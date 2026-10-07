import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonCards, trimNumber } from '../util.js';
import { api } from '../api.js';
import { ACT_ICON } from './activities.js';

// ═══════════════════════════════════════════════════════════════════════
// PERSONAL BESTS
// ═══════════════════════════════════════════════════════════════════════

export async function loadPersonalBests() {
  await Promise.all([loadActivityPersonalBests(), loadExercisePersonalBests()]);
}

async function loadActivityPersonalBests() {
  const el = document.getElementById('bests-list');
  el.innerHTML = skeletonCards(3);
  try {
    const bests = await api(`/api/personal-bests/${session.currentUser.id}`);
    if (!bests.length) { el.innerHTML = emptyState('🏆', 'No records yet — log workouts to start setting personal bests!'); return; }
    el.innerHTML = `<div class="pb-grid">` + bests.map(b => `
      <div class="pb-card">
        <div class="pb-type">${ACT_ICON[b.activityType]||''} ${(b.activityType||'').replace('_',' ')}</div>
        <div class="pb-row"><span>Best Duration</span><span class="pb-val">${b.bestDuration ?? '—'} min</span></div>
        <div class="pb-row"><span>Best Calories</span><span class="pb-val">${b.bestCalories ?? '—'} cal</span></div>
        ${b.bestDistanceKm != null
          ? `<div class="pb-row"><span>Furthest</span><span class="pb-val">${trimNumber(b.bestDistanceKm)} km</span></div>` : ''}
      </div>`).join('') + `</div>`;
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load personal bests: ${escapeHtml(err.message)}</div>`;
  }
}

/**
 * Per-movement strength records.
 *
 * <p>Sits beside the by-activity-type records rather than replacing them: the two
 * answer different questions, and for a runner the type-level card is still the
 * meaningful one.
 */
async function loadExercisePersonalBests() {
  const el = document.getElementById('exercise-bests-list');
  if (!el) return;
  el.innerHTML = skeletonCards(3);
  try {
    const bests = await api(`/api/exercises/personal-bests/${session.currentUser.id}`);
    if (!bests.length) {
      el.innerHTML = emptyState('🏋️', 'No lifts logged yet — add exercises when you log a workout and your strength records appear here.');
      return;
    }
    el.innerHTML = `<div class="pb-grid">` + bests.map(b => {
      // Only the rows that have a number. A bodyweight movement has no load, no 1RM
      // and no volume, and printing three dashes for it says nothing — its record is
      // the rep count instead.
      const rows = [
        ['Heaviest', b.bestWeightKg != null
          ? `${trimNumber(b.bestWeightKg)} kg${b.bestWeightReps ? ` x ${b.bestWeightReps}` : ''}` : null],
        ['Est. 1RM', b.bestEstimatedOneRepMax != null ? `${trimNumber(b.bestEstimatedOneRepMax)} kg` : null],
        ['Best volume', b.bestVolume != null ? `${trimNumber(b.bestVolume)} kg` : null],
        ['Most reps', b.bestBodyweightReps != null ? `${b.bestBodyweightReps}` : null]
      ].filter(([, value]) => value != null);

      return `
      <div class="pb-card">
        <div class="pb-type">${escapeHtml(b.displayName || b.normalisedName || '')}</div>
        ${rows.map(([label, value]) =>
          `<div class="pb-row"><span>${label}</span><span class="pb-val">${escapeHtml(value)}</span></div>`).join('')}
      </div>`;
    }).join('') + `</div>`;
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load exercise records: ${escapeHtml(err.message)}</div>`;
  }
}
