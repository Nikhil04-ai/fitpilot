import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonCards } from '../util.js';
import { toast } from '../ui.js';
import { api } from '../api.js';
import { ACT_ICON } from './activities.js';

// ═══════════════════════════════════════════════════════════════════════
// WORKOUT PLANS
// ═══════════════════════════════════════════════════════════════════════

const DIFFICULTY_BADGE = { BEGINNER: 'bl', INTERMEDIATE: 'bp2', ADVANCED: 'bd' };

function planCard(p) {
  const badgeCls = DIFFICULTY_BADGE[p.difficulty] || 'bn';
  return `
    <div class="chc">
      <span class="cbadge ${badgeCls}">${p.difficulty || ''}</span>
      <div class="ctype">// ${(p.goal || '').toLowerCase().replace('_', ' ')}</div>
      <div class="ctitle">${escapeHtml(p.name)}</div>
      <div style="font-size:13px;color:var(--muted);margin-bottom:16px;line-height:1.7">${escapeHtml(p.description || '')}</div>
      <div class="cf">
        <span class="cdl">${p.totalDays} days</span>
        <button class="cjb" data-action="enroll-plan" data-id="${p.id}">Enroll</button>
      </div>
    </div>`;
}

export async function loadWorkoutPlans() {
  const activeCardEl = document.getElementById('active-plan-card');
  const listEl = document.getElementById('plans-list');
  listEl.innerHTML = skeletonCards(3);

  try {
    const active = await api(`/api/plans/active/${session.currentUser.id}`);
    if (active && active.planName) {
      const today = active.todaysWorkout;
      activeCardEl.innerHTML = `
        <div class="card">
          <h3 class="clbl">Active Plan</h3>
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">
            <div class="pname" style="font-size:18px">${escapeHtml(active.planName)}</div>
            <span class="cdl">Day ${active.currentDay} of ${active.totalDays}</span>
          </div>
          <div class="gb2" style="height:8px;margin-bottom:16px"><div class="gf" style="width:${active.progressPercent || 0}%"></div></div>
          ${today ? `
            <div style="background:var(--panel2);border-radius:12px;padding:16px;margin-bottom:14px">
              <h3 class="clbl" style="margin-bottom:8px">Today's Workout</h3>
              ${today.isRestDay
                ? `<div style="font-size:14px;font-weight:600">😴 Rest Day</div>`
                : `<div style="font-size:14px;font-weight:600">${ACT_ICON[today.activityType]||'🏃'} ${(today.activityType||'').replace('_',' ')} — ${today.durationMinutes} min</div>
                   <div style="font-size:12px;color:var(--muted);margin-top:6px">${escapeHtml(today.instructions||'')}</div>`}
            </div>` : ''}
          ${active.isCompleted
            ? `<div style="text-align:center;color:var(--cyan);font-weight:600;padding:10px">🎉 Plan complete — great work!</div>`
            : `<button class="btn btn-prim btn-block" data-action="complete-plan-day">Mark Today Complete ✓</button>`}
        </div>`;
    } else {
      activeCardEl.innerHTML = '';
    }
  } catch (err) {
    activeCardEl.innerHTML = '';
  }

  try {
    const plans = await api('/api/plans');
    listEl.innerHTML = plans.length ? plans.map(planCard).join('') : emptyState('📋', 'No plans available yet.');
  } catch (err) {
    listEl.innerHTML = `<div class="error-state" style="grid-column:1/-1">Couldn't load plans: ${escapeHtml(err.message)}</div>`;
  }
}

export async function enrollInPlan(planId) {
  try {
    await api(`/api/plans/${planId}/enroll`, { method: 'POST', body: JSON.stringify({ userId: session.currentUser.id }) });
    toast('📋 Enrolled! Check your active plan above.');
    loadWorkoutPlans();
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
}

export async function completeTodayPlan() {
  try {
    const res = await api(`/api/plans/active/${session.currentUser.id}/complete-day`, { method: 'POST' });
    toast('✅ Day complete! ' + (res.isCompleted ? 'Plan finished — congrats! 🎉' : `On to day ${res.currentDay}.`));
    loadWorkoutPlans();
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
}
