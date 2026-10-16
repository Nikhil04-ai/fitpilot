import { session } from '../state.js';
import { emptyState, escapeHtml, localDateTimeNow, shortDate, skeletonRows, trimNumber } from '../util.js';
import { closeModal, confirmDialog, openModal, renderPager, toast } from '../ui.js';
import { api } from '../api.js';
import { panelLoaders } from '../nav.js';

// ═══════════════════════════════════════════════════════════════════════
// ACTIVITIES
// ═══════════════════════════════════════════════════════════════════════

export const ACT_ICON = {
  RUNNING:'🏃', WALKING:'🚶', CYCLING:'🚴', SWIMMING:'🏊', WEIGHT_TRAINING:'🏋️',
  YOGA:'🧘', HIIT:'⚡', CARDIO:'🏄', STRETCHING:'🤸', OTHER:'➕'
};
const ACT_DOT_COLOR = {
  RUNNING:'var(--blue)', WALKING:'var(--blue)', CYCLING:'var(--cyan)', SWIMMING:'var(--cyan)',
  WEIGHT_TRAINING:'var(--gold)', YOGA:'#A855F7', HIIT:'var(--gold)', CARDIO:'var(--cyan)',
  STRETCHING:'#A855F7', OTHER:'var(--blue)'
};

/** Labels for the metrics Gemini extracts, so a parsed "5km" is actually shown. */
const METRIC_LABEL = {
  distance_km: v => `${v} km`,
  distance:    v => `${v} km`,
  sets:        v => `${v} sets`,
  reps:        v => `${v} reps`,
  weight_kg:   v => `${v} kg`,
  pace:        v => `${v} pace`,
  steps:       v => `${v} steps`
};

/**
 * Renders additionalMetrics — the distance/sets/reps the natural-language parser
 * extracts. These were stored and returned by the API all along but never displayed,
 * so anything you described was silently dropped from view (PLAN.md P0-2).
 */
function metricChips(metrics) {
  if (!metrics || typeof metrics !== 'object') return '';
  return Object.entries(metrics)
    // distance_km and notes both have somewhere better to live now — the column
    // and the row's own title — so a chip for either is a duplicate.
    .filter(([k, v]) => v != null && v !== '' && k !== 'notes'
                     && k !== 'distance_km' && k !== 'distanceKm')
    .map(([k, v]) => {
      const fmt = METRIC_LABEL[k];
      const text = fmt ? fmt(v) : `${k.replace(/_/g, ' ')}: ${v}`;
      return `<span class="ai-chip">${escapeHtml(String(text))}</span>`;
    })
    .join('');
}

/** Clickable 1-5 difficulty rating. Feature 13 had an endpoint but no UI (PLAN.md P0-1). */
function ratingStars(a) {
  const current = a.difficultyRating || 0;
  let html = '<span class="rate" title="How hard did it feel?">';
  for (let i = 1; i <= 5; i++) {
    html += `<button class="${i <= current ? 'on' : ''}" data-action="rate-activity" data-id="${a.id}" data-rating="${i}" aria-label="Rate ${i} of 5">◆</button>`;
  }
  return html + '</span>';
}

export function activityRow(a) {
  const icon = ACT_ICON[a.type] || '🏃';
  const dotColor = ACT_DOT_COLOR[a.type] || 'var(--blue)';
  const label = (a.type || '').replace('_',' ');
  const dur = a.duration != null ? `${a.duration} min` : '';
  const cal = a.caloriesBurned != null ? `${a.caloriesBurned} cal` : '';
  const dist = a.distanceKm != null ? `${trimNumber(a.distanceKm)} km` : '';
  const meta = [dist, dur, cal].filter(Boolean).join(' · ');
  const when = a.startTime ? shortDate(a.startTime) : '';
  const volume = a.totalVolume != null
    ? `<span class="ai-chip">${trimNumber(a.totalVolume)} kg total</span>` : '';
  // distanceKm has its own column now, so it must not also be rendered out of the
  // metrics blob — old rows still carry it there and it would appear twice.
  const sub = [when ? `<span>${escapeHtml(when)}</span>` : '',
               metricChips(a.additionalMetrics), volume, ratingStars(a)]
    .filter(Boolean).join('');
  return `
    <div class="ai">
      <div class="ad" style="background:${dotColor};box-shadow:0 0 6px ${dotColor}"></div>
      <div class="ai-body">
        <div class="an">${icon} ${escapeHtml(label)}${a.notes ? ' — ' + escapeHtml(a.notes) : ''}</div>
        <div class="ai-sub">${sub}</div>
        ${exerciseList(a.exercises)}
      </div>
      <div class="am">${meta}</div>
      ${a.isPersonalBest ? '<span class="apr">⭐ PR</span>' : ''}
      <div class="ai-acts">
        <button title="Edit" data-action="edit-activity" data-id="${a.id}">✎</button>
        <button title="Delete" class="danger" data-action="delete-activity" data-id="${a.id}">✕</button>
      </div>
    </div>`;
}

