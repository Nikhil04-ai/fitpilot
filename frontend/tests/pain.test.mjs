// The pain entry row and the pattern note on the Pain Log page (js/panels/pain.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1' }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { painRow, patternsNote } = await import('../js/panels/pain.js');

const entry = (over) => ({ id: 'p1', bodyPart: 'LEFT_KNEE', severity: 4, description: 'Sharp on the descent',
  loggedAt: '2026-11-06T13:05:00', ...over });
const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u;

test('an entry is a log row: when, the body part in words, then severity as a figure and a word', () => {
  const html = painRow(entry());
  assert.ok(html.includes('class="ai logrow pain"'));
  assert.ok(html.includes('class="ai-when"'));
  assert.match(html, /class="an">Left knee</);
  assert.ok(html.includes('<span class="k">Severity </span>4 <span class="u">of 5, significant</span>'));
});

test('severity is named in words at every step of the scale, with no colour dot', () => {
  const words = { 1: 'mild', 2: 'noticeable', 3: 'moderate', 4: 'significant', 5: 'severe' };
  for (const [n, word] of Object.entries(words)) {
    const html = painRow(entry({ severity: Number(n) }));
    assert.ok(html.includes(`of 5, ${word}<`), `${n} should read "${word}"`);
    assert.ok(!html.includes('class="ad"'));
    assert.ok(!html.includes('style='), 'no inline colour');
  }
});

test('a row carries no emoji, no middle dot and no dash joining the parts', () => {
  const html = painRow(entry());
  assert.ok(!EMOJI.test(html), html);
  assert.ok(!html.includes(' · '));
  assert.ok(!html.includes(' — '));
});

test('no description leaves no empty note, and an unknown severity or body part still renders', () => {
  const html = painRow(entry({ description: null, severity: 9, bodyPart: 'ELBOW' }));
  assert.ok(!html.includes('ai-note'));
  assert.match(html, /class="an">ELBOW</);
  assert.ok(html.includes('9 <span class="u">of 5</span>'));
});

test('free text is escaped', () => {
  const html = painRow(entry({ description: '<img src=x onerror=alert(1)>' }));
  assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt;'));
  assert.ok(!html.includes('<img'));
});

test('patterns become one warm note with a list item each, shown as the server wrote them', () => {
  const html = patternsNote(['Left knee reported 3 times in 21 days', 'Hips reported 4 times in 21 days']);
  assert.equal((html.match(/class="note note-warn"/g) || []).length, 1);
  assert.equal((html.match(/<li>/g) || []).length, 2);
  assert.ok(html.includes('Pattern detected.'));
  assert.ok(!EMOJI.test(html), 'no warning glyph');
  assert.ok(!html.includes('style='));
});

test('no patterns means no note at all, and a pattern string is escaped', () => {
  assert.equal(patternsNote([]), '');
  assert.equal(patternsNote(null), '');
  assert.ok(patternsNote(['<b>x</b>']).includes('&lt;b&gt;x&lt;/b&gt;'));
});
