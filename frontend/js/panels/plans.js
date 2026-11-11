import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonCards } from '../util.js';
import { confirmDialog, toast } from '../ui.js';
import { api } from '../api.js';

// ═══════════════════════════════════════════════════════════════════════
// WORKOUT PLANS
// ═══════════════════════════════════════════════════════════════════════

/** "WEIGHT_TRAINING" to "Weight training"; the enum used to show through as "// weight loss". */
const WORDS = { HIIT: 'HIIT' };
function words(s) {
  if (!s) return '';
  if (WORDS[s]) return WORDS[s];
  const t = String(s).toLowerCase().replace(/_/g, ' ');
  return t.charAt(0).toUpperCase() + t.slice(1);
}

/** The plan the user is on, so its card can say so and a switch can be confirmed. */
let activePlanName = null;

/**
 * One plan in the list, ruled like a challenge: what it trains for and how hard it is,
 * the title, what it is, then its length and the one thing you can do with it.
 * Only the plan you are on carries the lime, because it is the one in play.
 */
export function planCard(p, isCurrent = false) {
  const action = isCurrent
    ? '<span class="cbadge bl">Active</span>'
    : `<button type="button" class="btn btn-sec btn-sm" data-action="enroll-plan" data-id="${escapeHtml(p.id)}" data-name="${escapeHtml(p.name)}">Enroll</button>`;
  return `
    <article class="chc plan-card">
      <div class="ac-head">
        <span class="ctype">${escapeHtml(words(p.goal))}</span>
        <span class="cbadge">${escapeHtml(words(p.difficulty))}</span>
      </div>
      <h4 class="ctitle">${escapeHtml(p.name)}</h4>
      ${p.description ? `<p class="plan-desc">${escapeHtml(p.description)}</p>` : ''}
      <div class="cf">
        <span class="cdl"><b class="num">${escapeHtml(p.totalDays)}</b> ${p.totalDays === 1 ? 'day' : 'days'}</span>
        ${action}
      </div>
    </article>`;
}

/** Today's workout: the activity and its minutes as the figure, then what to do. */
function todayBlock(today, day) {
  const head = `<h4 class="mini-ttl">Today, day ${escapeHtml(day)}</h4>`;
  if (!today) return '';
  if (today.isRestDay) {
    return `<div class="plan-today">${head}<div class="plan-act">Rest day</div>
      ${today.instructions ? `<p class="plan-ins">${escapeHtml(today.instructions)}</p>` : ''}</div>`;
  }
  return `<div class="plan-today">${head}
    <div class="plan-act">${escapeHtml(words(today.activityType))}<span class="u">${escapeHtml(today.durationMinutes)} min</span></div>
    ${today.instructions ? `<p class="plan-ins">${escapeHtml(today.instructions)}</p>` : ''}</div>`;
}

/**
 * The plan you are on: where you are in it, as a figure and a bar, beside today's
 * workout and the button that ticks it off.
 */
export function activePlanSheet(active) {
  const pct = Math.max(0, Math.min(100, Math.round(active.progressPercent || 0)));
  const done = active.completedDays ?? 0;
  const body = active.isCompleted
    ? `<div class="note">Plan complete. Well done.</div>`
    : `${todayBlock(active.todaysWorkout, active.currentDay)}
       <button type="button" class="btn btn-sec" data-action="complete-plan-day">Mark today complete</button>`;
  return `
    <div class="sheet-head">
      <h3 class="sheet-ttl" id="active-plan-ttl">Active plan</h3>
      <span class="sheet-meta">Day ${escapeHtml(active.currentDay)} of ${escapeHtml(active.totalDays)}</span>
    </div>
    <div class="plan-now">
      <div class="plan-prog">
        <div class="ac-head">
          <span class="ctype">${escapeHtml(words(active.planGoal))}</span>
          <span class="cbadge">${escapeHtml(words(active.planDifficulty))}</span>
        </div>
        <div class="pname">${escapeHtml(active.planName)}</div>
        <div class="git"><span class="gn">${escapeHtml(done)} of ${escapeHtml(active.totalDays)} days done</span><span class="gp">${pct}%</span></div>
        <div class="gb2" role="progressbar" aria-label="Plan progress" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${pct}"><div class="gf${pct >= 100 ? ' done' : ''}" style="width:${pct}%"></div></div>
      </div>
      <div class="plan-do">${body}</div>
    </div>`;
}

export async function loadWorkoutPlans() {
  const activeEl = document.getElementById('active-plan-card');
  const listEl = document.getElementById('plans-list');
  listEl.innerHTML = skeletonCards(3);

  try {
    const active = await api(`/api/plans/active/${session.currentUser.id}`);
    if (active && active.planName) {
      activePlanName = active.planName;
      activeEl.innerHTML = activePlanSheet(active);
      activeEl.hidden = false;
    } else {
      activePlanName = null;
      activeEl.innerHTML = '';
      activeEl.hidden = true;
    }
  } catch (err) {
    activePlanName = null;
    activeEl.innerHTML = '';
    activeEl.hidden = true;
  }

  try {
    const plans = await api('/api/plans');
    listEl.innerHTML = plans.length
      ? plans.map(p => planCard(p, p.name === activePlanName)).join('')
      : emptyState('', 'No plans available yet.');
  } catch (err) {
    listEl.innerHTML = `<div class="error-state">Couldn't load plans: ${escapeHtml(err.message)}</div>`;
  }
}

/**
 * Enrolling ends the plan you are on without a word from the server, so a switch is
 * asked about first. Picking your first plan has nothing to lose and asks nothing.
 */
export async function enrollInPlan(planId, planName) {
  if (activePlanName && !(await confirmDialog({
    title: 'Switch plans?',
    message: `This ends ${activePlanName} and drops your progress in it. You start the new plan at day 1.`,
    confirmLabel: 'Switch plan'
  }))) return;
  try {
    await api(`/api/plans/${planId}/enroll`, { method: 'POST' });
    toast(planName ? `Enrolled in ${planName}. Your plan is at the top.` : 'Enrolled. Your plan is at the top.');
    loadWorkoutPlans();
  } catch (err) {
    toast(err.message, true);
  }
}

export async function completeTodayPlan() {
  try {
    const res = await api(`/api/plans/active/${session.currentUser.id}/complete-day`, { method: 'POST' });
    toast(res.isCompleted ? 'Plan finished. Congratulations.' : `Day complete. On to day ${res.currentDay}.`);
    loadWorkoutPlans();
  } catch (err) {
    toast(err.message, true);
  }
}
