import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonRows, trimNumber } from '../util.js';
import { closeModal, confirmDialog, openModal, toast } from '../ui.js';
import { api } from '../api.js';

// ═══════════════════════════════════════════════════════════════════════
// GOALS
// ═══════════════════════════════════════════════════════════════════════

const GOAL_LABEL = {
  CALORIES_PER_WEEK: '🔥 Calories', WORKOUTS_PER_WEEK: '💪 Workouts', DURATION_PER_WEEK: '⏱ Duration (min)',
  // PLAN P2-1 — the intake side. A budget and a body-weight target are scored
  // differently from the three above, which goalRow() reads off g.direction.
  CALORIE_INTAKE_PER_DAY: '🍽 Calorie budget', PROTEIN_PER_DAY: '🥩 Protein', TARGET_WEIGHT: '⚖️ Body weight'
};

/** Placeholder guidance per goal type — 2000 kcal/day and 3000 kcal/week look alike. */
export function goalTargetHint() {
  const type = document.getElementById('goal-type').value;
  const input = document.getElementById('goal-target');
  const hint = document.getElementById('goal-target-hint');
  const guide = {
    CALORIES_PER_WEEK:      ['3000', 'Calories burned across the whole week.'],
    WORKOUTS_PER_WEEK:      ['4', 'Sessions per week.'],
    DURATION_PER_WEEK:      ['150', 'Minutes per week. The usual guideline is 150.'],
    CALORIE_INTAKE_PER_DAY: ['2000', 'A daily ceiling — you are on track while you stay under it, so this is never marked complete.'],
    PROTEIN_PER_DAY:        ['140', 'Grams per day. Roughly 1.6–2.2 g per kg of bodyweight if you are training.'],
    TARGET_WEIGHT:          ['72', 'The weight you are heading for, in kg. Progress is measured from your current weight and tracked from the scale, not from what you eat.']
  }[type] || ['', ''];
  if (input) input.placeholder = 'e.g. ' + guide[0];
  if (hint) hint.textContent = guide[1];
}

/**
 * One goal, rendered according to how it is actually scored.
 *
 * Three shapes share this row, and flattening them into one would misreport two of
 * them (PLAN P2-1):
 *
 *   AT_LEAST  reach the target      — the original bar, completes at 100%.
 *   AT_MOST   stay under the target — a budget. Never "complete"; turns red when blown.
 *   TOWARDS   move from A to B      — body weight. Measured from startValue, not zero,
 *                                     because 76/72 as a plain ratio is 105% and means
 *                                     nothing.
 */
export function goalRow(g) {
  const pct = Math.min(100, g.progressPercent || 0);
  const deadlineAttr = g.deadline || '';
  const unit = g.unit ? ' ' + g.unit : '';
  // A deadline used to be stored and never checked, so a goal missed months ago still
  // showed as active and in progress (PLAN.md P0-7).
  const expired = !!g.isExpired;
  const due = g.daysRemaining;
  const budget = g.direction === 'AT_MOST';
  const towards = g.direction === 'TOWARDS';

  let deadlineNote = '';
  if (expired) {
    deadlineNote = '<span class="g-exp">Expired</span>';
  } else if (!g.isCompleted && due != null && due <= 7) {
    deadlineNote = `<span class="g-exp" style="color:var(--gold);background:rgba(255,193,7,.12)">${
      due === 0 ? 'Due today' : due === 1 ? '1 day left' : due + ' days left'}</span>`;
  }

  // The status line. Each direction needs the number its own reader is looking for:
  // how much budget is left, how far there is still to go, or how much is done.
  let status;
  if (budget) {
    status = g.isExceeded
      ? `<span style="color:var(--red)">${trimNumber(g.currentValue ?? 0)} / ${trimNumber(g.targetValue)}${unit} — over by ${trimNumber(g.exceededBy || 0)}</span>`
      : `${trimNumber(g.currentValue ?? 0)} / ${trimNumber(g.targetValue)}${unit} — ${trimNumber(g.remaining || 0)} left`;
  } else if (towards) {
    const from = g.startValue != null ? `from ${trimNumber(g.startValue)}` : 'no starting weight yet';
    status = `${trimNumber(g.currentValue ?? 0)} → ${trimNumber(g.targetValue)}${unit} (${escapeHtml(from)})`
           + (g.isCompleted ? ' ✓ Reached' : expired ? ' — never reached' : '');
  } else {
    status = `${trimNumber(g.currentValue ?? 0)} / ${trimNumber(g.targetValue)}${unit} `
           + (g.isCompleted ? '✓ Complete' : expired ? '— never reached' : '');
  }

  // A blown budget is not a finished goal, so it must not get the "done" gradient.
  const fillClass = g.isExceeded ? 'over' : (g.isCompleted ? 'done' : '');
  const periodNote = g.period === 'DAILY' ? '<span class="ai-chip">today</span>'
                   : g.period === 'WEEKLY' ? '<span class="ai-chip">this week</span>' : '';

  return `
    <div class="gi" style="${expired ? 'opacity:.55' : ''}">
      <div class="git">
        <span class="gn">${GOAL_LABEL[g.type] || g.type} — ${trimNumber(g.targetValue)}${unit} ${periodNote} ${deadlineNote}</span>
        <span class="gp">${pct.toFixed(0)}%</span>
      </div>
      <div class="gb2"><div class="gf ${fillClass}" style="width:${pct}%"></div></div>
      <div class="g-meta">
        <span>${status}</span>
        <span>
          <button class="g-del" data-action="edit-goal" data-id="${g.id}" data-target="${g.targetValue}" data-deadline="${escapeHtml(deadlineAttr)}">Edit</button>
          <button class="g-del" data-action="delete-goal" data-id="${g.id}">Delete</button>
        </span>
      </div>
    </div>`;
}

