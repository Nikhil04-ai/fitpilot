// The Goals page's grouping and the shared goal row (js/panels/goals.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1' }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { goalGroups, goalRow } = await import('../js/panels/goals.js');

const goal = (over) => ({ id: 'g', type: 'WORKOUTS_PER_WEEK', period: 'WEEKLY', direction: 'AT_LEAST',
  targetValue: 4, currentValue: 2, progressPercent: 50, isCompleted: false, isExpired: false, ...over });

test('goals group by period, and a reached weekly goal stays in its week', () => {
  const groups = goalGroups([
    goal({ id: 'w1' }),
    goal({ id: 'w2', isCompleted: true, progressPercent: 100 }),
    goal({ id: 'd1', type: 'PROTEIN_PER_DAY', period: 'DAILY' }),
    goal({ id: 'b1', type: 'TARGET_WEIGHT', period: 'NONE', direction: 'TOWARDS' })
  ]);
  assert.deepEqual(groups.map(([t, l]) => [t, l.map(g => g.id)]),
    [['This week', ['w1', 'w2']], ['Today', ['d1']], ['Body weight', ['b1']]]);
});

test('an expired goal leaves its period for Ended, and empty groups are left out', () => {
  const groups = goalGroups([goal({ id: 'w1' }), goal({ id: 'old', isExpired: true })]);
  assert.deepEqual(groups.map(([t, l]) => [t, l.map(g => g.id)]), [['This week', ['w1']], ['Ended', ['old']]]);
});

test('a goal row carries no emoji, no dash, and marks an expired goal by class, not inline style', () => {
  const html = goalRow(goal({ isExpired: true }));
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(html), html);
  assert.ok(!html.includes('—'));
  assert.ok(html.includes('class="gi expired"'));
  assert.ok(!html.includes('opacity'));
});

test('a blown budget says so in words and gets the over fill, never the done one', () => {
  const html = goalRow(goal({ type: 'CALORIE_INTAKE_PER_DAY', period: 'DAILY', direction: 'AT_MOST',
    targetValue: 2000, currentValue: 2300, progressPercent: 100, isExceeded: true, exceededBy: 300, unit: 'kcal' }));
  assert.match(html, /over by 300/);
  assert.match(html, /class="gf over"/);
});