/** Submits a difficulty rating and surfaces the progression suggestion it unlocks. */
export async function rateActivity(id, rating) {
  try {
    const res = await api(`/api/activities/${id}/rate`, {
      method: 'POST',
      body: JSON.stringify({ rating })
    });
    toast(`Rated ${rating}/5`);
    if (res.progressionSuggestion) {
      setTimeout(() => toast('📈 ' + res.progressionSuggestion), 1200);
    }
    loadActivities(currentActivityPage);
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
}

export async function deleteActivity(id) {
  const ok = await confirmDialog({
    title: 'Delete workout?',
    message: 'Your streak, records and goal progress will be recalculated without it.',
    confirmLabel: 'Delete'
  });
  if (!ok) return;
  try {
    await api(`/api/activities/${id}`, { method: 'DELETE' });
    toast('🗑 Workout deleted');
    loadActivities(currentActivityPage);
    refreshStreakFromProfile();
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
}

/** Opens the edit modal pre-filled from the row already on screen. */
export function openEditActivity(id) {
  const a = (activityCache || []).find(x => x.id === id);
  if (!a) return;
  document.getElementById('edit-act-id').value = a.id;
  document.getElementById('edit-act-type').value = a.type || 'OTHER';
  document.getElementById('edit-act-duration').value = a.duration != null ? a.duration : '';
  const caloriesInput = document.getElementById('edit-act-calories');
  caloriesInput.value = a.caloriesBurned != null ? a.caloriesBurned : '';
  // Remembered so the save can tell "left alone" from "changed" (see handleEditActivity).
  caloriesInput.dataset.original = caloriesInput.value;
  document.getElementById('edit-act-notes').value = a.notes || '';
  document.getElementById('edit-act-distance').value = a.distanceKm != null ? a.distanceKm : '';

  clearExerciseRows('edit-act-exercises');
  (a.exercises || []).forEach(ex => addExerciseRow('edit-act-exercises', ex));

  openModal('modal-edit-activity');
}

export async function handleEditActivity(e) {
  e.preventDefault();
  const btn = document.getElementById('edit-activity-btn');
  btn.disabled = true; btn.textContent = 'Saving…';

  const id = document.getElementById('edit-act-id').value;
  const caloriesInput = document.getElementById('edit-act-calories');
  const calories = caloriesInput.value;
  const distance = document.getElementById('edit-act-distance').value;
  const payload = {
    type: document.getElementById('edit-act-type').value,
    duration: parseInt(document.getElementById('edit-act-duration').value),
    distanceKm: distance ? parseFloat(distance) : null,
    // Always sent, including as [], because the modal shows the current list: an
    // omitted list means "leave them alone" server-side, which would silently ignore
    // the user deleting every row.
    exercises: collectExerciseRows('edit-act-exercises'),
    notes: document.getElementById('edit-act-notes').value.trim() || null
  };
  // A figure is sent only when the field was changed (PLAN P4-2). Sending the
  // pre-filled figure back every time pinned it as "typed", so changing the duration
  // never updated a calculated figure. Left alone, the server keeps a typed figure and
  // re-derives a calculated one if type or duration changed. Blank, the placeholder
  // promises a recalculation, so ask for one explicitly.
  if (!calories) payload.recalculateCalories = true;
  else if (calories !== caloriesInput.dataset.original) payload.caloriesBurned = parseInt(calories);

  try {
    await api(`/api/activities/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
    closeModal('modal-edit-activity');
    toast('Workout updated');
    loadActivities(currentActivityPage);
    refreshStreakFromProfile();
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Save Changes';
  }
}

/** An edit or delete can change the streak, so pull the authoritative number back. */
async function refreshStreakFromProfile() {
  try {
    const profile = await api(`/api/profile/${session.currentUser.id}`);
    if (profile && profile.currentStreak != null) {
      session.currentUser.currentStreak = profile.currentStreak;
      localStorage.setItem('fp_user', JSON.stringify(session.currentUser));
      document.getElementById('topbar-streak').textContent = profile.currentStreak;
    }
  } catch (err) { /* the list already refreshed; a stale streak badge isn't worth a toast */ }
}

let activityCache = [];
let currentActivityPage = 0;

export async function loadActivities(page = 0) {
  const el = document.getElementById('activities-list');
  el.innerHTML = skeletonRows(5);
  try {
    const data = await api(`/api/activities?page=${page}&size=10&sort=startTime,desc`);
    const items = data.content || [];
    activityCache = items;
    currentActivityPage = page;
    el.innerHTML = items.length ? items.map(activityRow).join('') : emptyState('🏃', 'No workouts logged yet.');
    renderPager('activities-pager', page, data.page?.totalPages ?? 1, loadActivities);
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load activities: ${escapeHtml(err.message)}</div>`;
    document.getElementById('activities-pager').innerHTML = '';
  }
}

export async function handleLogActivity(e) {
  e.preventDefault();
  const btn = document.getElementById('log-activity-btn');
  btn.disabled = true; btn.textContent = 'Logging…';

  const caloriesInput = document.getElementById('act-calories').value;
  const distanceInput = document.getElementById('act-distance').value;
  const payload = {
    userId: session.currentUser.id,
    type: document.getElementById('act-type').value,
    duration: parseInt(document.getElementById('act-duration').value),
    caloriesBurned: caloriesInput ? parseInt(caloriesInput) : null,
    distanceKm: distanceInput ? parseFloat(distanceInput) : null,
    exercises: collectExerciseRows('act-exercises'),
    notes: document.getElementById('act-notes').value.trim() || null,
    startTime: localDateTimeNow()
  };

  try {
    const res = await api('/api/activities', { method: 'POST', body: JSON.stringify(payload) });
    closeModal('modal-log-activity');
    document.getElementById('form-log-activity').reset();
    // form.reset() only clears inputs that were in the markup — the exercise rows
    // were added at runtime and survive it, so the next workout would open
    // pre-filled with the last one's movements.
    clearExerciseRows('act-exercises');
    handleActivityLoggedResponse(res);
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Log It 💪';
  }
}

export function switchLogTab(which) {
  document.getElementById('log-tab-manual').classList.toggle('on', which === 'manual');
  document.getElementById('log-tab-natural').classList.toggle('on', which === 'natural');
  document.getElementById('form-log-activity').style.display = which === 'manual'  ? 'block' : 'none';
  document.getElementById('form-log-natural').style.display  = which === 'natural' ? 'block' : 'none';
}

export async function handleLogNatural(e) {
  e.preventDefault();
  const btn = document.getElementById('log-natural-btn');
  btn.disabled = true; btn.textContent = 'Parsing…';

  const description = document.getElementById('natural-description').value.trim();

  try {
    const res = await api('/api/activities/natural', {
      method: 'POST',
      body: JSON.stringify({ userId: session.currentUser.id, description })
    });
    closeModal('modal-log-activity');
    document.getElementById('form-log-natural').reset();
    handleActivityLoggedResponse(res);
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Parse & Log 💬';
  }
}

/** Shared success handling for both manual and natural-language activity logging. */
function handleActivityLoggedResponse(res) {
  let msg = `${ACT_ICON[res.type]||'🏃'} Workout logged — ${res.caloriesBurned ?? '?'} cal burned!`;
  if (res.isPersonalBest) msg = `🏆 New personal best! ${res.personalBestMessage || ''}`;
  toast(msg);

  if (res.goalAchievements && res.goalAchievements.length) {
    setTimeout(() => toast('🎉 ' + res.goalAchievements[0]), 1200);
  }
  if (res.muscleRecoveryWarnings && res.muscleRecoveryWarnings.length) {
    setTimeout(() => toast('⚠️ ' + res.muscleRecoveryWarnings[0], true), 2400);
  }
  if (res.exercisePersonalBests && res.exercisePersonalBests.length) {
    setTimeout(() => toast('🏋️ ' + res.exercisePersonalBests[0]), 3600);
  }
  if (res.progressionSuggestion) {
    setTimeout(() => toast('📈 ' + res.progressionSuggestion), 4800);
  }

  session.currentUser.currentStreak = res.currentStreak;
  localStorage.setItem('fp_user', JSON.stringify(session.currentUser));
  document.getElementById('topbar-streak').textContent = res.currentStreak ?? session.currentUser.currentStreak ?? 0;

  const activePanelEl = document.querySelector('.panel.on');
  if (activePanelEl) {
    const activePanel = activePanelEl.id.replace('panel-', '');
    if (panelLoaders[activePanel]) panelLoaders[activePanel]();
  }
}

// ═══════════════════════════════════════════════════════════════════════
// EXERCISES
//
// Sets, reps and load — the half of a workout the app could not represent
// before PLAN P1-1, which is why the only "records" it could hold for weight
// training were longest session and most calories.
// ═══════════════════════════════════════════════════════════════════════

/** Appends one blank movement row to a builder, optionally pre-filled. */
export function addExerciseRow(containerId, exercise) {
  const container = document.getElementById(containerId);
  if (!container) return;

  const e = exercise || {};
  const row = document.createElement('div');
  row.className = 'exrow';
  row.innerHTML = `
    <input type="text"   class="ex-name"  placeholder="Bench Press" value="${escapeHtml(e.name ?? '')}">
    <input type="number" class="ex-sets"  placeholder="3"  min="1" value="${e.sets ?? ''}">
    <input type="number" class="ex-reps"  placeholder="8"  min="1" value="${e.reps ?? ''}">
    <input type="number" class="ex-weight" placeholder="60" min="0" step="0.5" value="${e.weightKg ?? ''}">
    <button type="button" class="exdel" title="Remove">✕</button>`;
  row.querySelector('.exdel').onclick = () => row.remove();
  container.appendChild(row);
}

function clearExerciseRows(containerId) {
  const container = document.getElementById(containerId);
  if (container) container.innerHTML = '';
}

/**
 * Reads a builder back into the shape the API expects.
 *
 * <p>Rows with no movement name are dropped rather than sent: an empty row is what a
 * half-finished "+ Add exercise" click leaves behind, and posting it would create a
 * nameless entry that no record could ever be attached to.
 */
function collectExerciseRows(containerId) {
  const container = document.getElementById(containerId);
  if (!container) return [];

  return Array.from(container.querySelectorAll('.exrow')).map(row => {
    const num = sel => {
      const raw = row.querySelector(sel).value;
      return raw === '' ? null : Number(raw);
    };
    return {
      name: row.querySelector('.ex-name').value.trim(),
      sets: num('.ex-sets'),
      reps: num('.ex-reps'),
      weightKg: num('.ex-weight')
    };
  }).filter(e => e.name);
}

/** "3 x 8 @ 60kg", or "3 x 8" when it is bodyweight. */
function exerciseSummary(e) {
  const scheme = [e.sets, e.reps].every(v => v != null)
    ? `${e.sets} x ${e.reps}`
    : (e.sets != null ? `${e.sets} sets` : (e.reps != null ? `${e.reps} reps` : ''));
  const load = e.weightKg != null ? `@ ${trimNumber(e.weightKg)}kg` : '';
  return [scheme, load].filter(Boolean).join(' ');
}

/** The movements on an activity row. Returns '' for cardio, which has none. */
function exerciseList(exercises) {
  if (!Array.isArray(exercises) || !exercises.length) return '';
  return `<div class="exlist">` + exercises.map(e => `
    <div class="exli">
      <span class="exn">${escapeHtml(e.name || '')}</span>
      <span class="exs">${escapeHtml(exerciseSummary(e))}</span>
      ${e.estimatedOneRepMax != null
        ? `<span class="ex1rm" title="Estimated one-rep max (Epley)">~${trimNumber(e.estimatedOneRepMax)}kg 1RM</span>` : ''}
      ${e.isPersonalBest ? '<span class="expr">PR</span>' : ''}
    </div>`).join('') + `</div>`;
}
