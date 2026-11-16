// What the full-app test run of 2026-11-16 found in the frontend, one test per fix.
// Each asserts the behaviour that was wrong, so it fails on the code from before.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el, jsonResponse } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1', firstName: 'Anna', lastName: 'Berg' }) }
});
// The toast is used by several modules; give it its real children.
const toastEl = document.getElementById('toast');
toastEl.appendChild(el('div', { class: 'tic' }));
toastEl.appendChild(el('button', { class: 'toast-close' }));

const { localDay, lastDays, today } = await import('../js/util.js');
const { openModal, closeAllModals, toggleSidebar, toast, hideToast } = await import('../js/ui.js');
const { showAuthScreen } = await import('../js/auth.js');
const { refreshUnreadCount } = await import('../js/panels/notifications.js');
const { rememberActivities, openEditActivity, handleEditActivity } = await import('../js/panels/activities.js');
const { activePlanSheet } = await import('../js/panels/plans.js');
const { challengeCard } = await import('../js/panels/social.js');
const { openEditGoal } = await import('../js/panels/goals.js');
const { handleUpdateProfile } = await import('../js/panels/profile.js');
const { session } = await import('../js/state.js');

/** Scripted fetch that records each call and answers from `reply(url, opts)`. */
let calls;
function fakeFetch(reply) {
  calls = [];
  globalThis.fetch = async (url, opts = {}) => { calls.push({ url: String(url), opts }); return reply(String(url), opts); };
}

beforeEach(() => {
  session.accessToken = 'access';
  session.currentUser = { id: 'u-1', firstName: 'Anna', lastName: 'Berg' };
  session.expiredInProgress = false;
});

// ── Dates (F3, F19) ───────────────────────────────────────────────────────────

test('a date-only value is that day everywhere, including west of UTC', () => {
  const before = process.env.TZ;
  process.env.TZ = 'America/Los_Angeles';
  try {
    // The trap: a bare date is UTC midnight, the evening before in Los Angeles.
    assert.equal(new Date('2026-11-16').getDate(), 15);
    const d = localDay('2026-11-16');
    assert.equal(d.getDate(), 16);
    assert.equal(d.toLocaleDateString('en', { weekday: 'short' }), 'Mon');
    assert.equal(localDay('2026-11-16T07:30:00').getDate(), 16, 'a date-time is cut to its date');
  } finally {
    if (before === undefined) delete process.env.TZ; else process.env.TZ = before;
  }
});

test('the water week is seven days ending today, so an empty day has its place', () => {
  const days = lastDays(7);
  assert.equal(days.length, 7);
  assert.equal(days[6], today());
  assert.ok(days.every((d, i) => i === 0 || d > days[i - 1]), 'oldest first, no gaps or repeats');
});

// ── Dialogs, drawer, toast (U3 to U7) ────────────────────────────────────────

test('a dialog that starts with a hidden id field focuses its first real field (U3)', () => {
  const real = el('input', { id: 'real-field', type: 'number' });
  const modal = el('div', { id: 'modal-hidden-first', class: 'modal-overlay' },
    el('input', { id: 'hidden-id', type: 'hidden' }), real, el('button', { class: 'modal-close' }));
  document.body.appendChild(modal);
  openModal('modal-hidden-first');
  assert.equal(document.activeElement, real);
  closeAllModals();
});

test('changing panel closes any open dialog; closeAllModals closes them all (U7)', () => {
  for (const id of ['modal-a', 'modal-b']) {
    document.body.appendChild(el('div', { id, class: 'modal-overlay' }, el('button', {})));
    openModal(id);
  }
  closeAllModals();
  assert.equal(document.getElementById('modal-a').classList.contains('show'), false);
  assert.equal(document.getElementById('modal-b').classList.contains('show'), false);
});

