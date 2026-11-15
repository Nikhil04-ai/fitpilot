// A workout with no duration (js/panels/activities.js, PLAN O-4): saved as described,
// never given a guessed duration, and the person is told what that costs.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el, jsonResponse } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1', currentStreak: 0 }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { noDurationNote, activityRow, handleLogNatural } = await import('../js/panels/activities.js');

const toastText = () => document.getElementById('tmsg').textContent;

/** Drives the natural-language form with the server answering `workout`. */
async function logNatural(workout) {
  document.getElementById('tmsg').textContent = '';
  document.getElementById('form-log-natural').reset = () => {};   // the helper DOM has no form.reset()
  document.getElementById('natural-description').value = 'ran 5k';
  globalThis.fetch = async () => jsonResponse(200, workout);
  await handleLogNatural({ preventDefault() {} });
}

test('a workout with no duration is explained, and one with a duration is not', () => {
  assert.match(noDurationNote({ duration: null }), /No duration.*duration or calorie goals.*Edit/);
  assert.match(noDurationNote({}), /No duration/);
  assert.equal(noDurationNote({ duration: 30 }), null);
  assert.equal(noDurationNote(null), null);
});

test('the row says why its duration cell is empty, in words and without a dash', () => {
  const bare = activityRow({ id: 'a-1', type: 'RUNNING', startTime: '2026-11-15T07:00:00', distanceKm: 5, duration: null });
  assert.match(bare, /class="ai-chip"[^>]*>No duration, not counted in goals</);
  assert.ok(!bare.includes('—') && !bare.includes('undefined') && !bare.includes('null'));

  const timed = activityRow({ id: 'a-2', type: 'RUNNING', startTime: '2026-11-15T07:00:00', distanceKm: 5, duration: 30, caloriesBurned: 300 });
  assert.ok(!timed.includes('No duration'));
});

test('logging "ran 5k" with no duration says it will not count toward goals', async () => {
  await logNatural({ id: 'a-1', type: 'RUNNING', distanceKm: 5, duration: null, caloriesBurned: null, currentStreak: 1 });
  assert.match(toastText(), /^Workout logged\. No duration, so it won.t count toward duration or calorie goals\. Use Edit to add one\.$/);
});

test('a workout with a duration gets the same toast as before', async () => {
  await logNatural({ id: 'a-2', type: 'RUNNING', distanceKm: 5, duration: 30, caloriesBurned: 300, currentStreak: 1 });
  assert.equal(toastText(), 'Workout logged, 300 cal burned');
});

test('a personal best with no duration keeps its message and adds the note with one full stop', async () => {
  await logNatural({ id: 'a-3', type: 'RUNNING', distanceKm: 10, duration: null, caloriesBurned: null,
    isPersonalBest: true, personalBestMessage: 'Furthest run yet.', currentStreak: 1 });
  assert.match(toastText(), /^New personal best\. Furthest run yet\. No duration/);
  assert.ok(!toastText().includes('..'));
});
