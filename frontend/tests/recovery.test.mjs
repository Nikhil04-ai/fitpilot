// The muscle group row, its state and the summary on the Muscle Recovery page (js/panels/recovery.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1' }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { recoveryState, recoveryRows, recoveryRow, recoverySummary } = await import('../js/panels/recovery.js');

const NOW = new Date('2026-11-13T12:00:00').getTime();
// The API sends a LocalDateTime with no zone, which the page reads as local time, so
// build the string in local time too (toISOString would shift it by the UTC offset).
const hoursAgo = (h) => {
  const d = new Date(NOW - h * 3600000), p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
};
const group = (over) => ({ id: 'm1', muscleGroup: 'QUADRICEPS', lastWorkedAt: hoursAgo(10), recommendedRestHours: 48, ...over });
const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u;

test('more than half the rest window still ahead is "needs rest", some is "recovering", none is "recovered"', () => {
  assert.equal(recoveryState(group({ lastWorkedAt: hoursAgo(10) }), NOW).state, 'sore');     // 38 h left of 48
  assert.equal(recoveryState(group({ lastWorkedAt: hoursAgo(30) }), NOW).state, 'moderate'); // 18 h left
  assert.equal(recoveryState(group({ lastWorkedAt: hoursAgo(60) }), NOW).state, 'fresh');    // done
  // Exactly half left is still "recovering": the rule is "more than half".
  assert.equal(recoveryState(group({ lastWorkedAt: hoursAgo(24) }), NOW).state, 'moderate');
});

test('rest left is the window minus the time since, never negative, and defaults to 48 h', () => {
  const g = recoveryState(group({ lastWorkedAt: hoursAgo(10), recommendedRestHours: 0 }), NOW);
  assert.equal(g.restHours, 48);
  assert.equal(Math.round(g.remaining), 38);
  assert.equal(recoveryState(group({ lastWorkedAt: hoursAgo(500) }), NOW).remaining, 0);
});

test('a group with no last-worked time has had all its rest', () => {
  for (const lastWorkedAt of [null, undefined, 'not a date']) {
    const g = recoveryState(group({ lastWorkedAt }), NOW);
    assert.equal(g.state, 'fresh');
    assert.equal(g.remaining, 0);
  }
  assert.ok(!recoveryRow(recoveryState(group({ lastWorkedAt: null }), NOW)).includes('Invalid'));
});

test('rows come most rest still to take first, and ties go to the most recently worked', () => {
  const rows = recoveryRows([
    group({ muscleGroup: 'CALVES', lastWorkedAt: hoursAgo(60) }),
    group({ muscleGroup: 'CORE', lastWorkedAt: hoursAgo(30) }),
    group({ muscleGroup: 'GLUTES', lastWorkedAt: hoursAgo(5) }),
    group({ muscleGroup: 'ARMS', lastWorkedAt: hoursAgo(90) }),
  ], NOW);
  assert.deepEqual(rows.map(r => r.muscleGroup), ['GLUTES', 'CORE', 'CALVES', 'ARMS']);
});

test('a row is a log row: when, the muscle in words, then state and rest left', () => {
  const html = recoveryRow(recoveryState(group({ lastWorkedAt: hoursAgo(10) }), NOW));
  assert.ok(html.includes('class="ai logrow rec"'));
  assert.ok(html.includes('class="ai-when"'));
  assert.match(html, /class="an">Quadriceps</);
  assert.ok(html.includes('<span class="k">Status </span>Needs rest'));
  assert.ok(html.includes('<span class="k">Rest left </span>38<span class="u">h of 48 h</span>'));
});

test('only "needs rest" is coloured, and a recovered row has an empty rest-left cell, not a zero', () => {
  const sore = recoveryRow(recoveryState(group({ lastWorkedAt: hoursAgo(10) }), NOW));
  const mid = recoveryRow(recoveryState(group({ lastWorkedAt: hoursAgo(30) }), NOW));
  const fresh = recoveryRow(recoveryState(group({ lastWorkedAt: hoursAgo(60) }), NOW));
  assert.ok(sore.includes('rec-state warn'));
  assert.ok(!mid.includes('warn') && mid.includes('Recovering'));
  assert.ok(!fresh.includes('warn') && fresh.includes('Recovered'));
  assert.ok(!fresh.includes('Rest left'), 'no rest left to name');
  assert.ok(!fresh.includes('0 h'));
});

test('a row carries no emoji, tick, dot, dash or inline style, and an unknown muscle still renders escaped', () => {
  const html = recoveryRow(recoveryState(group({ muscleGroup: '<b>NECK</b>' }), NOW));
  assert.ok(!EMOJI.test(html), html);
  assert.ok(!html.includes('✓') && !html.includes(' · ') && !html.includes(' — '));
  assert.ok(!html.includes('style='));
  assert.ok(html.includes('&lt;b&gt;NECK&lt;/b&gt;') && !html.includes('<b>'));
  assert.match(recoveryRow(recoveryState(group({ muscleGroup: undefined }), NOW)), /class="an">Unspecified</);
});

test('the summary counts each state in one ruled row of three figures', () => {
  const rows = recoveryRows([
    group({ lastWorkedAt: hoursAgo(5) }), group({ lastWorkedAt: hoursAgo(10) }),
    group({ lastWorkedAt: hoursAgo(30) }),
    group({ lastWorkedAt: hoursAgo(60) }), group({ lastWorkedAt: hoursAgo(70) }), group({ lastWorkedAt: hoursAgo(80) }),
  ], NOW);
  const html = recoverySummary(rows);
  assert.equal((html.match(/class="fig"/g) || []).length, 3);
  assert.ok(html.includes('class="figs figs-3"'));
  const figs = [...html.matchAll(/fig-v">(\d+)<\/div><div class="fig-k">([^<]+)</g)].map(m => [m[2], Number(m[1])]);
  assert.deepEqual(figs, [['Recovered', 3], ['Recovering', 1], ['Need rest', 2]]);
});
