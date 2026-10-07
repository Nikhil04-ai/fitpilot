// state.js runs at import, before anything else in the app. If it throws, every module
// that imports it fails to load and the login form's buttons silently do nothing.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv } from './helpers/env.mjs';

installBrowserEnv({
  storage: { fp_accessToken: 'a', fp_refreshToken: 'r', fp_user: '{"id":"u-1",' }   // truncated JSON
});

test('a corrupt saved user is treated as signed out instead of crashing the app', async () => {
  const { session } = await import('../js/state.js');
  assert.equal(session.currentUser, null);
  // main.js boots only when both a token and a user are present, so this lands on the
  // login screen like any other missing session.
  assert.equal(session.expiredInProgress, false);
});
