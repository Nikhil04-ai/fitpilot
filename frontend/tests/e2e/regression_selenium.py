"""
FitPilot browser regression suite: Selenium driving headless Chrome against a real
backend. Every check goes through the UI the way a person would (clicks, typing,
Escape), never by calling the app's functions directly.

Not part of CI, because it needs a running backend + MySQL and Chrome. Run it locally:

    # 1. backend on :8080, with the rate limits raised (a run makes ~300 requests) and
    #    no Gemini/mail key, so nothing leaves the machine:
    cd backend
    APP_RATELIMIT_AUTH=1000 APP_RATELIMIT_STANDARD=5000 APP_RATELIMIT_AI=1000 \
      GEMINI_API_KEY= MAIL_API_KEY= ./mvnw spring-boot:run
    # 2. the frontend, served (any port the backend's CORS allows, i.e. localhost:*):
    cd frontend && python -m http.server 5500
    # 3. this:
    pip install selenium
    python frontend/tests/e2e/regression_selenium.py --url http://localhost:5500/

It creates throwaway accounts (e2e-*@example.com) on the backend it is pointed at, so
never point it at production. Exit code is the number of failed checks.

Works against builds from before the confirm modal too (it answers native confirm()
dialogs), so it can be run on an older checkout as a baseline. The checks for the
newer features are reported as "n/a" there.
"""
import argparse
import http.server
import json
import os
import pathlib
import socketserver
import sys
import tempfile
import threading
import time
import traceback
import urllib.error
import urllib.request
import uuid

from selenium import webdriver
from selenium.common.exceptions import (ElementClickInterceptedException, ElementNotInteractableException,
                                        StaleElementReferenceException, TimeoutException)
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import Select, WebDriverWait

PASSWORD = 'e2e-password-1'

# Toasts carry emoji; a Windows console codec would otherwise crash mid-report.
sys.stdout.reconfigure(encoding='utf-8', errors='replace')


# ── Backend seeding (plain HTTP, no browser) ────────────────────────────────────

class Api:
    def __init__(self, base):
        self.base = base.rstrip('/')

    def call(self, method, path, token=None, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header('Content-Type', 'application/json')
        if token:
            req.add_header('Authorization', 'Bearer ' + token)
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                raw = res.read()
                return res.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw)
            except ValueError:
                return e.code, raw.decode(errors='replace')

    def user(self, first):
        """Registers and logs in a fresh account. Returns (id, token, email)."""
        email = f'e2e-{first.lower()}-{uuid.uuid4().hex[:8]}@example.com'
        status, body = self.call('POST', '/api/auth/register',
                                 body={'email': email, 'password': PASSWORD, 'firstName': first,
                                       'lastName': 'Tester', 'weight': 70, 'height': 175, 'age': 30})
        assert status == 200, f'register {first}: {status} {body}'
        status, body = self.call('POST', '/api/auth/login', body={'email': email, 'password': PASSWORD})
        assert status == 200, f'login {first}: {status} {body}'
        return body['user']['id'], body['accessToken'], email


# ── A slow fake backend, for the wake-notice check ─────────────────────────────

class SlowApiHandler(http.server.BaseHTTPRequestHandler):
    """Answers every POST after DELAY seconds with a 401 — a Render instance waking up."""
    DELAY = 6

    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', self.headers.get('Origin') or '*')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length') or 0))
        time.sleep(self.DELAY)
        body = json.dumps({'error': 'Invalid email or password'}).encode()
        self.send_response(401)
        self._cors()
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


# ── The suite ──────────────────────────────────────────────────────────────────

