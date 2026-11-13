import { session } from '../state.js';
import { emptyState, escapeHtml, shortDate, skeletonRows } from '../util.js';
import { api } from '../api.js';

// ═══════════════════════════════════════════════════════════════════════
// MUSCLE RECOVERY — Feature 4
// ═══════════════════════════════════════════════════════════════════════

const MUSCLE_LABEL = {
  QUADRICEPS:'Quadriceps', HAMSTRINGS:'Hamstrings', CALVES:'Calves', CORE:'Core',
  GLUTES:'Glutes', SHOULDERS:'Shoulders', BACK:'Back', ARMS:'Arms',
  LEGS:'Legs', CHEST:'Chest', FULL_BODY:'Full body'
};

// The three states, in the words the page uses for them. Only the last is coloured:
// a muscle that needs rest is the one thing here you might act on.
const STATE_WORD = { fresh: 'Recovered', moderate: 'Recovering', sore: 'Needs rest' };

/**
 * Where one muscle group stands, as of `now` (ms). Rest left is the recommended window
 * minus the time since it was last worked, and the state is how much of the window is
 * still ahead: more than half is "needs rest", some is "recovering", none is "recovered".
 * A group with no recorded last-worked time has had all its rest.
 */
export function recoveryState(g, now = Date.now()) {
  const restHours = g.recommendedRestHours || 48;
  const lastWorked = g.lastWorkedAt ? new Date(g.lastWorkedAt).getTime() : NaN;
  const hoursSince = Number.isNaN(lastWorked) ? Infinity : (now - lastWorked) / 3600000;
  const remaining = Math.max(0, restHours - hoursSince);
  let state = 'fresh';
  if (remaining > restHours * 0.5) state = 'sore';
  else if (remaining > 0) state = 'moderate';
  return { ...g, restHours, remaining, state };
}

/** Every group with its state, the ones with the most rest still to take first. */
export function recoveryRows(groups, now = Date.now()) {
  return groups.map(g => recoveryState(g, now))
    .sort((a, b) => b.remaining - a.remaining || new Date(b.lastWorkedAt || 0) - new Date(a.lastWorkedAt || 0));
}

/**
 * One muscle group, as a row of the sheet: when it was last worked, its name, then its
 * state and what rest is left. The two figures' names are visually hidden until the row
 * stacks, as on the pain rows, and always read by a screen reader. A recovered group has
 * no rest left, so that cell is empty rather than a zero.
 */
export function recoveryRow(g) {
  const when = g.lastWorkedAt ? shortDate(g.lastWorkedAt) : '';
  const name = MUSCLE_LABEL[g.muscleGroup] || g.muscleGroup || 'Unspecified';
  const left = g.remaining > 0
    ? `<span><span class="k">Rest left </span>${Math.ceil(g.remaining)}<span class="u">h of ${escapeHtml(g.restHours)} h</span></span>`
    : '<span></span>';
  return `
    <div class="ai logrow rec">
      <div class="ai-when">${escapeHtml(when)}</div>
      <div class="ai-body"><div class="an">${escapeHtml(name)}</div></div>
      <div class="am"><span class="rec-state${g.state === 'sore' ? ' warn' : ''}"><span class="k">Status </span>${STATE_WORD[g.state]}</span>${left}</div>
    </div>`;
}

/** How many groups are in each state: three figures in a ruled row. */
export function recoverySummary(rows) {
  const n = { fresh: 0, moderate: 0, sore: 0 };
  rows.forEach(g => n[g.state]++);
  const fig = (v, k) => `<div class="fig"><div class="fig-v">${v}</div><div class="fig-k">${k}</div></div>`;
  return `<div class="figs figs-3">${fig(n.fresh, 'Recovered')}${fig(n.moderate, 'Recovering')}${fig(n.sore, 'Need rest')}</div>`;
}

export async function loadRecovery() {
  const el = document.getElementById('recovery-content');
  el.innerHTML = skeletonRows(6);
  try {
    const groups = await api(`/api/recovery/status/${session.currentUser.id}`);
    if (!groups.length) {
      el.innerHTML = emptyState('', 'No recovery data yet. Log a workout and each muscle it works gets a rest window.',
        '<button type="button" class="btn btn-sec btn-sm" data-action="open-modal" data-modal="modal-log-activity">Log workout</button>');
      return;
    }
    const rows = recoveryRows(groups);
    el.innerHTML = `
      ${recoverySummary(rows)}
      <div class="ai logrow rec ai-head" aria-hidden="true">
        <span>Last worked</span><span>Muscle group</span><span class="am"><span>Status</span><span>Rest left</span></span>
      </div>
      <div class="af">${rows.map(recoveryRow).join('')}</div>`;
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load recovery data: ${escapeHtml(err.message)}</div>`;
  }
}
