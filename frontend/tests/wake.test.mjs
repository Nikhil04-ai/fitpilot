// The "waking up the server" notice (js/wake.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, jsonResponse } from './helpers/env.mjs';

// A deployed origin, so the default tracker is enabled (IS_LOCAL is false).
installBrowserEnv({ url: 'https://someone.github.io/fitpilot/?api=https://api.example.com' });
const { createWakeTracker, wakeAwareFetch, NOTICE_DELAY_MS, SLEEP_AFTER_MS } = await import('../js/wake.js');

/** A tracker on a hand-cranked clock, recording show/hide calls. */
function harness({ enabled = true } = {}) {
  let clock = 1_000_000;
  const timers = [];
  const log = [];
  const tracker = createWakeTracker({
    enabled,
    now: () => clock,
    setTimer: (fn, ms) => { const t = { fn, at: clock + ms, live: true }; timers.push(t); return t; },
    clearTimer: t => { t.live = false; },
    show: () => log.push('show'),
    hide: () => log.push('hide')
  });
  const advance = ms => {
    clock += ms;
    timers.filter(t => t.live && t.at <= clock).forEach(t => { t.live = false; t.fn(); });
  };
  return { tracker, advance, log };
}

/** A request that settles when told to. */
function pending() {
  let resolve, reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

test('a slow first request on a fresh page shows the notice, and the response hides it', async () => {
  const { tracker, advance, log } = harness();
  const req = pending();
  const tracked = tracker.track(req.promise);

  advance(NOTICE_DELAY_MS - 1);
  assert.deepEqual(log, [], 'not before the delay');
  advance(1);
  assert.deepEqual(log, ['show']);
  assert.equal(tracker.isShown, true);

  req.resolve('response');
  assert.equal(await tracked, 'response', 'resolves exactly as the request does');
  assert.deepEqual(log, ['show', 'hide']);
});

test('a fast request never shows it', async () => {
  const { tracker, advance, log } = harness();
  await tracker.track(Promise.resolve('ok'));
  advance(NOTICE_DELAY_MS * 2);
  assert.deepEqual(log, []);
});

test('a slow request to a server heard from recently is just slow (e.g. a Gemini call)', async () => {
  const { tracker, advance, log } = harness();
  await tracker.track(Promise.resolve('warm-up'));
  advance(60_000);
  const req = pending();
  const tracked = tracker.track(req.promise);
  advance(20_000);
  assert.deepEqual(log, [], 'awake a minute ago, so not a cold start');
  req.resolve('done');
  await tracked;
});

test('after the idle period the server may be asleep again, so the notice re-arms', async () => {
  const { tracker, advance, log } = harness();
  await tracker.track(Promise.resolve('warm-up'));
  advance(SLEEP_AFTER_MS + 1);
  const req = pending();
  const tracked = tracker.track(req.promise);
  advance(NOTICE_DELAY_MS);
  assert.deepEqual(log, ['show']);
  req.resolve('ok');
  await tracked;
  assert.deepEqual(log, ['show', 'hide']);
});

test('parallel requests share one notice, which goes on the first response', async () => {
  const { tracker, advance, log } = harness();
  const a = pending(), b = pending();
  const ta = tracker.track(a.promise), tb = tracker.track(b.promise);
  advance(NOTICE_DELAY_MS);
  assert.deepEqual(log, ['show'], 'one notice, not two');
  a.resolve(1);
  await ta;
  assert.deepEqual(log, ['show', 'hide'], 'any response means it is awake');
  b.resolve(2);
  await tb;
  assert.deepEqual(log, ['show', 'hide']);
});

test('a network failure rejects as before and hides the notice once nothing is pending', async () => {
  const { tracker, advance, log } = harness();
  const a = pending(), b = pending();
  const ta = tracker.track(a.promise), tb = tracker.track(b.promise);
  advance(NOTICE_DELAY_MS);
  a.reject(new TypeError('Failed to fetch'));
  await assert.rejects(ta, /Failed to fetch/);
  assert.deepEqual(log, ['show'], 'still waiting on the other request');
  b.reject(new TypeError('Failed to fetch'));
  await assert.rejects(tb);
  assert.deepEqual(log, ['show', 'hide']);
});

test('a network failure does not count as hearing from the server', async () => {
  const { tracker, advance, log } = harness();
  await assert.rejects(tracker.track(Promise.reject(new TypeError('offline'))));
  const req = pending();
  const tracked = tracker.track(req.promise);
  advance(NOTICE_DELAY_MS);
  assert.deepEqual(log, ['show']);
  req.resolve('ok');
  await tracked;
});

test('disabled (a local backend never sleeps): never shows', async () => {
  const { tracker, advance, log } = harness({ enabled: false });
  const req = pending();
  const tracked = tracker.track(req.promise);
  advance(60_000);
  assert.deepEqual(log, []);
  req.resolve('ok');
  await tracked;
});

test('wakeAwareFetch drives the #wake-notice element on a deployed page', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const req = pending();
  globalThis.fetch = () => req.promise;
  const notice = document.getElementById('wake-notice');

  const tracked = wakeAwareFetch('https://api.example.com/api/auth/login', { method: 'POST' });
  t.mock.timers.tick(NOTICE_DELAY_MS);
  assert.ok(notice.classList.contains('show'));

  req.resolve(jsonResponse(200, {}));
  await tracked;
  assert.ok(!notice.classList.contains('show'));
});
