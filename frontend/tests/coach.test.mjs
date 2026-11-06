// The AI Coach's insight note and weekly digest (js/panels/coach.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1' }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { recommendationCard, renderDigest } = await import('../js/panels/coach.js');

const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u;

test('an insight names its kind in words, with no emoji and no inline style', () => {
  const html = recommendationCard({ type: 'PERFORMANCE', recommendation: 'Add a tempo run.',
    improvements: ['Pace held'], suggestions: ['Try intervals'], createdAt: '2026-11-05T09:00:00' });
  assert.match(html, /class="atag">Performance</);
  assert.ok(!EMOJI.test(html), html);
  assert.ok(!html.includes('style='));
  assert.match(html, /<li>Pace held<\/li>/);
});

test('only a safety insight is marked as a warning, and its notes are a list, not a dot-joined line', () => {
  const html = recommendationCard({ type: 'SAFETY', recommendation: 'Rest your knee.', safety: ['Knee pain twice', 'Load up 30%'] });
  assert.match(html, /class="atag r">Safety</);
  assert.match(html, /note-warn/);
  assert.ok(!html.includes(' · '));
  assert.equal((html.match(/<li>/g) || []).length, 2);
  assert.ok(!recommendationCard({ type: 'RECOVERY', recommendation: 'x' }).includes('atag r'));
});

test('insight text is escaped', () => {
  const html = recommendationCard({ type: 'MOTIVATION', recommendation: '<img src=x onerror=alert(1)>' });
  assert.ok(html.includes('&lt;img'));
  assert.ok(!html.includes('<img'));
});

test('the digest says in words whether Gemini wrote it, and uses no emoji or dash in the week', () => {
  const box = el('div');
  renderDigest(box, { aiGenerated: false, headline: 'Steady week', weekStart: '2026-10-26', weekEnd: '2026-11-01',
    observations: ['Four sessions'], focusNextWeek: ['Sleep earlier'], dataPoints: { training: { sessions: 4 } } });
  assert.match(box.innerHTML, /From your figures/);
  assert.ok(!EMOJI.test(box.innerHTML), box.innerHTML);
  // The helper DOM keeps innerHTML as a string, so the week is read from the markup.
  assert.match(box.innerHTML, /<div class="dg-week">[^<]+ to [^<]+<\/div>/);
  assert.ok(!/class="dg-week">[^<]*[–—]/.test(box.innerHTML));
  const ai = el('div');
  renderDigest(ai, { aiGenerated: true, headline: 'x' });
  assert.match(ai.innerHTML, /Written by Gemini/);
});
