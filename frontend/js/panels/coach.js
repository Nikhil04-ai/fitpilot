import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonCards, skeletonLines, trimNumber } from '../util.js';
import { renderPager, toast } from '../ui.js';
import { api } from '../api.js';
import { shortAxisDate } from '../charts.js';

// ═══════════════════════════════════════════════════════════════════════
// AI COACH — Gemini-powered recommendations (Feature 10)
// ═══════════════════════════════════════════════════════════════════════

// The kind of insight, said in words. Only a safety note carries a colour, because
// that one is a warning; the rest are the same kind of thing and look it.
const REC_TYPE_META = {
  PERFORMANCE: { label: 'Performance' },
  RECOVERY:    { label: 'Recovery' },
  SAFETY:      { label: 'Safety', warn: true },
  MOTIVATION:  { label: 'Motivation' }
};

/** A titled list inside an insight, or nothing when the list is empty. */
function insightList(title, items) {
  return items && items.length
    ? `<h4 class="mini-ttl">${title}</h4><ul class="ac-list">${items.map(i => `<li>${escapeHtml(i)}</li>`).join('')}</ul>`
    : '';
}

/** One per-workout insight, as a note: its kind and date, what it says, then its lists. */
export function recommendationCard(r) {
  const meta = REC_TYPE_META[r.type] || { label: r.type || 'Insight' };
  const safety = r.safety || [];
  const dateStr = r.createdAt ? new Date(r.createdAt).toLocaleDateString('en', { day: 'numeric', month: 'short' }) : '';

  return `
    <article class="ac">
      <div class="ac-head">
        <span class="atag${meta.warn ? ' r' : ''}">${escapeHtml(meta.label)}</span>
        <time class="ac-date">${escapeHtml(dateStr)}</time>
      </div>
      <p class="abody">${escapeHtml(r.recommendation || 'No details available.')}</p>
      ${insightList('Improvements', r.improvements)}
      ${insightList('Suggestions', r.suggestions)}
      ${safety.length ? `<div class="note note-warn"><strong>Take care.</strong><ul class="ac-list">${safety.map(s => `<li>${escapeHtml(s)}</li>`).join('')}</ul></div>` : ''}
    </article>`;
}

// ═══════════════════════════════════════════════════════════════════════
// WEEKLY DIGEST  (PLAN P2-3)
// ═══════════════════════════════════════════════════════════════════════

/**
 * The latest digest, or an invitation to generate one.
 *
 * 204 means none has been generated yet, which is a normal state rather than an error —
 * the scheduled run writes the first one on Monday morning.
 */