/** Opens the create-goal modal with the hint already matching the default type. */
export function openCreateGoal() {
  openModal('modal-create-goal');
  goalTargetHint();
}

export async function loadGoals() {
  const el = document.getElementById('goals-list');
  el.innerHTML = skeletonRows(3);
  try {
    const goals = await api(`/api/goals/${session.currentUser.id}`);
    el.innerHTML = goals.length ? goals.map(goalRow).join('') : emptyState('🎯', 'No goals yet — create your first target!');
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load goals: ${escapeHtml(err.message)}</div>`;
  }
}

export async function handleCreateGoal(e) {
  e.preventDefault();
  const btn = document.getElementById('create-goal-btn');
  btn.disabled = true; btn.textContent = 'Creating…';
  const payload = {
    userId: session.currentUser.id,
    type: document.getElementById('goal-type').value,
    targetValue: parseFloat(document.getElementById('goal-target').value),
    deadline: document.getElementById('goal-deadline').value || null
  };
  try {
    await api('/api/goals', { method: 'POST', body: JSON.stringify(payload) });
    closeModal('modal-create-goal');
    document.getElementById('form-create-goal').reset();
    toast('🎯 Goal created!');
    loadGoals();
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Create Goal';
  }
}

export function openEditGoal(id, targetValue, deadline) {
  document.getElementById('edit-goal-id').value = id;
  document.getElementById('edit-goal-target').value = targetValue;
  document.getElementById('edit-goal-deadline').value = deadline || '';
  openModal('modal-edit-goal');
}

export async function handleEditGoal(e) {
  e.preventDefault();
  const btn = document.getElementById('edit-goal-btn');
  btn.disabled = true; btn.textContent = 'Saving…';
  const id = document.getElementById('edit-goal-id').value;
  const payload = {
    targetValue: parseFloat(document.getElementById('edit-goal-target').value) || null,
    deadline: document.getElementById('edit-goal-deadline').value || null
  };
  try {
    await api(`/api/goals/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
    closeModal('modal-edit-goal');
    toast('✅ Goal updated!');
    loadGoals();
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Save Changes';
  }
}

export async function deleteGoal(id) {
  // Deleting a workout already confirmed; deleting a goal didn't, so a single
  // stray click destroyed a target and its accumulated progress with no undo.
  const ok = await confirmDialog({
    title: 'Delete goal?',
    message: 'Its progress will be discarded and this cannot be undone.',
    confirmLabel: 'Delete'
  });
  if (!ok) return;
  try {
    await api(`/api/goals/${id}`, { method: 'DELETE' });
    toast('🗑 Goal removed');
    loadGoals();
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
}