test('opening the drawer moves focus into it and shows the scrim; closing returns focus (U4, U5)', () => {
  const link = el('button', { class: 'sb-link' });
  document.body.appendChild(el('aside', { id: 'sidebar' }, link));
  const menu = el('button', { id: 'mobile-menu-btn' });
  document.body.appendChild(menu);
  const scrim = el('div', { id: 'drawer-scrim' });
  scrim.hidden = true;
  document.body.appendChild(scrim);

  menu.focus();
  toggleSidebar(true);
  assert.equal(document.activeElement, link);
  assert.equal(scrim.hidden, false);
  assert.equal(menu.getAttribute('aria-expanded'), 'true');

  toggleSidebar(false);
  assert.equal(document.activeElement, menu);
  assert.equal(scrim.hidden, true);
});

test('the toast\'s dismiss button is a tab stop only while a toast shows (U6)', () => {
  const close = toastEl.querySelector('.toast-close');
  toast('Saved');
  assert.equal(close.tabIndex, 0);
  hideToast();
  assert.equal(close.tabIndex, -1);
});

// ── Leaving the app (U1, U2) ─────────────────────────────────────────────────

test('logging out empties the password fields, hides the + button and closes dialogs (U1, U2)', () => {
  document.getElementById('login-email').value = 'anna@example.com';
  document.getElementById('login-password').value = 'secret-password';
  document.getElementById('fab-log').style.display = 'flex';
  document.body.appendChild(el('div', { id: 'modal-log-x', class: 'modal-overlay' }, el('button', {})));
  openModal('modal-log-x');

  showAuthScreen({ forgetEmail: true });
  assert.equal(document.getElementById('login-password').value, '');
  assert.equal(document.getElementById('login-email').value, '');
  assert.equal(document.getElementById('fab-log').style.display, 'none');
  assert.equal(document.getElementById('modal-log-x').classList.contains('show'), false);
  assert.equal(document.getElementById('auth-screen').style.display, 'flex');
});

test('an expired session keeps the email, since the same person is asked back in', () => {
  document.getElementById('login-email').value = 'anna@example.com';
  document.getElementById('login-password').value = 'secret-password';
  showAuthScreen();
  assert.equal(document.getElementById('login-email').value, 'anna@example.com');
  assert.equal(document.getElementById('login-password').value, '');
});

// ── The bell (U9) ────────────────────────────────────────────────────────────

test('the bell\'s accessible name carries the unread count (U9)', async () => {
  const bell = el('button', { id: 'bell-btn', 'aria-label': 'Notifications' });
  document.body.appendChild(bell);
  fakeFetch(() => jsonResponse(200, { unread: 3 }));
  await refreshUnreadCount();
  assert.equal(bell.getAttribute('aria-label'), 'Notifications, 3 unread');
  fakeFetch(() => jsonResponse(200, { unread: 0 }));
  await refreshUnreadCount();
  assert.equal(bell.getAttribute('aria-label'), 'Notifications');
});

// ── Workouts (F7, F8) ────────────────────────────────────────────────────────

test('Edit works on a row any panel drew, not only the Activities page (F7)', () => {
  rememberActivities([{ id: 'ov-1', type: 'RUNNING', duration: 30, notes: 'from the overview', distanceKm: 5 }]);
  openEditActivity('ov-1');
  assert.equal(document.getElementById('edit-act-id').value, 'ov-1');
  assert.equal(document.getElementById('edit-act-notes').value, 'from the overview');
});

test('emptying a workout\'s notes and distance sends them in clear, not as null (F8)', async () => {
  rememberActivities([{ id: 'w-1', type: 'WEIGHT_TRAINING', duration: 45, notes: 'Strong day', distanceKm: 2.5 }]);
  document.getElementById('edit-act-id').value = 'w-1';
  document.getElementById('edit-act-type').value = 'WEIGHT_TRAINING';
  document.getElementById('edit-act-duration').value = '45';
  document.getElementById('edit-act-notes').value = '';
  document.getElementById('edit-act-distance').value = '';
  fakeFetch(() => jsonResponse(200, {}));
  await handleEditActivity({ preventDefault() {} });

  const put = calls.find(c => c.opts.method === 'PUT');
  assert.ok(put, 'a PUT was sent');
  assert.deepEqual(JSON.parse(put.opts.body).clear.sort(), ['distanceKm', 'notes']);
});

