import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonCards, skeletonLines, trimNumber } from '../util.js';
import { toast } from '../ui.js';
import { api } from '../api.js';
import { shortAxisDate } from '../charts.js';

// ═══════════════════════════════════════════════════════════════════════
// AI COACH — Gemini-powered recommendations (Feature 10)
// ═══════════════════════════════════════════════════════════════════════

const REC_TYPE_META = {
  PERFORMANCE: { label: 'Performance', icon: '📈', cls: 'b' },
  RECOVERY:    { label: 'Recovery',    icon: '🛡',  cls: 'c' },
  SAFETY:      { label: 'Safety',      icon: '⚠️',  cls: 'r' },
  MOTIVATION:  { label: 'Motivation',  icon: '🔥',  cls: 'g' }
};

function recommendationCard(r) {
  const meta = REC_TYPE_META[r.type] || { label: r.type || 'Insight', icon: '💡', cls: 'b' };
  const improvements = (r.improvements || []).map(i => `<div class="apill">${escapeHtml(i)}</div>`).join('');
  const suggestions  = (r.suggestions  || []).map(s => `<div class="apill">${escapeHtml(s)}</div>`).join('');
  const safety = r.safety || [];
  const dateStr = r.createdAt ? new Date(r.createdAt).toLocaleDateString('en', { day: 'numeric', month: 'short' }) : '';

  return `
    <div class="ac">
      <div class="atag ${meta.cls}"><div class="adot ${meta.cls}"></div>${meta.icon} ${escapeHtml(meta.label)}</div>
      <div class="abody">${escapeHtml(r.recommendation || 'No details available.')}</div>
      ${improvements ? `<h3 class="clbl" style="margin-bottom:6px">Improvements</h3><div class="apills" style="margin-bottom:12px">${improvements}</div>` : ''}
      ${suggestions  ? `<h3 class="clbl" style="margin-bottom:6px">Suggestions</h3><div class="apills" style="margin-bottom:12px">${suggestions}</div>`   : ''}
      ${safety.length ? `<div style="margin-top:6px;background:rgba(255,59,92,.07);border:1px solid rgba(255,59,92,.2);border-radius:9px;padding:10px 12px;font-size:12px;color:var(--silver)">⚠️ ${safety.map(escapeHtml).join(' · ')}</div>` : ''}
      <div style="margin-top:14px;font-family:var(--fm);font-size:10px;color:var(--muted)">${dateStr}</div>
    </div>`;
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
      el.innerHTML = emptyState('📋',
        'No weekly digest yet — one is written every Monday, or generate last week’s now.');
      return;
    }
    renderDigest(el, digest);
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load your digest: ${escapeHtml(err.message)}</div>`;
  }
}

function renderDigest(el, d) {
  const week = d.weekStart
    ? `${shortAxisDate(d.weekStart)} – ${shortAxisDate(d.weekEnd)}`
    : '';

  // Said out loud rather than hidden. The fallback states the facts and the model
  // interprets them, and the reader is entitled to know which one they are reading.
  const source = d.aiGenerated
    ? '<span class="dg-src ai" title="Written by Gemini from your logged figures">✨ AI narrative</span>'
    : '<span class="dg-src rules" title="No Gemini key configured — your figures, summarised by rule">📊 Your figures</span>';

  const list = (items, cls) => (items && items.length)
    ? `<div class="dg-sec ${cls}"><h4>${cls === 'focus' ? 'Next week' : 'What the week showed'}</h4><ul>${
        items.map(i => `<li>${escapeHtml(i)}</li>`).join('')}</ul></div>`
    : '';

  el.innerHTML = `
    <div class="dg-head">
      <div>
        <div class="dg-hl">${escapeHtml(d.headline || 'Your week')}</div>
        <div class="dg-week">${escapeHtml(week)}</div>
      </div>
      ${source}
    </div>
    ${d.narrative ? `<div class="dg-nar">${escapeHtml(d.narrative)}</div>` : ''}
    ${list(d.observations, 'obs')}
    ${list(d.focusNextWeek, 'focus')}
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
    toast(digest.aiGenerated ? '📋 Weekly digest ready' : '📋 Digest ready — from your own figures');
  } catch (err) {
    toast('❌ ' + err.message, true);
    // The button failing shouldn't also wipe whatever was already on screen.
    loadWeeklyDigest();
  } finally {
    btn.disabled = false; btn.textContent = '📋 Weekly Digest';
  }
}

/** The AI Coach panel: the week as a whole, then the per-workout insights. */
export async function loadRecommendations() {
  await Promise.all([loadWeeklyDigest(), loadPerWorkoutInsights()]);
}

async function loadPerWorkoutInsights() {
  const el = document.getElementById('ai-list');
  el.innerHTML = skeletonCards(3);
  try {
    const list = await api(`/api/recommendation/user/${session.currentUser.id}`);
    const sorted = [...list].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
    el.innerHTML = sorted.length
      ? sorted.map(recommendationCard).join('')
      : `<div style="grid-column:1/-1">${emptyState('🧠', 'No AI insights yet. Recommendations auto-generate every 3rd workout — or click "Generate Insight" above to get one now.')}</div>`;
  } catch (err) {
    el.innerHTML = `<div class="error-state" style="grid-column:1/-1">Couldn't load AI insights: ${escapeHtml(err.message)}</div>`;
  }
}

export async function loadOverviewAiTeaser() {
  const el = document.getElementById('overview-ai-teaser');
  try {
    const list = await api(`/api/recommendation/user/${session.currentUser.id}`);
    if (!list.length) {
      el.innerHTML = `
        <div class="ai-teaser">
          <div class="ai-teaser-icon">🧠</div>
          <div style="flex:1">
            <div style="font-weight:700;font-size:14px">No AI insights yet</div>
            <div style="font-size:12px;color:var(--muted);margin-top:2px">Log 3 workouts and Gemini will auto-generate your first coaching insight.</div>
          </div>
          <button class="btn-ghost" data-action="nav" data-panel="ai">View AI Coach →</button>
        </div>`;
      return;
    }
    const latest = [...list].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))[0];
    const meta = REC_TYPE_META[latest.type] || { label: latest.type || 'Insight', icon: '💡' };
    el.innerHTML = `
      <div class="ai-teaser">
        <div class="ai-teaser-icon">${meta.icon}</div>
        <div style="flex:1;min-width:0">
          <div style="font-weight:700;font-size:14px">Latest AI Insight — ${escapeHtml(meta.label)}</div>
          <div style="font-size:12px;color:var(--muted);margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${escapeHtml(latest.recommendation || '')}</div>
        </div>
        <button class="btn-ghost" data-action="nav" data-panel="ai">View All →</button>
      </div>`;
  } catch (err) {
    el.innerHTML = '';
  }
}

export async function generateRecommendationNow() {
  const btn = document.getElementById('gen-insight-btn');
  btn.disabled = true; btn.textContent = 'Thinking…';
  try {
    const latestPage = await api('/api/activities?page=0&size=1&sort=createdAt,desc');
    const latestActivity = (latestPage.content || [])[0];
    if (!latestActivity) {
      toast('Log a workout first — insights are based on your activity history.', true);
      return;
    }
    const rec = await api('/api/recommendation/generate', {
      method: 'POST',
      body: JSON.stringify({ userId: session.currentUser.id, activityId: latestActivity.id })
    });
    toast('✨ New insight generated!');
    loadPerWorkoutInsights();   // the digest above is weekly and unaffected by one new insight
    const overviewTeaser = document.getElementById('overview-ai-teaser');
    if (overviewTeaser) loadOverviewAiTeaser();
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = '✨ Generate Insight';
  }
}