async function loadWeeklyDigest() {
  const el = document.getElementById('digest-card');
  el.innerHTML = skeletonLines(4);
  try {
    const digest = await api('/api/digest');
    if (!digest) {
      el.innerHTML = emptyState('',
        'No weekly digest yet. One is written every Monday, or write last week’s now.');
      return;
    }
    renderDigest(el, digest);
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load your digest: ${escapeHtml(err.message)}</div>`;
  }
}

export function renderDigest(el, d) {
  const week = d.weekStart
    ? `${shortAxisDate(d.weekStart)} to ${shortAxisDate(d.weekEnd)}`
    : '';

  // Said out loud rather than hidden. The fallback states the facts and the model
  // interprets them, and the reader is entitled to know which one they are reading.
  const source = d.aiGenerated
    ? '<span class="dg-src ai" title="Written by Gemini from your logged figures">Written by Gemini</span>'
    : '<span class="dg-src rules" title="No Gemini key configured: your figures, summarised by rule">From your figures</span>';

  const list = (items, cls) => (items && items.length)
    ? `<div class="dg-sec ${cls}"><h4>${cls === 'focus' ? 'Next week' : 'What the week showed'}</h4><ul>${
        items.map(i => `<li>${escapeHtml(i)}</li>`).join('')}</ul></div>`
    : '';

  // The headline leads, as a report's would; the two lists sit side by side under the
  // narrative; the figures the narrative was written from close it.
  el.innerHTML = `
    <div class="dg-head">
      <div>
        <p class="dg-hl">${escapeHtml(d.headline || 'Your week')}</p>
        <div class="dg-week">${escapeHtml(week)}</div>
      </div>
      ${source}
    </div>
    ${d.narrative ? `<p class="dg-nar">${escapeHtml(d.narrative)}</p>` : ''}
    <div class="dg-lists">${list(d.observations, 'obs')}${list(d.focusNextWeek, 'focus')}</div>
    ${digestFacts(d.dataPoints)}
  `;
}

/**
 * The figures the narrative was written from, shown next to it.
 *
 * This is the half that makes the digest checkable: the model is handed these numbers
 * and asked to interpret them, never to supply them, so a sentence that disagrees with
 * the tiles below it is visibly wrong rather than quietly believed.
 */
function digestFacts(data) {
  if (!data) return '';
  const t = data.training || {};
  const n = data.nutrition || {};
  const s = data.sleep || {};
  const b = data.bodyWeight || {};

  const tiles = [
    ['Sessions', t.sessions],
    ['Minutes', t.minutes],
    ['Calories', t.calories],
    ['Volume', t.volumeKg ? trimNumber(t.volumeKg) + ' kg' : null],
    ['Food days', n.daysLogged ? n.daysLogged + '/7' : null],
    ['Avg intake', n.avgCaloriesPerLoggedDay ? n.avgCaloriesPerLoggedDay + ' kcal' : null],
    ['Avg protein', n.avgProteinPerLoggedDay ? trimNumber(n.avgProteinPerLoggedDay) + 'g' : null],
    ['Avg sleep', s.avgHours ? trimNumber(s.avgHours) + 'h' : null],
    ['Weight change', b.changeKg != null ? (b.changeKg > 0 ? '+' : '') + trimNumber(b.changeKg) + ' kg' : null]
  ].filter(([, v]) => v != null && v !== 0 && v !== '');

  if (!tiles.length) return '';
  return `<div class="dg-facts">${tiles.map(([label, value]) =>
    `<div class="dg-fact"><b>${escapeHtml(String(value))}</b><span>${escapeHtml(label)}</span></div>`
  ).join('')}</div>`;
}

export async function generateDigestNow() {
  const btn = document.getElementById('gen-digest-btn');
  btn.disabled = true; btn.textContent = 'Thinking…';
  const el = document.getElementById('digest-card');
  el.innerHTML = skeletonLines(4);

  try {
    const digest = await api('/api/digest/generate', { method: 'POST' });
    renderDigest(el, digest);
    toast(digest.aiGenerated ? 'Weekly digest ready' : 'Digest ready, from your own figures');
  } catch (err) {
    toast(err.message, true);
    // The button failing shouldn't also wipe whatever was already on screen.
    loadWeeklyDigest();
  } finally {
    btn.disabled = false; btn.textContent = 'Write last week’s digest';
  }
}

/** The AI Coach panel: the week as a whole, then the per-workout insights. */
export async function loadRecommendations() {
  await Promise.all([loadWeeklyDigest(), loadPerWorkoutInsights()]);
}

async function loadPerWorkoutInsights(page = 0) {
  const el = document.getElementById('ai-list');
  el.innerHTML = skeletonCards(3);
  try {
    // Paged and already newest first. The whole history used to arrive in one response
    // and be sorted here, growing with every insight (PLAN P4-19).
    const res = await api(`/api/recommendation/user/${session.currentUser.id}?page=${page}&size=9`);
    const rows = res.content || [];
    el.innerHTML = rows.length
      ? rows.map(recommendationCard).join('')
      : emptyState('', 'No AI insights yet. Recommendations auto-generate every 3rd workout, or use Generate insight above to get one now.');
    renderPager('ai-pager', page, res.page?.totalPages ?? 1, loadPerWorkoutInsights);
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load AI insights: ${escapeHtml(err.message)}</div>`;
  }
}

export async function loadOverviewAiTeaser() {
  const el = document.getElementById('overview-ai-teaser');
  try {
    // Only the newest is shown, so only the newest is asked for (PLAN P4-19).
    const res = await api(`/api/recommendation/user/${session.currentUser.id}?page=0&size=1`);
    const list = res.content || [];
    if (!list.length) {
      el.innerHTML = `
        <div class="ai-teaser">
          <div class="ai-teaser-body">
            <div class="ai-teaser-ttl">No AI insights yet</div>
            <div class="ai-teaser-text">Log 3 workouts and Gemini will auto-generate your first coaching insight.</div>
          </div>
          <button type="button" class="btn-ghost" data-action="nav" data-panel="ai">View AI Coach</button>
        </div>`;
      return;
    }
    const latest = list[0];
    const meta = REC_TYPE_META[latest.type] || { label: latest.type || 'Insight' };
    el.innerHTML = `
      <div class="ai-teaser">
        <div class="ai-teaser-body">
          <div class="ai-teaser-ttl">Latest AI insight <span class="ai-teaser-kind">${escapeHtml(meta.label)}</span></div>
          <div class="ai-teaser-text">${escapeHtml(latest.recommendation || '')}</div>
        </div>
        <button type="button" class="btn-ghost" data-action="nav" data-panel="ai">View all</button>
      </div>`;
  } catch (err) {
    el.innerHTML = '';
  }
}

export async function generateRecommendationNow() {
  const btn = document.getElementById('gen-insight-btn');
  btn.disabled = true; btn.textContent = 'Thinking…';
  try {
    const latestPage = await api('/api/activities?page=0&size=1&sort=startTime,desc');
    const latestActivity = (latestPage.content || [])[0];
    if (!latestActivity) {
      toast('Log a workout first: insights are based on your activity history.', true);
      return;
    }
    const rec = await api('/api/recommendation/generate', {
      method: 'POST',
      body: JSON.stringify({ userId: session.currentUser.id, activityId: latestActivity.id })
    });
    toast('New insight generated');
    loadPerWorkoutInsights();   // the digest above is weekly and unaffected by one new insight
    const overviewTeaser = document.getElementById('overview-ai-teaser');
    if (overviewTeaser) loadOverviewAiTeaser();
  } catch (err) {
    toast(err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Generate insight';
  }
}
