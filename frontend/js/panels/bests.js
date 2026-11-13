import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonRows, trimNumber } from '../util.js';
import { api } from '../api.js';

// ═══════════════════════════════════════════════════════════════════════
// PERSONAL BESTS
// ═══════════════════════════════════════════════════════════════════════

export async function loadPersonalBests() {
  await Promise.all([loadActivityPersonalBests(), loadExercisePersonalBests()]);
}

/**
 * One figure of a record row: its value, its unit muted beside it, and its name, which
 * is visually hidden until the row stacks and always read by a screen reader. A record a
 * row doesn't have leaves its cell empty rather than printing a dash.
 */
function figure(name, value, unit) {
  if (value == null || value === '') return '<span></span>';
  return `<span><span class="k">${escapeHtml(name)} </span>${escapeHtml(value)}<span class="u">${escapeHtml(unit)}</span></span>`;
}

/** "WEIGHT_TRAINING" as "Weight training". */
export function typeLabel(type) {
  const s = String(type || '').replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/**
 * One activity type's records, as a row: the type, then its longest session, its most
 * calories and its furthest distance. Distance is only there for types that have one.
 */
export function activityBestRow(b) {
  return `
    <div class="ai logrow pb pb-act">
      <div class="ai-body"><div class="an">${escapeHtml(typeLabel(b.activityType) || 'Unspecified')}</div></div>
      <div class="am">${figure('Longest', b.bestDuration, 'min')}${figure('Most calories', b.bestCalories, 'cal')}${figure('Furthest', b.bestDistanceKm != null ? trimNumber(b.bestDistanceKm) : null, 'km')}</div>
    </div>`;
}

/**
 * One movement's records. A bodyweight movement has no load, no 1RM and no volume, so
 * those cells are empty and its record is the rep count instead.
 */
export function movementBestRow(b) {
  const heaviest = b.bestWeightKg != null ? trimNumber(b.bestWeightKg) : null;
  const heaviestUnit = b.bestWeightReps ? `kg x ${b.bestWeightReps}` : 'kg';
  return `
    <div class="ai logrow pb pb-mov">
      <div class="ai-body"><div class="an">${escapeHtml(b.displayName || b.normalisedName || 'Unnamed')}</div></div>
      <div class="am">${figure('Heaviest', heaviest, heaviestUnit)}${figure('Est. 1RM', b.bestEstimatedOneRepMax != null ? trimNumber(b.bestEstimatedOneRepMax) : null, 'kg')}${figure('Best volume', b.bestVolume != null ? trimNumber(b.bestVolume) : null, 'kg')}${figure('Most reps', b.bestBodyweightReps, 'reps')}</div>
    </div>`;
}

const ACT_HEAD = `
  <div class="ai logrow pb pb-act ai-head" aria-hidden="true">
    <span>Activity</span><span class="am"><span>Longest</span><span>Most calories</span><span>Furthest</span></span>
  </div>`;
const MOV_HEAD = `
  <div class="ai logrow pb pb-mov ai-head" aria-hidden="true">
    <span>Movement</span><span class="am"><span>Heaviest</span><span>Est. 1RM</span><span>Best volume</span><span>Most reps</span></span>
  </div>`;

async function loadActivityPersonalBests() {
  const el = document.getElementById('bests-list');
  el.innerHTML = skeletonRows(3);
  try {
    const bests = await api(`/api/personal-bests/${session.currentUser.id}`);
    if (!bests.length) {
      el.innerHTML = emptyState('', 'No records yet. Log a workout and its activity gets a first one.',
        '<button type="button" class="btn btn-sec btn-sm" data-action="open-modal" data-modal="modal-log-activity">Log workout</button>');
      return;
    }
    // The API gives no order, so the page picks one that doesn't move between visits.
    const rows = [...bests].sort((a, b) => typeLabel(a.activityType).localeCompare(typeLabel(b.activityType)));
    el.innerHTML = ACT_HEAD + `<div class="af">${rows.map(activityBestRow).join('')}</div>`;
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load personal bests: ${escapeHtml(err.message)}</div>`;
  }
}

/**
 * Per-movement strength records.
 *
 * <p>Sits beside the by-activity-type records rather than replacing them: the two
 * answer different questions, and for a runner the type-level card is still the
 * meaningful one. The server sends them strongest estimated 1RM first.
 */
async function loadExercisePersonalBests() {
  const el = document.getElementById('exercise-bests-list');
  if (!el) return;
  el.innerHTML = skeletonRows(3);
  try {
    const bests = await api(`/api/exercises/personal-bests/${session.currentUser.id}`);
    if (!bests.length) {
      el.innerHTML = emptyState('', 'No lifts logged yet. Add exercises when you log a workout and each movement gets its records here.');
      return;
    }
    el.innerHTML = MOV_HEAD + `<div class="af">${bests.map(movementBestRow).join('')}</div>`;
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load exercise records: ${escapeHtml(err.message)}</div>`;
  }
}
