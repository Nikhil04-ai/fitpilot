// api() and the session around it: bearer header, silent refresh, the two ways a dead
// session is detected, and what a write does to the panel cache (AGENT.md §8).
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, jsonResponse } from './helpers/env.mjs';

const USER = { id: 'u-1', firstName: 'Mona', lastName: 'Dule', email: 'm@x.io' };
installBrowserEnv({
  storage: { fp_accessToken: 'old-access', fp_refreshToken: 'old-refresh', fp_user: JSON.stringify(USER) }
});

const { session } = await import('../js/state.js');
const restoredAtImport = { ...session, currentUser: { ...session.currentUser } };
const { api, storeTokens, unreachableMessage, forceSessionExpired } = await import('../js/api.js');
const { API_BASE } = await import('../js/config.js');

/** Scripted fetch: each call takes the next response (or throws it if it's an Error). */
let calls;
function scriptFetch(...responses) {
  calls = [];
  globalThis.fetch = async (url, opts = {}) => {
    calls.push({ url, opts });
    const next = responses.shift();
    if (!next) throw new Error('unexpected extra fetch to ' + url);
    if (next instanceof Error) throw next;
    return next;
  };
}

function signIn() {
  session.accessToken = 'access';
  session.refreshToken = 'refresh';
  session.currentUser = { ...USER };
  session.expiredInProgress = false;
  localStorage.setItem('fp_accessToken', 'access');
  localStorage.setItem('fp_refreshToken', 'refresh');
  localStorage.setItem('fp_user', JSON.stringify(USER));
  document.getElementById('auth-error').classList.remove('show');
  document.getElementById('auth-screen').style.display = 'none';
  document.getElementById('app-shell').classList.add('show');
}

beforeEach(signIn);

test('state.js restores the saved session from localStorage', () => {
  assert.equal(restoredAtImport.accessToken, 'old-access');
  assert.equal(restoredAtImport.refreshToken, 'old-refresh');
  assert.deepEqual(restoredAtImport.currentUser, USER);
  assert.equal(restoredAtImport.expiredInProgress, false);
});

test('API_BASE is the local backend when served from localhost', () => {
  assert.equal(API_BASE, 'http://localhost:8080');
});

test('sends the bearer token and JSON content type, and parses the body', async () => {
  scriptFetch(jsonResponse(200, { hello: 'world' }));
  const body = await api('/api/thing');
  assert.deepEqual(body, { hello: 'world' });
  assert.equal(calls[0].url, 'http://localhost:8080/api/thing');
  assert.equal(calls[0].opts.headers.Authorization, 'Bearer access');
  assert.equal(calls[0].opts.headers['Content-Type'], 'application/json');
});

test('204 No Content resolves to null', async () => {
  scriptFetch(jsonResponse(204));
  assert.equal(await api('/api/digest'), null);
});

test('an error response throws the backend\'s message', async () => {
  scriptFetch(jsonResponse(400, { error: 'Duration must be positive' }));
  await assert.rejects(api('/api/activities', { method: 'POST' }), /Duration must be positive/);
});

test('the error carries the HTTP status, so a caller can tell a refusal from a fault', async () => {
  scriptFetch(jsonResponse(400, { error: 'Nothing to summarise' }));
  await assert.rejects(api('/api/digest/generate', { method: 'POST' }), { status: 400 });
  scriptFetch(jsonResponse(500, { error: 'Something went wrong' }));
  await assert.rejects(api('/api/digest/generate', { method: 'POST' }), { status: 500 });
});

test('an error with no JSON body still gives a readable message', async () => {
  scriptFetch(jsonResponse(502));
  await assert.rejects(api('/api/x'), /Request failed \(502\)/);
});

test('a network failure becomes the "cannot reach the server" message', async () => {
  scriptFetch(new TypeError('Failed to fetch'));
  await assert.rejects(api('/api/x'), { message: unreachableMessage() });
  assert.match(unreachableMessage(), /localhost:8080/);
});

test('a 401 refreshes the token silently and retries the call once', async () => {
  scriptFetch(
    jsonResponse(401, { error: 'expired' }),
    jsonResponse(200, { accessToken: 'new-access', refreshToken: 'new-refresh' }),
    jsonResponse(200, { ok: true }));

  assert.deepEqual(await api('/api/stats'), { ok: true });
  assert.equal(calls[1].url, 'http://localhost:8080/api/auth/refresh');
  assert.deepEqual(JSON.parse(calls[1].opts.body), { refreshToken: 'refresh' });
  assert.equal(calls[2].opts.headers.Authorization, 'Bearer new-access');
  assert.equal(localStorage.getItem('fp_accessToken'), 'new-access');
  assert.equal(localStorage.getItem('fp_refreshToken'), 'new-refresh');
});

test('a 401 that survives the refresh ends the session and shows why', async () => {
  scriptFetch(jsonResponse(401, { error: 'expired' }), jsonResponse(401, { error: 'revoked' }));
  await assert.rejects(api('/api/stats'));

  assert.equal(session.accessToken, null);
  assert.equal(session.currentUser, null);
  assert.equal(localStorage.getItem('fp_accessToken'), null);
  assert.equal(localStorage.getItem('fp_user'), null);
  assert.equal(document.getElementById('auth-screen').style.display, 'flex');
  assert.ok(!document.getElementById('app-shell').classList.contains('show'));
  assert.match(document.getElementById('auth-error').textContent, /session has expired/);
  assert.equal(session.expiredInProgress, true, 'suppresses the other in-flight toasts');
});

