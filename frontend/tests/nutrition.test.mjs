// The meal row on the Nutrition page (js/panels/nutrition.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1' }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { mealRow } = await import('../js/panels/nutrition.js');

const meal = (over) => ({ id: 'm1', mealType: 'LUNCH', description: 'Rice and dal', calories: 520,
  proteinGrams: 18, carbsGrams: 80, fatGrams: 12, loggedAt: '2026-11-06T13:05:00', aiEstimated: false, ...over });

test('a meal row has four figure cells, one empty when a macro is unknown, so the columns line up', () => {
  const html = mealRow(meal({ fatGrams: null }));
  const am = html.match(/<div class="am">([\s\S]*?)<\/div>\s*<div class="ai-acts">/)[1];
  assert.equal((am.match(/<span class="k">/g) || []).length, 3, 'three known figures');
  assert.equal((am.match(/<span><\/span>/g) || []).length, 1, 'one empty cell for the missing fat');
});

test('each figure names itself, so a stacked row and a screen reader both know which is which', () => {
  const html = mealRow(meal());
  for (const name of ['Protein', 'Carbs', 'Fat', 'Energy']) assert.ok(html.includes(`<span class="k">${name} </span>`), name);
});

test('a meal row carries no emoji, no coloured dot and no middle-dot macro string', () => {
  const html = mealRow(meal());
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(html), html);
  assert.ok(!html.includes('class="ad"'));
  assert.ok(!html.includes(' · '));
});

test('an estimated meal says so in words; a measured one does not', () => {
  assert.match(mealRow(meal({ aiEstimated: true })), /class="est"[^>]*>Estimate</);
  assert.ok(!mealRow(meal()).includes('class="est"'));
});

test('free text is escaped, and the action buttons name the meal they act on', () => {
  const html = mealRow(meal({ description: '<img src=x onerror=alert(1)>' }));
  assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt;'));
  assert.ok(!html.includes('<img'));
  assert.match(html, /aria-label="Edit lunch at [^"]+"/);
});