test('a workout that never had notes or a distance sends no clear', async () => {
  rememberActivities([{ id: 'w-2', type: 'RUNNING', duration: 30, notes: null, distanceKm: null }]);
  document.getElementById('edit-act-id').value = 'w-2';
  document.getElementById('edit-act-notes').value = '';
  document.getElementById('edit-act-distance').value = '';
  fakeFetch(() => jsonResponse(200, {}));
  await handleEditActivity({ preventDefault() {} });
  const put = calls.find(c => c.opts.method === 'PUT');
  assert.equal(JSON.parse(put.opts.body).clear, undefined);
});

// ── Plans, challenges, goals, profile (F6, F15, F40, F24, F12, F39) ──────────

test('a plan day already done today is shown as done, with no live button (F6)', () => {
  const base = { progressPercent: 14.3, completedDays: 1, currentDay: 2, totalDays: 7, planGoal: 'ENDURANCE',
    planDifficulty: 'BEGINNER', isCompleted: false,
    todaysWorkout: { dayNumber: 2, activityType: 'CARDIO', durationMinutes: 20, isRestDay: false } };
  const done = activePlanSheet({ ...base, completedToday: true });
  assert.match(done, /Next, day 2 \(tomorrow\)/);
  assert.match(done, /data-action="complete-plan-day" disabled>Done for today</);
  assert.doesNotMatch(done, /Today, day 2/);

  const notYet = activePlanSheet({ ...base, completedToday: false });
  assert.match(notYet, /Today, day 2/);
  assert.match(notYet, /data-action="complete-plan-day">Mark today complete</);
});

test('a declined challenge reads "Declined", not the raw enum (F40)', () => {
  const html = challengeCard({ id: 'c-1', status: 'DECLINED', type: 'WORKOUT_COUNT', title: 'X', participants: [] }, false);
  assert.match(html, />Declined</);
  assert.match(html, /Everyone invited declined\./);
  assert.doesNotMatch(html, /DECLINED/);
});

test('a creator alone in a running challenge gets Cancel; with someone still in, doesn\'t (F15)', () => {
  const card = others => challengeCard({ id: 'c-2', status: 'ACTIVE', type: 'WORKOUT_COUNT', title: 'X', youAreCreator: true,
    daysRemaining: 4, participants: [{ isYou: true, status: 'ACCEPTED', isCreator: true }, ...others] }, false);
  assert.match(card([{ isYou: false, status: 'DECLINED' }]), /data-action="cancel-challenge"/);
  assert.doesNotMatch(card([{ isYou: false, status: 'ACCEPTED' }]), /data-action="cancel-challenge"/);
});

test('a goal\'s deadline can\'t be emptied in the edit form once it has one (F24)', () => {
  const label = el('label', { for: 'edit-goal-deadline' });
  document.body.appendChild(label);
  openEditGoal('g-1', 5, '2026-12-31');
  assert.equal(document.getElementById('edit-goal-deadline').required, true);
  assert.match(label.textContent, /can be moved, not removed/);
  openEditGoal('g-2', 5, null);
  assert.equal(document.getElementById('edit-goal-deadline').required, false);
  assert.equal(label.textContent, 'Deadline (optional)');
});

test('saving the profile refills the form from what was saved, says why, and updates the initials (F12, F39)', async () => {
  document.getElementById('prof-first').value = 'Xavier';
  document.getElementById('prof-last').value = '';        // emptied: the server keeps it
  document.getElementById('prof-weight').value = '';
  document.getElementById('prof-height').value = '175';
  document.getElementById('prof-age').value = '30';
  fakeFetch(() => jsonResponse(200, { id: 'u-1', firstName: 'Xavier', lastName: 'Young', weight: 70, height: 175, age: 30 }));
  await handleUpdateProfile({ preventDefault() {} });

  assert.equal(document.getElementById('prof-last').value, 'Young');
  assert.equal(String(document.getElementById('prof-weight').value), '70');
  assert.match(document.getElementById('tmsg').textContent, /left empty keeps its saved value/);
  assert.equal(document.getElementById('sb-av').textContent, 'XY');
});