test('a 409 "account that no longer exists" self-heals to the login screen', async () => {
  scriptFetch(jsonResponse(409, { error: 'This session belongs to an account that no longer exists.' }));
  await assert.rejects(api('/api/activities', { method: 'POST' }), /no longer exists/);
  assert.equal(session.currentUser, null);
  assert.equal(document.getElementById('auth-screen').style.display, 'flex');
});

test('a 409 about some other record does NOT log the person out', async () => {
  scriptFetch(jsonResponse(409, { error: 'That challenge no longer exists.' }));
  await assert.rejects(api('/api/challenges/c-1/respond', { method: 'POST' }));
  assert.equal(session.currentUser.id, 'u-1');
  assert.equal(localStorage.getItem('fp_accessToken'), 'access');
});

test('raw: true hands back the Response for a download, after the same auth handling', async () => {
  const res = jsonResponse(200, 'blob-bytes');
  scriptFetch(res);
  assert.equal(await api('/api/export/zip', { raw: true }), res);
  assert.equal(calls[0].opts.headers.Authorization, 'Bearer access');
});

test('raw: true still recognises a dead session on failure (PLAN P4-1)', async () => {
  scriptFetch(jsonResponse(409, { error: 'account that no longer exists' }));
  await assert.rejects(api('/api/export/zip', { raw: true }));
  assert.equal(session.currentUser, null);
});

test('storeTokens updates memory and storage together', () => {
  storeTokens('a2', 'r2');
  assert.equal(session.accessToken, 'a2');
  assert.equal(localStorage.getItem('fp_refreshToken'), 'r2');
});

test('forceSessionExpired re-enables toasts after its grace period', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  forceSessionExpired('bye');
  assert.equal(session.expiredInProgress, true);
  t.mock.timers.tick(1500);
  assert.equal(session.expiredInProgress, false);
});

// ── Concurrent 401s (PLAN O-5) ──────────────────────────────────────────────
// A panel fires several calls at once, so an expired token is a burst of 401s. The real
// backend honours a refresh token once; a second use is a 401. These tests run against a
// fake that behaves the same way, because a fake that accepts any refresh would pass while
// the bug stayed.

/** A backend that treats tokens like the real one: a refresh token works once. */
function tokenServer({ refreshWorks = true, holdUntilRefreshed = [] } = {}) {
  const s = { refreshCalls: 0, n: 1, validAccess: 'access-1', validRefresh: refreshWorks ? 'refresh' : 'never-matches' };
  globalThis.fetch = async (url, opts = {}) => {
    const path = String(url).replace('http://localhost:8080', '');
    await new Promise(r => setImmediate(r));                       // let concurrent calls interleave
    if (path === '/api/auth/refresh') {
      s.refreshCalls++;
      if (JSON.parse(opts.body).refreshToken !== s.validRefresh) {
        return jsonResponse(401, { error: 'Refresh token has been revoked. Please log in again.' });
      }
      s.n++; s.validAccess = 'access-' + s.n; s.validRefresh = 'refresh-' + s.n;
      return jsonResponse(200, { accessToken: s.validAccess, refreshToken: s.validRefresh });
    }
    // A call that was sent with the old token but whose answer only arrives after another
    // call has already refreshed: the case that must retry without refreshing again.
    // Bounded, so a regression fails this test instead of leaving a loop that keeps node alive.
    if (holdUntilRefreshed.includes(path)) {
      for (let i = 0; i < 1000 && s.n < 2; i++) await new Promise(r => setImmediate(r));
    }
    return opts.headers.Authorization === 'Bearer ' + s.validAccess
      ? jsonResponse(200, { path }) : jsonResponse(401, { error: 'expired' });
  };
  return s;
}

test('several calls that all get a 401 share one refresh, and all succeed', async () => {
  const server = tokenServer();   // the session starts on 'access', which this server no longer accepts
  const results = await Promise.all([api('/api/a'), api('/api/b'), api('/api/c')]);
  assert.deepEqual(results.map(r => r.path), ['/api/a', '/api/b', '/api/c']);
  assert.equal(server.refreshCalls, 1, 'one refresh, not one per call');
  assert.equal(session.accessToken, 'access-2');
  assert.equal(session.expiredInProgress, false, 'nobody was signed out');
});

test('a call sent with the old token that is refused after another call refreshed retries without a second refresh', async () => {
  const server = tokenServer({ holdUntilRefreshed: ['/api/late'] });
  const results = await Promise.all([api('/api/a'), api('/api/late')]);
  assert.deepEqual(results.map(r => r.path), ['/api/a', '/api/late']);
  assert.equal(server.refreshCalls, 1);
  assert.equal(session.expiredInProgress, false);
});

test('when the refresh is refused, the burst makes one attempt and ends the session once with a reason', async () => {
  const server = tokenServer({ refreshWorks: false });
  const results = await Promise.allSettled([api('/api/a'), api('/api/b'), api('/api/c')]);
  assert.ok(results.every(r => r.status === 'rejected'));
  assert.equal(server.refreshCalls, 1, 'a refused refresh is not retried per call');
  assert.equal(session.accessToken, null);
  assert.equal(session.expiredInProgress, true);
  assert.match(document.getElementById('auth-error').textContent, /session has expired/);
});

test('the guard is cleared once a refresh settles, so the next expiry refreshes again', async () => {
  const server = tokenServer();
  await Promise.all([api('/api/a'), api('/api/b')]);
  assert.equal(server.refreshCalls, 1);

  session.accessToken = 'expired-again';              // the token goes bad a second time
  const again = await api('/api/c');
  assert.equal(again.path, '/api/c');
  assert.equal(server.refreshCalls, 2, 'a fresh refresh, using the rotated refresh token');
  assert.equal(session.accessToken, 'access-3');
});
