// The boot check in main.js (F2 of the 2026-11-16 full-app run): only a refusal of the
// saved session may end it. A network failure or a server fault used to sign the person
// out and throw away tokens the server still accepted.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el, jsonResponse } from './helpers/env.mjs';

const SAVED = { fp_accessToken: 'access', fp_refreshToken: 'refresh',
                fp_user: JSON.stringify({ id: 'u-1', firstName: 'Anna', lastName: 'Berg' }) };
const { document, window } = installBrowserEnv({ storage: SAVED });
// The error toast is part of the expected outcome, so it needs its real children.
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));

await import('../js/main.js');
const { session } = await import('../js/state.js');
const { stopUnreadPolling } = await import('../js/panels/notifications.js');
after(() => stopUnreadPolling());

/** Boots with `reply` answering every backend call, and waits for the handler to settle. */
async function boot(reply) {
  for (const [k, v] of Object.entries(SAVED)) localStorage.setItem(k, v);
  session.accessToken = SAVED.fp_accessToken;
  session.refreshToken = SAVED.fp_refreshToken;
  session.currentUser = JSON.parse(SAVED.fp_user);
  session.expiredInProgress = false;
  document.getElementById('auth-screen').style.display = 'flex';
  document.getElementById('app-shell').classList.remove('show');
  globalThis.fetch = async (url, opts) => reply(String(url), opts);
  window.dispatchEvent(new Event('DOMContentLoaded'));
  await new Promise(r => setTimeout(r, 50));
  stopUnreadPolling();
}

const inApp = () => document.getElementById('app-shell').classList.contains('show');

test('the server unreachable at load: the app opens and the tokens are kept', async () => {
  await boot(() => { throw new TypeError('Failed to fetch'); });
  assert.equal(inApp(), true);
  assert.equal(localStorage.getItem('fp_accessToken'), 'access');
  assert.equal(localStorage.getItem('fp_refreshToken'), 'refresh');
  assert.equal(document.getElementById('toast').classList.contains('err'), true, 'the failure is shown');
});

test('a server fault at load: the app opens and the tokens are kept', async () => {
  await boot(() => jsonResponse(503, { error: 'Service unavailable' }));
  assert.equal(inApp(), true);
  assert.equal(localStorage.getItem('fp_refreshToken'), 'refresh');
});

test('a refusal of the saved session at load still ends it', async () => {
  await boot(() => jsonResponse(403, { error: "You don't have access to this account." }));
  assert.equal(inApp(), false);
  assert.equal(localStorage.getItem('fp_accessToken'), null);
  assert.equal(document.getElementById('auth-screen').style.display, 'flex');
});
