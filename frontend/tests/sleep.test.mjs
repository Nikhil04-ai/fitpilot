// The Health panel's Log Sleep form (js/panels/health.js). Before it, sleep could only
// be written through the API (PLAN P5-11).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, jsonResponse, FakeEvent, el } from './helpers/env.mjs';

const USER = { id: 'u-1', email: 'a@b.c' };
const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify(USER) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const health = await import('../js/panels/health.js');
const { yesterday } = await import('../js/util.js');

const calls = [];
globalThis.fetch = async (url, opts = {}) => {
  calls.push({ url: String(url), opts });
  return jsonResponse(200, String(url).endsWith('/analysis') ? { history: [] } : {});
};

function fill(values) {
  for (const [id, v] of Object.entries(values)) document.getElementById(id).value = v;
}

test('a bedtime before midnight is the evening of the night, waking is the next morning', () => {
  assert.deepEqual(health.sleepTimes('2026-09-24', '23:10', '06:40'),
    { bedtime: '2026-09-24T23:10:00', wakeTime: '2026-09-25T06:40:00' });
});

test('a bedtime after midnight is the next calendar day, and so is waking', () => {
  assert.deepEqual(health.sleepTimes('2026-09-24', '00:30', '07:00'),
    { bedtime: '2026-09-25T00:30:00', wakeTime: '2026-09-25T07:00:00' });
});

test('the night crosses a month end and a year end', () => {
  assert.equal(health.sleepTimes('2026-12-31', '22:00', '06:00').wakeTime, '2027-01-01T06:00:00');
});

test('either time can be left out', () => {
  assert.deepEqual(health.sleepTimes('2026-09-24', '', ''), { bedtime: null, wakeTime: null });
  assert.deepEqual(health.sleepTimes('2026-09-24', '', '06:40'), { bedtime: null, wakeTime: '2026-09-25T06:40:00' });
  assert.deepEqual(health.sleepTimes('2026-09-24', '23:10', ''), { bedtime: '2026-09-24T23:10:00', wakeTime: null });
});

test('hours are wall-clock hours to one decimal place', () => {
  assert.equal(health.hoursBetween('2026-09-24T23:10:00', '2026-09-25T06:40:00'), 7.5);
  assert.equal(health.hoursBetween('2026-09-24T23:07:00', '2026-09-25T06:40:00'), 7.6);
});

test('filling in both times fills in the hours; one time alone leaves them', () => {
  fill({ 'sleep-date': '2026-09-24', 'sleep-bedtime': '23:00', 'sleep-waketime': '', 'sleep-hours': '' });
  health.fillSleepHours();
  assert.equal(document.getElementById('sleep-hours').value, '');

  fill({ 'sleep-waketime': '06:30' });
  health.fillSleepHours();
  assert.equal(document.getElementById('sleep-hours').value, '7.5');
});

test('opening the form defaults the night to yesterday and stops at today', () => {
  fill({ 'sleep-date': '' });
  health.openLogSleep();
  const date = document.getElementById('sleep-date');
  assert.equal(date.value, yesterday());
  assert.ok(date.max > date.value);
});

test('submitting posts the night with full date-times, then refreshes the card', async () => {
  let reset = false;
  document.getElementById('form-log-sleep').reset = () => { reset = true; };
  fill({
    'sleep-date': '2026-09-24', 'sleep-bedtime': '23:10', 'sleep-waketime': '06:40',
    'sleep-hours': '7.5', 'sleep-quality': '4', 'sleep-notes': '  Woke once around 3am  '
  });
  calls.length = 0;
  const e = new FakeEvent('submit');
  await health.handleLogSleep(e);

  assert.ok(e.defaultPrevented);
  const post = calls.find(c => c.opts.method === 'POST');
  assert.equal(post.url, 'http://localhost:8080/api/health/sleep/u-1');
  assert.deepEqual(JSON.parse(post.opts.body), {
    sleepDate: '2026-09-24', hoursSlept: 7.5, qualityRating: 4,
    bedtime: '2026-09-24T23:10:00', wakeTime: '2026-09-25T06:40:00', notes: 'Woke once around 3am'
  });
  assert.ok(reset, 'the form is cleared for the next night');
  assert.ok(calls.some(c => c.url.endsWith('/api/health/sleep/u-1/analysis')), 'the card reloads');
  assert.equal(document.getElementById('log-sleep-btn').disabled, false);
});

test('without times or notes, those fields are sent as null', async () => {
  document.getElementById('form-log-sleep').reset = () => {};
  fill({ 'sleep-date': '2026-09-24', 'sleep-bedtime': '', 'sleep-waketime': '',
         'sleep-hours': '6', 'sleep-quality': '3', 'sleep-notes': '' });
  calls.length = 0;
  await health.handleLogSleep(new FakeEvent('submit'));

  const body = JSON.parse(calls.find(c => c.opts.method === 'POST').opts.body);
  assert.equal(body.bedtime, null);
  assert.equal(body.wakeTime, null);
  assert.equal(body.notes, null);
});
