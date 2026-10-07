// js/auth.js: login, register and logout as the session sees them.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, jsonResponse, FakeEvent } from './helpers/env.mjs';

const { document } = installBrowserEnv();
const { session } = await import('../js/state.js');
const auth = await import('../js/auth.js');

let calls;
function scriptFetch(...responses) {
  calls = [];
  globalThis.fetch = async (url, opts = {}) => {
    calls.push({ url, opts });
    const next = responses.shift();
    if (next instanceof Error) throw next;
    return next || jsonResponse(200, { content: [], page: { totalPages: 1 } });
  };
}

const USER = { id: 'u-1', firstName: 'Mona', lastName: '', email: 'm@x.io', currentStreak: 4 };

beforeEach(() => {
  session.accessToken = session.refreshToken = session.currentUser = null;
  localStorage.clear();
  document.getElementById('auth-error').classList.remove('show');
});

function fillLogin(email, password) {
  document.getElementById('login-email').value = email;
  document.getElementById('login-password').value = password;
}

test('login stores the token pair and user, then enters the app', async () => {
  scriptFetch(jsonResponse(200, { accessToken: 'A', refreshToken: 'R', user: USER }));
  fillLogin('  m@x.io ', 'pw');
  const e = new FakeEvent('submit');
  await auth.handleLogin(e);

  assert.equal(e.defaultPrevented, true);
  assert.deepEqual(JSON.parse(calls[0].opts.body), { email: 'm@x.io', password: 'pw' }, 'email trimmed');
  assert.equal(session.accessToken, 'A');
  assert.equal(localStorage.getItem('fp_refreshToken'), 'R');
  assert.deepEqual(JSON.parse(localStorage.getItem('fp_user')), USER);
  assert.equal(document.getElementById('auth-screen').style.display, 'none');
  assert.ok(document.getElementById('app-shell').classList.contains('show'));
  assert.equal(document.getElementById('sb-av').textContent, 'M', 'no "MUNDEFINED" with no last name');
  assert.equal(document.getElementById('topbar-streak').textContent, 4);
  assert.equal(document.getElementById('login-btn').disabled, false, 'button re-enabled');
  auth.doLogout();
});

test('a wrong password is shown on the auth screen and stores nothing', async () => {
  scriptFetch(jsonResponse(401, { error: 'Invalid email or password' }));
  fillLogin('m@x.io', 'nope');
  await auth.handleLogin(new FakeEvent('submit'));
  assert.equal(document.getElementById('auth-error').textContent, 'Invalid email or password');
  assert.ok(document.getElementById('auth-error').classList.contains('show'));
  assert.equal(session.accessToken, null);
  assert.equal(localStorage.getItem('fp_accessToken'), null);
});

test('login against an unreachable server says so instead of a JSON parse error', async () => {
  scriptFetch(new TypeError('Failed to fetch'));
  fillLogin('m@x.io', 'pw');
  await auth.handleLogin(new FakeEvent('submit'));
  assert.match(document.getElementById('auth-error').textContent, /Cannot reach the server/);
});

test('a proxy\'s HTML error page (no JSON) still produces a readable message', async () => {
  scriptFetch(jsonResponse(503));
  fillLogin('m@x.io', 'pw');
  await auth.handleLogin(new FakeEvent('submit'));
  assert.equal(document.getElementById('auth-error').textContent, 'Login failed (503)');
});

test('a registration validation map is joined into one readable message', async () => {
  scriptFetch(jsonResponse(400, { email: 'must be a well-formed email address', password: 'too short' }));
  ['reg-first', 'reg-last', 'reg-email', 'reg-password', 'reg-weight', 'reg-height', 'reg-age']
    .forEach(id => { document.getElementById(id).value = ''; });
  await auth.handleRegister(new FakeEvent('submit'));
  assert.equal(document.getElementById('auth-error').textContent,
    'must be a well-formed email address, too short');
});

test('logout revokes with the refresh token (never a bare userId) and clears everything', () => {
  scriptFetch(jsonResponse(204));
  session.accessToken = 'A'; session.refreshToken = 'R'; session.currentUser = USER;
  localStorage.setItem('fp_accessToken', 'A');
  localStorage.setItem('fp_user', JSON.stringify(USER));

  auth.doLogout();

  assert.equal(calls[0].url, 'http://localhost:8080/api/auth/logout');
  assert.deepEqual(JSON.parse(calls[0].opts.body), { refreshToken: 'R' });
  assert.equal(calls[0].opts.headers.Authorization, 'Bearer A');
  assert.equal(session.currentUser, null);
  assert.equal(localStorage.getItem('fp_accessToken'), null);
  assert.equal(localStorage.getItem('fp_user'), null);
  assert.equal(document.getElementById('auth-screen').style.display, 'flex');
});

test('switchAuthTab shows one form and marks only a real tab as selected', () => {
  auth.switchAuthTab('forgot');
  assert.equal(document.getElementById('form-forgot').style.display, 'block');
  assert.equal(document.getElementById('form-login').style.display, 'none');
  assert.equal(document.getElementById('tab-login').getAttribute('aria-selected'), 'false');
  assert.equal(document.getElementById('tab-register').getAttribute('aria-selected'), 'false');
  auth.switchAuthTab('register');
  assert.equal(document.getElementById('tab-register').getAttribute('aria-selected'), 'true');
});

test('reset-password refuses mismatched passwords before calling the server', async () => {
  scriptFetch();
  document.getElementById('reset-password').value = 'aaaaaaaa';
  document.getElementById('reset-password2').value = 'bbbbbbbb';
  await auth.handleResetPassword(new FakeEvent('submit'));
  assert.equal(calls.length, 0);
  assert.match(document.getElementById('auth-error').textContent, /do not match/);
});
