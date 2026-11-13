// The record rows on the Personal Bests page (js/panels/bests.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1' }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { activityBestRow, movementBestRow, typeLabel } = await import('../js/panels/bests.js');

const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u;
const cells = (html) => [...html.matchAll(/<span><span class="k">([^<]*)<\/span>([^<]*)<span class="u">([^<]*)<\/span><\/span>|<span><\/span>/g)]
  .map(m => m[1] === undefined ? null : [m[1].trim(), m[2], m[3]]);

test('an activity type becomes a row: its name in words, then longest, most calories and furthest', () => {
  const html = activityBestRow({ activityType: 'RUNNING', bestDuration: 48, bestCalories: 540, bestDistanceKm: 10.2 });
  assert.ok(html.includes('class="ai logrow pb pb-act"'));
  assert.match(html, /class="an">Running</);
  assert.deepEqual(cells(html), [['Longest', '48', 'min'], ['Most calories', '540', 'cal'], ['Furthest', '10.2', 'km']]);
});

test('a type with no distance leaves that cell empty, not a dash or a zero', () => {
  const html = activityBestRow({ activityType: 'YOGA', bestDuration: 30, bestCalories: 90, bestDistanceKm: null });
  assert.deepEqual(cells(html), [['Longest', '30', 'min'], ['Most calories', '90', 'cal'], null]);
  assert.ok(!html.includes('—') && !html.includes('undefined') && !html.includes('null'));
});

test('a type with a zero record still shows it (zero is a value, absent is not)', () => {
  const html = activityBestRow({ activityType: 'OTHER', bestDuration: 20, bestCalories: 0, bestDistanceKm: 0 });
  assert.deepEqual(cells(html), [['Longest', '20', 'min'], ['Most calories', '0', 'cal'], ['Furthest', '0', 'km']]);
});

test('type names read as words, underscores and all', () => {
  assert.equal(typeLabel('WEIGHT_TRAINING'), 'Weight training');
  assert.equal(typeLabel('HIIT'), 'Hiit');
  assert.equal(typeLabel(undefined), '');
  assert.match(activityBestRow({ activityType: undefined, bestDuration: 1 }), /class="an">Unspecified</);
});

test('a lift shows its heaviest set with the reps, its 1RM and its volume, and no rep record', () => {
  const html = movementBestRow({ displayName: 'Bench Press', bestWeightKg: 65, bestWeightReps: 5,
    bestEstimatedOneRepMax: 75.83, bestVolume: 1560, bestBodyweightReps: null });
  assert.ok(html.includes('class="ai logrow pb pb-mov"'));
  assert.match(html, /class="an">Bench Press</);
  assert.deepEqual(cells(html), [['Heaviest', '65', 'kg x 5'], ['Est. 1RM', '75.83', 'kg'], ['Best volume', '1560', 'kg'], null]);
});

test('a bodyweight movement has only its rep record, in a row of otherwise empty cells', () => {
  const html = movementBestRow({ displayName: 'Pull-up', bestWeightKg: null, bestWeightReps: null,
    bestEstimatedOneRepMax: null, bestVolume: null, bestBodyweightReps: 12 });
  assert.deepEqual(cells(html), [null, null, null, ['Most reps', '12', 'reps']]);
});

test('a heaviest set with no rep count shows the load alone', () => {
  const html = movementBestRow({ displayName: 'Deadlift', bestWeightKg: 100, bestWeightReps: null });
  assert.deepEqual(cells(html)[0], ['Heaviest', '100', 'kg']);
});

test('the name falls back to the matching key, and then to a label', () => {
  assert.match(movementBestRow({ normalisedName: 'bench press', bestWeightKg: 1 }), /class="an">bench press</);
  assert.match(movementBestRow({ bestWeightKg: 1 }), /class="an">Unnamed</);
});

test('rows carry no emoji, dash or inline style, and names are escaped', () => {
  const rows = [
    activityBestRow({ activityType: 'RUNNING', bestDuration: 48, bestCalories: 540, bestDistanceKm: 10.2 }),
    movementBestRow({ displayName: '<img src=x onerror=alert(1)>', bestWeightKg: 65, bestWeightReps: 5, bestEstimatedOneRepMax: 76, bestVolume: 1500 }),
  ];
  for (const html of rows) {
    assert.ok(!EMOJI.test(html), html);
    assert.ok(!/[—–]/.test(html) && !html.includes('style=') && !html.includes('pb-card'));
  }
  assert.ok(rows[1].includes('&lt;img src=x onerror=alert(1)&gt;') && !rows[1].includes('<img'));
});