class Suite:
    def __init__(self, url, api, frontend_dir, artifacts):
        self.url = url
        self.api = api
        self.frontend_dir = frontend_dir
        self.artifacts = artifacts
        self.results = []
        self.console = []
        self.download_dir = tempfile.mkdtemp(prefix='fitpilot-e2e-dl-')

        opts = webdriver.ChromeOptions()
        opts.add_argument('--headless=new')
        opts.add_argument('--window-size=1400,1000')
        opts.set_capability('goog:loggingPrefs', {'browser': 'ALL'})
        opts.add_experimental_option('prefs', {'download.default_directory': self.download_dir,
                                               'download.prompt_for_download': False})
        self.d = webdriver.Chrome(options=opts)
        self.wait = WebDriverWait(self.d, 15)
        self.modern = None   # set once the page is loaded: does it have the confirm modal?

    # helpers ---------------------------------------------------------------
    def check(self, name, fn, modern_only=False):
        if modern_only and not self.modern:
            self.results.append((name, 'n/a', 'feature not in this build'))
            print(f'  n/a   {name}')
            return
        try:
            detail = fn()
            self.results.append((name, 'PASS', detail or ''))
            print(f'  PASS  {name}' + (f' — {detail}' if detail else ''))
        except Exception as e:  # noqa: BLE001 - every failure is reported, the run goes on
            msg = f'{type(e).__name__}: {e}'.strip()
            self.results.append((name, 'FAIL', msg))
            print(f'  FAIL  {name} — {msg}')
            traceback.print_exc(limit=2, file=sys.stdout)
            try:
                self.d.save_screenshot(os.path.join(self.artifacts, f'fail-{len(self.results):02d}.png'))
            except Exception:
                pass
        finally:
            self.reset_ui()
            self.drain_console()

    def reset_ui(self):
        """Leaves no dialog open for the next check, the way a person would: Escape."""
        try:
            self.d.switch_to.alert.dismiss()
        except Exception:
            pass
        try:
            for _ in range(4):
                if not self.all('.modal-overlay.show'):
                    break
                self.d.switch_to.active_element.send_keys(Keys.ESCAPE)
                time.sleep(0.2)
        except Exception:
            pass

    def drain_console(self):
        try:
            self.console.extend(self.d.get_log('browser'))
        except Exception:
            pass

    def css(self, sel):
        return self.d.find_element(By.CSS_SELECTOR, sel)

    def all(self, sel):
        return self.d.find_elements(By.CSS_SELECTOR, sel)

    def visible(self, sel, timeout=15):
        return WebDriverWait(self.d, timeout).until(EC.visibility_of_element_located((By.CSS_SELECTOR, sel)))

    def gone(self, sel, timeout=15):
        WebDriverWait(self.d, timeout).until(EC.invisibility_of_element_located((By.CSS_SELECTOR, sel)))

    def press(self, el):
        """Clicks an element. If a toast is sitting on top of it, dismisses the toast
        with its ✕ first — what a person would do."""
        self.d.execute_script('arguments[0].scrollIntoView({block:"center"})', el)
        try:
            el.click()
        except ElementClickInterceptedException as e:
            if 'id="toast"' not in str(e):
                raise
            self.css('#toast .toast-close').click()
            WebDriverWait(self.d, 5).until(lambda d: 'on' not in self.css('#toast').get_attribute('class'))
            el.click()
        return el

    def click(self, sel):
        return self.press(WebDriverWait(self.d, 15).until(EC.element_to_be_clickable((By.CSS_SELECTOR, sel))))

    def click_text(self, sel, text):
        # .text is the rendered text, and .btn is text-transform: uppercase.
        def find(_):
            for el in self.all(sel):
                if text.lower() in el.text.lower() and el.is_displayed():
                    return el
            return False
        return self.press(WebDriverWait(self.d, 15).until(find))

    def click_in(self, parent, xpath_or_css, by=By.CSS_SELECTOR):
        return self.press(parent.find_element(by, xpath_or_css))

    def retry(self, fn, timeout=10):
        """Runs fn until it stops hitting an element that was re-rendered under it.
        Panels repaint asynchronously, some with no skeleton to wait on."""
        end = time.time() + timeout
        while True:
            try:
                return fn()
            except (StaleElementReferenceException, ElementNotInteractableException):
                if time.time() > end:
                    raise
                time.sleep(0.3)

    def text(self, sel):
        return self.retry(lambda: self.css(sel).text)

    def type(self, sel, text):
        el = self.visible(sel)
        el.clear()
        el.send_keys(str(text))

    def clear_toast(self):
        self.d.execute_script("document.getElementById('tmsg').textContent=''")

    def toast(self, *contains, timeout=20):
        """Waits for a toast containing any of the given texts. Returns its text."""
        def has(_):
            t = self.d.execute_script("return document.getElementById('tmsg').textContent") or ''
            return t if t and any(c.lower() in t.lower() for c in contains) else False
        try:
            return WebDriverWait(self.d, timeout).until(has)
        except TimeoutException:
            last = self.d.execute_script("return document.getElementById('tmsg').textContent")
            raise AssertionError(f'no toast containing {contains!r}; last toast: {last!r}')

    def nav(self, panel):
        self.click(f'.sb-link[data-panel="{panel}"]')
        self.visible(f'#panel-{panel}.on')
        self.settled(panel)

    def settled(self, panel, timeout=20):
        """Waits until a panel has no spinners or skeletons left."""
        WebDriverWait(self.d, timeout).until(lambda d: d.execute_script(
            "const p=document.getElementById(arguments[0]);"
            "return !p.querySelector('.loading-state, .sk')", f'panel-{panel}'))

    def answer_confirm(self, accept):
        """The confirm modal on new builds, window.confirm() on old ones."""
        if self.modern:
            self.visible('#modal-confirm.show')
            self.click('#confirm-ok' if accept else '#confirm-cancel')
            self.gone('#modal-confirm.show')
        else:
            alert = WebDriverWait(self.d, 10).until(EC.alert_is_present())
            alert.accept() if accept else alert.dismiss()

    def local(self, key):
        return self.d.execute_script('return localStorage.getItem(arguments[0])', key)

    def token(self):
        return self.local('fp_accessToken')

    def me(self):
        return json.loads(self.local('fp_user'))

    # checks ----------------------------------------------------------------
    def run(self):
        d = self.d
        d.get(self.url)
        self.visible('#auth-screen')
        self.modern = d.execute_script("return !!document.getElementById('modal-confirm')")
        print(f'Build under test: {"new (confirm modal present)" if self.modern else "baseline (native confirm)"}\n')

        self.check('auth: tabs and links switch between the four forms', self.c_auth_tabs)
        self.check('auth: forgot-password submits and returns to login', self.c_forgot)
        self.check('auth: wrong password shows an error', self.c_wrong_password)
        self.check('auth: register logs straight in', self.c_register)
        self.check('no handler functions left on window', self.c_no_window_globals, modern_only=True)
        self.check('every sidebar panel opens and loads without an error state', self.c_all_panels)
        self.check('log-workout modal: tabs, exercise rows, Escape closes and returns focus', self.c_log_modal)
        self.check('log a workout through the modal', self.c_log_workout)
        self.check('activity row: rate, edit, delete (cancel, then confirm)', self.c_activity_row)
        self.check('goals: hint on type change, create, edit, delete with confirm', self.c_goals)
        self.check('nutrition: tabs, log, edit, delete with confirm', self.c_nutrition)
        self.check('pain log: log via modal, pager Next/Prev', self.c_pain_pager)
        self.check('health: log water', self.c_water)
        self.check('plans: enroll and complete a day', self.c_plans)
        self.check('insights: plateau check', self.c_plateau)
        self.check('AI coach: weekly digest and insight buttons respond', self.c_coach)
        self.check('overview: "View AI Coach" teaser button navigates', self.c_overview_teaser)
        self.check('friends: accept, decline, remove with confirm', self.c_friends)
        self.check('challenges: create, cancel ("Keep it" then confirm)', self.c_challenge_cancel)
        self.check('challenges: decline an invite; accept, details, leave with confirm', self.c_challenge_invites)
        self.check('notifications: open deep-link, mark read, mark all read, bell', self.c_notifications)
        self.check('profile: save, export JSON + CSV downloads', self.c_profile)
        self.check('profile: change password', self.c_password)
        self.check('toast: dismiss button', self.c_toast_dismiss)
        self.check('mobile: menu button opens the sidebar, a link closes it', self.c_mobile)
        self.check('deep link survives a reload; Back returns', self.c_deep_link)
        self.check('logout, then log back in with the new password', self.c_logout_login)
        self.check('CSP blocks an injected inline handler', self.c_csp_blocks_inline, modern_only=True)
        self.check('wake notice: shown while a sleeping backend wakes, then hidden', self.c_wake_notice, modern_only=True)
        self.check('file:// shows the "use a local server" message', self.c_file_protocol)
        self.check('browser console: no JS errors or CSP violations', self.c_console)

    def c_auth_tabs(self):
        self.click_text('#form-login .auth-hint a', 'Create one')
        self.visible('#form-register')
        self.click_text('#form-register .auth-hint a', 'Log in')
        self.visible('#form-login')
        self.click('#forgot-link')
        self.visible('#form-forgot')
        self.click_text('#form-forgot .auth-hint a', 'Back to log in')
        self.visible('#form-login')
        self.click('#tab-register')
        self.visible('#form-register')
        assert self.css('#tab-register').get_attribute('aria-selected') == 'true'
        self.click('#tab-login')
        self.visible('#form-login')

    def c_forgot(self):
        self.click('#forgot-link')
        self.type('#forgot-email', f'nobody-{uuid.uuid4().hex[:6]}@example.com')
        self.clear_toast()
        self.click('#forgot-btn')
        t = self.toast('reset link')
        self.visible('#form-login')
        return t

    def c_wrong_password(self):
        self.type('#login-email', 'nobody@example.com')
        self.type('#login-password', 'wrong-password')
        self.click('#login-btn')
        err = self.visible('#auth-error.show')
        assert 'Invalid' in err.text, err.text
        assert self.d.current_url.startswith(self.url.split('#')[0]), 'form must not navigate'

    def c_register(self):
        self.click('#tab-register')
        self.email = f'e2e-ui-{uuid.uuid4().hex[:8]}@example.com'
        self.type('#reg-first', 'Uma')
        self.type('#reg-last', 'Tester')
        self.type('#reg-email', self.email)
        self.type('#reg-password', PASSWORD)
        self.type('#reg-weight', '70')
        self.type('#reg-height', '175')
        self.type('#reg-age', '30')
        self.click('#register-btn')
        self.visible('#app-shell.show')
        self.settled('overview')
        name = self.css('#sb-uname').text
        assert name == 'Uma Tester', name
        self.password = PASSWORD
        return self.email

    def c_no_window_globals(self):
        leaked = self.d.execute_script(
            "return ['nav','deleteGoal','handleLogin','openModal','closeModal','loadActivities',"
            "'removeFriend','downloadExport','toggleSidebar'].filter(n => n in window)")
        assert leaked == [], f'still on window: {leaked}'

    def c_all_panels(self):
        panels = [el.get_attribute('data-panel') for el in self.all('.sb-link')]
        bad = []
        for p in panels:
            self.nav(p)
            title = self.css('#topbar-ttl').text
            errors = [e.text for e in self.all(f'#panel-{p} .error-state') if e.is_displayed()]
            if errors or not title:
                bad.append(f'{p}: {errors or "no title"}')
            if not self.d.current_url.endswith('#' + p):
                bad.append(f'{p}: hash not updated ({self.d.current_url})')
        assert not bad, bad
        return f'{len(panels)} panels'

    def c_log_modal(self):
        opener = self.click('.topbar-right .btn-prim')
        self.visible('#modal-log-activity.show')
        self.click('#log-tab-natural')
        self.visible('#form-log-natural')
        self.click('#log-tab-manual')
        self.visible('#form-log-activity')
        add = '#form-log-activity button[data-action="add-exercise"]' if self.modern \
            else '#form-log-activity .btn-ghost'
        self.click(add)
        self.click(add)
        assert len(self.all('#act-exercises .exrow')) == 2
        self.press(self.all('#act-exercises .exdel')[1])
        assert len(self.all('#act-exercises .exrow')) == 1
        self.css('#act-duration').send_keys(Keys.ESCAPE)
        self.gone('#modal-log-activity.show')
        focused = self.d.switch_to.active_element
        assert focused == opener, 'focus should return to the button that opened the modal'

    def c_log_workout(self):
        self.click('.topbar-right .btn-prim')
        self.visible('#modal-log-activity.show')
        Select(self.css('#act-type')).select_by_value('WEIGHT_TRAINING')
        self.type('#act-duration', 30)
        self.type('#act-notes', "Leg day — it's fine")
        self.type('#act-exercises .ex-name', 'Squat')
        self.type('#act-exercises .ex-sets', 3)
        self.type('#act-exercises .ex-reps', 5)
        self.type('#act-exercises .ex-weight', 100)
        self.clear_toast()
        self.click('#log-activity-btn')
        # A first workout is also a personal best, which gets its own message.
        t = self.toast('Workout logged', 'personal best')
        self.gone('#modal-log-activity.show')
        return t

    def c_activity_row(self):
        self.nav('activities')
        rows = lambda: self.all('#activities-list .ai')
        assert len(rows()) == 1, f'{len(rows())} rows'
        self.clear_toast()
        self.press(self.all('#activities-list .rate button')[3])
        self.toast('Rated 4/5')
        self.settled('activities')

        self.click('#activities-list .ai-acts button[title="Edit"]')
        self.visible('#modal-edit-activity.show')
        assert self.css('#edit-act-duration').get_attribute('value') == '30'
        assert self.css('#edit-act-notes').get_attribute('value') == "Leg day — it's fine"
        self.type('#edit-act-duration', 45)
        self.clear_toast()
        self.click('#edit-activity-btn')
        self.toast('Workout updated')
        self.settled('activities')
        assert '45 min' in self.css('#activities-list .ai').text

        self.click('#activities-list .ai-acts button[title="Delete"]')
        self.answer_confirm(False)
        time.sleep(0.5)
        assert len(rows()) == 1, 'cancel must keep the workout'
        self.clear_toast()
        self.click('#activities-list .ai-acts button[title="Delete"]')
        self.answer_confirm(True)
        self.toast('Workout deleted')
        self.settled('activities')
        assert len(rows()) == 0

    def c_goals(self):
        self.nav('goals')
        self.click_text('#panel-goals .btn-prim', 'New Goal')
        self.visible('#modal-create-goal.show')
        Select(self.css('#goal-type')).select_by_value('TARGET_WEIGHT')
        hint = self.css('#goal-target-hint').text
        assert 'weight' in hint.lower(), f'hint did not follow the type change: {hint!r}'
        Select(self.css('#goal-type')).select_by_value('WORKOUTS_PER_WEEK')
        assert 'Sessions per week' in self.css('#goal-target-hint').text
        self.type('#goal-target', 4)
        self.clear_toast()
        self.click('#create-goal-btn')
        self.toast('Goal created')
        self.settled('goals')

        self.click_text('#goals-list .g-del', 'Edit')
        self.visible('#modal-edit-goal.show')
        assert self.css('#edit-goal-target').get_attribute('value') == '4'
        self.type('#edit-goal-target', 5)
        self.clear_toast()
        self.click('#edit-goal-btn')
        self.toast('Goal updated')
        self.settled('goals')
        assert '5' in self.css('#goals-list .gi').text

        self.click_text('#goals-list .g-del', 'Delete')
        self.answer_confirm(False)
        time.sleep(0.5)
        assert len(self.all('#goals-list .gi')) == 1
        self.clear_toast()
        self.click_text('#goals-list .g-del', 'Delete')
        self.answer_confirm(True)
        self.toast('Goal removed')

    def c_nutrition(self):
        self.nav('nutrition')
        self.click_text('#panel-nutrition .btn-prim', 'Log Meal')
        self.visible('#modal-log-meal.show')
        self.click('#meal-tab-natural')
        self.visible('#form-log-meal-natural')
        self.click('#meal-tab-manual')
        self.visible('#form-log-meal')
        self.type('#meal-description', "Oats 'n' berries")
        self.type('#meal-calories', 450)
        self.clear_toast()
        self.click('#log-meal-btn')
        self.toast('Meal logged')
        self.settled('nutrition')

        self.click('#nutrition-meals .ai-acts button[title="Edit"]')
        self.visible('#modal-edit-meal.show')
        assert self.css('#edit-meal-description').get_attribute('value') == "Oats 'n' berries"
        self.type('#edit-meal-calories', 500)
        self.clear_toast()
        self.click('#edit-meal-btn')
        self.toast('Meal updated')
        self.settled('nutrition')

        self.clear_toast()
        self.click('#nutrition-meals .ai-acts button[title="Delete"]')
        self.answer_confirm(True)
        self.toast('Meal removed')

    def c_pain_pager(self):
        token = self.token()
        for i in range(15):
            s, b = self.api.call('POST', '/api/pain-log', token,
                                 {'bodyPart': 'NECK', 'severity': 2, 'description': f'seed {i}'})
            assert s == 200, (s, b)
        self.nav('pain')
        self.click_text('#panel-pain .btn-prim', 'Log Pain')
        self.visible('#modal-log-pain.show')
        self.type('#pain-description', 'from the modal')
        self.clear_toast()
        self.click('#log-pain-btn')
        self.toast('Pain logged')
        self.settled('pain')
        pos = lambda: self.text('#pain-pager .pager-pos')
        assert pos() == '1 of 2', pos()
        assert 'from the modal' in self.text('#pain-list .ai'), 'newest first'
        self.click('#pain-pager button[aria-label="Next page"]')
        WebDriverWait(self.d, 10).until(lambda d: pos() == '2 of 2')
        assert not self.css('#pain-pager button[aria-label="Next page"]').is_enabled()
        self.click('#pain-pager button[aria-label="Previous page"]')
        WebDriverWait(self.d, 10).until(lambda d: pos() == '1 of 2')

    def c_water(self):
        self.nav('health')
        self.type('#water-amount', 300)
        self.clear_toast()
        self.click_text('#water-content .btn-prim', 'Add')
        return self.toast('+300ml')

    def c_plans(self):
        self.nav('plans')
        enroll = self.all('#plans-list .cjb')
        if not enroll:
            return 'no plans seeded on this database; skipped'
        self.clear_toast()
        self.press(enroll[0])
        self.toast('Enrolled')
        self.settled('plans')
        self.clear_toast()
        self.click_text('#active-plan-card .btn-prim', 'Mark Today Complete')
        return self.toast('Day complete')

    def c_plateau(self):
        self.nav('insights')
        before = self.css('#plateau-result').text
        self.click_text('#panel-insights .btn-prim', 'Check')
        WebDriverWait(self.d, 15).until(lambda d: self.css('#plateau-result').text not in (before, '', 'Checking…')
                                        and not self.all('#plateau-result .spinner'))
        return self.css('#plateau-result').text[:60]

    def c_coach(self):
        self.nav('ai')
        self.clear_toast()
        self.click('#gen-digest-btn')
        # A brand-new account has no finished week to summarise, and says so.
        t1 = self.toast('Digest ready', 'nothing to summarise')
        WebDriverWait(self.d, 15).until(lambda d: self.css('#gen-digest-btn').is_enabled())
        self.settled('ai')
        self.clear_toast()
        self.click('#gen-insight-btn')
        # The workout was deleted earlier, so this answers "log a workout first".
        t2 = self.toast('Log a workout first', 'insight')
        return f'digest: {t1!r}; insight: {t2!r}'

    def c_overview_teaser(self):
        self.nav('overview')
        self.retry(lambda: self.click_text('#overview-ai-teaser button', 'View'))
        self.visible('#panel-ai.on')

    def c_friends(self):
        me = self.me()
        self.b_id, self.b_tok, _ = self.api.user('Bea')
        self.c_id, self.c_tok, _ = self.api.user('Cal')
        _, d_tok, _ = self.api.user('Dee')
        for tok in (self.b_tok, self.c_tok, d_tok):
            s, b = self.api.call('POST', '/api/friends/request', tok, {'email': self.email})
            assert s == 200, (s, b)
        self.nav('challenges')
        self.visible('#friend-invites .ai')
        invites = lambda: [e.text for e in self.all('#friend-invites .ai')]
        assert len(invites()) == 3, invites()

        def act(name, title):
            row = next(r for r in self.all('#friend-invites .ai') if name in r.text)
            self.press(row.find_element(By.CSS_SELECTOR, f'button[title="{title}"]'))

        self.clear_toast()
        act('Bea', 'Accept')
        self.toast("now friends")
        WebDriverWait(self.d, 10).until(lambda d: len(invites()) == 2)
        self.clear_toast()
        act('Cal', 'Accept')
        self.toast("now friends")
        WebDriverWait(self.d, 10).until(lambda d: len(invites()) == 1)
        self.clear_toast()
        act('Dee', 'Decline')
        self.toast('declined')
        WebDriverWait(self.d, 10).until(lambda d: len(invites()) == 0)
        WebDriverWait(self.d, 10).until(lambda d: len(self.all('#friends-list .ai')) == 2)

        # Remove someone who isn't needed later: add Dee back as a friend first.
        _, d2_tok, _ = self.api.user('Eve')
        self.api.call('POST', '/api/friends/request', d2_tok, {'email': self.email})
        self.nav('overview'); self.nav('challenges')   # leave and return
        self.d.refresh()
        self.visible('#app-shell.show'); self.settled('challenges')
        act('Eve', 'Accept')
        WebDriverWait(self.d, 10).until(lambda d: len(self.all('#friends-list .ai')) == 3)
        eve = lambda: [r for r in self.all('#friends-list .ai') if 'Eve' in r.text]
        self.press(eve()[0].find_element(By.CSS_SELECTOR, 'button[title="Remove"]'))
        self.answer_confirm(False)
        time.sleep(0.5)
        assert eve(), 'cancel must keep the friend'
        self.clear_toast()
        self.press(eve()[0].find_element(By.CSS_SELECTOR, 'button[title="Remove"]'))
        self.answer_confirm(True)
        self.toast('Friend removed')
        WebDriverWait(self.d, 10).until(lambda d: not eve())
        return f'me={me["id"][:8]}'

    def c_challenge_cancel(self):
        self.nav('challenges')
        self.click_text('#panel-challenges .btn-prim', 'New Challenge')
        self.visible('#modal-create-challenge.show')
        Select(self.css('#ch-opponents')).select_by_value(self.b_id)
        self.type('#ch-title', "Bea's burn-off")
        self.clear_toast()
        self.click('#create-challenge-btn')
        self.toast('Challenge sent')
        self.settled('challenges')
        card = lambda: [c for c in self.all('#challenges-list .chc') if "Bea's burn-off" in c.text]
        WebDriverWait(self.d, 10).until(lambda d: card())

        self.press(card()[0].find_element(By.CSS_SELECTOR, '.g-del'))
        if self.modern:
            self.visible('#modal-confirm.show')
            # textContent, not .text: the rendered text is uppercased by .btn's CSS.
            label = lambda sel: self.css(sel).get_attribute('textContent')
            assert label('#confirm-cancel') == 'Keep it', label('#confirm-cancel')
            assert label('#confirm-ok') == 'Cancel challenge', label('#confirm-ok')
        self.answer_confirm(False)
        time.sleep(0.5)
        assert card(), '"Keep it" must keep the challenge'
        self.clear_toast()
        self.press(card()[0].find_element(By.CSS_SELECTOR, '.g-del'))
        self.answer_confirm(True)
        self.toast('Challenge cancelled')

    def c_challenge_invites(self):
        me = self.me()['id']
        s, b = self.api.call('POST', '/api/challenges', self.c_tok,
                             {'opponentIds': [me], 'type': 'WORKOUT_COUNT', 'title': 'Cal invites', 'durationDays': 7})
        assert s == 200, (s, b)
        s, b = self.api.call('POST', '/api/challenges', self.b_tok,
                             {'opponentIds': [me], 'type': 'CALORIES_BURNED', 'title': 'Bea invites', 'durationDays': 7})
        assert s == 200, (s, b)
        self.nav('overview')
        self.d.refresh()
        self.visible('#app-shell.show')
        self.nav('challenges')
        self.visible('#invites-section')
        inv = lambda t: [c for c in self.all('#invites-list .chc') if t in c.text]
        WebDriverWait(self.d, 10).until(lambda d: inv('Cal invites') and inv('Bea invites'))
        assert self.css('#invite-badge').text == '2'

        self.clear_toast()
        self.press(inv('Cal invites')[0].find_element(By.XPATH, ".//button[contains(., 'Decline')]"))
        self.toast('declined')
        WebDriverWait(self.d, 10).until(lambda d: not inv('Cal invites'))
        self.clear_toast()
        self.press(inv('Bea invites')[0].find_element(By.XPATH, ".//button[contains(., 'Accept')]"))
        self.toast('accepted')
        self.settled('challenges')

        mine = lambda: [c for c in self.all('#challenges-list .chc') if 'Bea invites' in c.text]
        WebDriverWait(self.d, 10).until(lambda d: mine())
        self.press(mine()[0].find_element(By.XPATH, ".//button[contains(., 'Details')]"))
        self.visible('#modal-challenge-detail.show')
        WebDriverWait(self.d, 10).until(lambda d: self.css('#cd-title').text == 'Bea invites')
        self.click('#modal-challenge-detail .modal-close')
        self.gone('#modal-challenge-detail.show')

        self.clear_toast()
        self.press(mine()[0].find_element(By.XPATH, ".//button[contains(., 'Leave')]"))
        self.answer_confirm(True)
        self.toast('left the challenge')

    def c_notifications(self):
        self.nav('overview')
        self.d.refresh()
        self.visible('#app-shell.show')
        self.settled('overview')
        bell = self.css('#bell-count')
        unread = int(bell.text) if bell.is_displayed() and bell.text.isdigit() else 0
        assert unread > 0, 'friend requests and invites should have left unread notifications'
        self.click('#bell-btn')
        self.visible('#panel-notifications.on')
        self.settled('notifications')
        self.click('#notif-list .ai-acts button[title="Open"]')
        WebDriverWait(self.d, 10).until(lambda d: 'challenges' in d.current_url)
        self.visible('#panel-challenges.on')
        self.nav('notifications')
        before = len(self.all('#notif-list .ai.unread'))
        self.click('#notif-list .ai-acts button[title="Mark read"]')
        WebDriverWait(self.d, 10).until(lambda d: len(self.all('#notif-list .ai.unread')) == before - 1)
        self.clear_toast()
        self.click('#mark-all-read-btn')
        t = self.toast('Marked')
        WebDriverWait(self.d, 10).until(lambda d: not self.css('#bell-count').is_displayed())
        return f'{unread} unread; {t}'

    def c_profile(self):
        self.nav('profile')
        WebDriverWait(self.d, 10).until(lambda d: self.css('#prof-first').get_attribute('value') == 'Uma')
        self.type('#prof-first', 'Umaa')
        self.clear_toast()
        self.click('#form-profile button[type="submit"]')
        self.toast('Profile updated')
        assert self.css('#sb-uname').text == 'Umaa Tester'

        before = set(os.listdir(self.download_dir))
        self.clear_toast()
        self.click_text('#panel-profile .btn', '.json')
        self.toast('Downloaded')
        WebDriverWait(self.d, 10).until(lambda d: len(self.all('#export-dataset option')) > 1)
        Select(self.css('#export-dataset')).select_by_value('pain')
        self.clear_toast()
        self.click_text('#panel-profile .btn', 'One dataset')
        self.toast('Downloaded')

        def finished(_):
            files = set(os.listdir(self.download_dir)) - before
            done = [f for f in files if not f.endswith('.crdownload')]
            return done if len(done) >= 2 else False
        files = WebDriverWait(self.d, 20).until(finished)
        csv = [f for f in files if f.endswith('.csv')]
        assert csv and 'pain' in csv[0], files
        with open(os.path.join(self.download_dir, csv[0]), encoding='utf-8') as fh:
            assert fh.read().count('\n') >= 16, 'CSV holds the 16 pain entries'
        return ', '.join(sorted(files))

    def c_password(self):
        self.nav('profile')
        self.type('#pwd-current', PASSWORD)
        self.password = PASSWORD + 'x'
        self.type('#pwd-new', self.password)
        self.clear_toast()
        self.click('#form-password button[type="submit"]')
        self.toast('Password changed')
        self.nav('overview')   # still signed in on this tab after the change (P4-3)
        assert not self.all('#panel-overview .error-state')

    def c_toast_dismiss(self):
        self.nav('health')
        self.type('#water-amount', 100)
        self.click_text('#water-content .btn-prim', 'Add')
        self.visible('#toast.on')
        self.click('#toast .toast-close')
        WebDriverWait(self.d, 5).until(lambda d: 'on' not in self.css('#toast').get_attribute('class'))

    def c_mobile(self):
        self.d.set_window_size(390, 844)
        try:
            btn = self.visible('#mobile-menu-btn')
            assert 'show' not in self.css('#sidebar').get_attribute('class')
            self.press(btn)
            WebDriverWait(self.d, 5).until(lambda d: 'show' in self.css('#sidebar').get_attribute('class'))
            assert btn.get_attribute('aria-expanded') == 'true'
            time.sleep(0.4)   # the sidebar slides in
            self.click('.sb-link[data-panel="goals"]')
            self.visible('#panel-goals.on')
            WebDriverWait(self.d, 5).until(lambda d: 'show' not in self.css('#sidebar').get_attribute('class'))
            assert btn.get_attribute('aria-expanded') == 'false'
        finally:
            self.d.set_window_size(1400, 1000)

    def c_deep_link(self):
        base = self.url.split('#')[0]
        self.d.get(base + '#plans')
        self.visible('#app-shell.show')
        self.visible('#panel-plans.on')
        self.nav('recovery')
        self.d.back()
        self.visible('#panel-plans.on')

    def c_logout_login(self):
        self.click('.sb-logout[title="Log out"]')
        self.visible('#auth-screen')
        assert self.local('fp_accessToken') is None
        assert '#' not in self.d.current_url, 'panel hash dropped on logout'
        self.type('#login-email', self.email)
        self.type('#login-password', self.password)
        self.click('#login-btn')
        self.visible('#app-shell.show')
        assert self.css('#sb-uname').text == 'Umaa Tester'

    def c_csp_blocks_inline(self):
        meta = self.d.execute_script(
            "return document.querySelector('meta[http-equiv=\"Content-Security-Policy\"]')?.content")
        assert meta and "script-src 'self'" in meta, meta
        self.d.execute_script(
            "window.__xss = 0; const d = document.createElement('div');"
            "d.innerHTML = '<img src=\"data:,x\" onerror=\"window.__xss = 1\">'; document.body.appendChild(d);")
        time.sleep(1)
        assert self.d.execute_script('return window.__xss') == 0, 'injected onerror ran'
        violations = [e for e in self.d.get_log('browser') if 'Content Security Policy' in e['message']]
        assert violations, 'expected the browser to report the blocked handler'
        return 'blocked and reported'

    def c_wake_notice(self):
        server = socketserver.ThreadingTCPServer(('127.0.0.1', 0), SlowApiHandler)
        server.daemon_threads = True
        port = server.server_address[1]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            # 127.0.0.2 is loopback but not "localhost" to config.js, so the page
            # behaves as deployed: IS_LOCAL is false and the notice is armed.
            page = self.url.replace('localhost', '127.0.0.2').split('#')[0].split('?')[0]
            self.d.get(f'{page}?api=http://127.0.0.1:{port}')
            self.visible('#auth-screen')
            notice = lambda: self.css('#wake-notice').is_displayed()
            self.type('#login-email', 'sleepy@example.com')
            self.type('#login-password', 'whatever-1')
            t0 = time.time()
            self.click('#login-btn')
            time.sleep(1.5)
            assert not notice(), 'too early: a normal request should not show it'
            WebDriverWait(self.d, 5).until(lambda d: notice())
            shown_at = time.time() - t0
            text = self.css('#wake-notice').text
            assert 'Waking up the server' in text, text
            self.visible('#auth-error.show', timeout=15)
            hidden = not notice()
            assert hidden, 'should hide once the server answers'
            return f'shown after {shown_at:.1f}s, hidden after the {SlowApiHandler.DELAY}s response'
        finally:
            server.shutdown()
            self.d.execute_script("localStorage.removeItem('fp_apiBase')")

    def c_file_protocol(self):
        self.d.get(pathlib.Path(self.frontend_dir, 'index.html').resolve().as_uri())
        err = self.visible('#auth-error.show', timeout=5)
        assert 'local server' in err.text, err.text

    def c_console(self):
        self.drain_console()
        ignorable = ('Failed to load resource',)          # deliberate 401s/404s/409s
        expected_csp = 'onerror'                           # the CSP check's own injection
        bad = [e['message'] for e in self.console
               if e['level'] == 'SEVERE'
               and not any(s in e['message'] for s in ignorable)
               and not ('Content Security Policy' in e['message'] and expected_csp in e['message'])
               and not e['message'].startswith('file://')]   # module load refusal on file://
        csp = [m for m in bad if 'Content Security Policy' in m or 'Refused to' in m]
        assert not bad, bad[:5]
        return f'{len(self.console)} entries, 0 errors, {len(csp)} CSP violations'

    def close(self):
        self.d.quit()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--url', default='http://localhost:5500/', help='where the frontend is served')
    ap.add_argument('--api', default='http://localhost:8080', help='the backend, for seeding')
    ap.add_argument('--frontend-dir', default=str(pathlib.Path(__file__).resolve().parents[2]),
                    help='the folder being served, for the file:// check')
    ap.add_argument('--artifacts', default=tempfile.gettempdir(), help='failure screenshots go here')
    args = ap.parse_args()

    suite = Suite(args.url, Api(args.api), args.frontend_dir, args.artifacts)
    try:
        suite.run()
    finally:
        suite.close()

    counts = {k: sum(1 for r in suite.results if r[1] == k) for k in ('PASS', 'FAIL', 'n/a')}
    print(f"\n{counts['PASS']} passed, {counts['FAIL']} failed, {counts['n/a']} n/a "
          f"(of {len(suite.results)} checks)")
    sys.exit(counts['FAIL'])


if __name__ == '__main__':
    main()
