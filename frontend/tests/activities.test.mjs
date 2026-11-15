// A workout with no duration (js/panels/activities.js, PLAN O-4): saved as described,
// never given a guessed duration, and the person is told what that costs. Also a duration
// with no calories, which only a missing profile weight can cause (PLAN O-7).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el, jsonResponse } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1', currentStreak: 0 }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { noDurationNote, noCaloriesNote, activityRow, handleLogNatural } = await import('../js/panels/activities.js');

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

// PLAN O-7: a duration with no calories means the profile has no weight to work them out from.

test('a duration with no calories is explained by the missing weight, and nothing else is', () => {
  assert.match(noCaloriesNote({ duration: 30, caloriesBurned: null }), /profile has no weight.*Profile.*Edit/);
  assert.equal(noCaloriesNote({ duration: 30, caloriesBurned: 300 }), null);
  assert.equal(noCaloriesNote({ duration: 30, caloriesBurned: 0 }), null, 'zero is a value, absent is not');
  assert.equal(noCaloriesNote({ duration: null, caloriesBurned: null }), null, 'no duration is the other note');
  assert.equal(noCaloriesNote(null), null);
});

test('the row names the missing weight, and only when that is the reason', () => {
  const run = { id: 'a-1', type: 'RUNNING', startTime: '2026-11-15T07:00:00', distanceKm: 5 };
  const noWeight = activityRow({ ...run, duration: 30, caloriesBurned: null });
  assert.match(noWeight, /class="ai-chip"[^>]*>No calories, needs your weight in Profile</);
  assert.ok(!noWeight.includes('No duration'));

  assert.ok(!activityRow({ ...run, duration: 30, caloriesBurned: 343 }).includes('No calories'));

  const bare = activityRow({ ...run, duration: null, caloriesBurned: null });
  assert.ok(bare.includes('No duration') && !bare.includes('No calories'), 'one explanation, not two');
});

test('saving a workout with a duration but no weight on the profile says so', async () => {
  await logNatural({ id: 'a-4', type: 'RUNNING', distanceKm: 5, duration: 30, caloriesBurned: null, currentStreak: 1 });
  assert.match(toastText(), /^Workout logged\. No calories were worked out, because your profile has no weight\. Add it in Profile/);
});

test('with neither a duration nor calories, the toast gives the duration as the reason', async () => {
  await logNatural({ id: 'a-5', type: 'RUNNING', distanceKm: 5, duration: null, caloriesBurned: null, currentStreak: 1 });
  assert.match(toastText(), /No duration/);
  assert.ok(!toastText().includes('No calories'));
});
