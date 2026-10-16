// Opening a password-reset link (js/auth.js consumeResetLink).
//
// Its own file because the token is read from location at the moment the link is
// consumed, and each test file gets a fresh page.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, jsonResponse, FakeEvent } from './helpers/env.mjs';

const { document } = installBrowserEnv({ url: 'http://localhost:5500/?reset=live-token-123#overview' });
const auth = await import('../js/auth.js');

const calls = [];
globalThis.fetch = async (url, opts = {}) => { calls.push({ url, opts }); return jsonResponse(200, { message: 'ok' }); };

test('the token leaves the address bar as soon as the link is opened, and is still used for the reset', async () => {
  assert.equal(auth.consumeResetLink(), true);

  // Found by the live QA pass: the token stayed in the URL (and browser history)
  // until a reset succeeded, so an abandoned link left it there until it expired.
  assert.equal(new URLSearchParams(location.search).get('reset'), null);
  assert.equal(location.hash, '#overview', 'the rest of the URL is left alone');

  document.getElementById('reset-password').value = 'a-new-password';
  document.getElementById('reset-password2').value = 'a-new-password';
  await auth.handleResetPassword(new FakeEvent('submit'));

  const sent = calls.find(c => String(c.url).endsWith('/api/auth/reset-password'));
  assert.ok(sent, 'reset request was sent');
  assert.equal(JSON.parse(sent.opts.body).token, 'live-token-123');
});
