import { session } from '../state.js';
import { emptyState, escapeHtml, initials, skeletonLines, skeletonRows, skeletonStats } from '../util.js';
import { api } from '../api.js';
import { activityRow } from './activities.js';
import { goalRow } from './goals.js';
import { loadOverviewAiTeaser } from './coach.js';

// ═══════════════════════════════════════════════════════════════════════
// OVERVIEW
// ═══════════════════════════════════════════════════════════════════════

export async function loadOverview() {
  await Promise.all([loadOverviewStats(), loadOverviewProfile(), loadOverviewActivities(), loadOverviewGoals(), loadOverviewAiTeaser()]);
}

async function loadOverviewStats() {
  const el = document.getElementById('overview-stats');
  el.innerHTML = skeletonStats(4);
  try {
    // These two don't depend on each other. Awaiting them in series doubled the
    // time to first paint on the panel everyone lands on.
    const [weekly, allTime] = await Promise.all([
      api(`/api/stats/weekly/${session.currentUser.id}`),
      api(`/api/stats/all-time/${session.currentUser.id}`)
    ]);
    el.innerHTML = `
      <div class="card smc"><div class="smi b">🔥</div><div><div class="smv">${weekly.totalCalories ?? 0}</div><div class="smk">Calories this week</div></div></div>
      <div class="card smc"><div class="smi c">⚡</div><div><div class="smv">${session.currentUser.currentStreak ?? 0}</div><div class="smk">Day streak</div><div class="smd">Best: ${allTime.longestStreak ?? 0}</div></div></div>
      <div class="card smc"><div class="smi g">🏋️</div><div><div class="smv">${weekly.activityCount ?? 0}</div><div class="smk">Workouts this week</div></div></div>
      <div class="card smc"><div class="smi p">💪</div><div><div class="smv">${allTime.totalWorkouts ?? 0}</div><div class="smk">Total workouts</div></div></div>
    `;
  } catch (err) {
    el.innerHTML = `<div class="error-state" style="grid-column:1/-1">Couldn't load stats: ${escapeHtml(err.message)}</div>`;
  }
}

async function loadOverviewProfile() {
  const el = document.getElementById('overview-profile');
  el.innerHTML = skeletonLines(4);
  try {
    const p = await api(`/api/profile/${session.currentUser.id}`);
    session.currentUser = { ...session.currentUser, ...p };
    localStorage.setItem('fp_user', JSON.stringify(session.currentUser));
    document.getElementById('topbar-streak').textContent = p.currentStreak ?? 0;

    const bmi = (p.weight && p.height) ? (p.weight / ((p.height/100)**2)).toFixed(1) : '—';
    const streakPct = Math.min(100, ((p.currentStreak||0) / Math.max(p.longestStreak||1,1)) * 100);
    const circumference = 2 * Math.PI * 28;
    const offset = circumference - (streakPct/100)*circumference;

    el.innerHTML = `
      <h3 class="clbl">Athlete Profile</h3>
      <div class="av-lg">${escapeHtml(initials(p.firstName, p.lastName))}</div>
      <div class="pname">${escapeHtml(p.firstName||'')} ${escapeHtml(p.lastName||'')}</div>
      <div class="phandle">${escapeHtml(p.email||'')}</div>
      <div class="pstats">
        <div class="ps"><div class="psv">${p.weight ?? '—'}</div><div class="psk">kg</div></div>
        <div class="ps"><div class="psv">${p.height ?? '—'}</div><div class="psk">cm</div></div>
        <div class="ps"><div class="psv">${bmi}</div><div class="psk">BMI</div></div>
        <div class="ps"><div class="psv">${p.age ?? '—'}</div><div class="psk">Age</div></div>
      </div>
      <div class="sring">
        <div class="srw">
          <svg width="70" height="70" viewBox="0 0 70 70">
            <circle cx="35" cy="35" r="28" fill="none" stroke="rgba(14,165,255,.1)" stroke-width="6"/>
            <circle cx="35" cy="35" r="28" fill="none" stroke="url(#sr)" stroke-width="6"
              stroke-dasharray="${circumference}" stroke-dashoffset="${offset}" stroke-linecap="round"/>
            <defs><linearGradient id="sr" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stop-color="#0EA5FF"/><stop offset="100%" stop-color="#00FFD1"/>
            </linearGradient></defs>
          </svg>
          <div class="srn">${p.currentStreak ?? 0}</div>
        </div>
        <div><div class="srt">Day Streak 🔥</div><div class="srs">Best: ${p.longestStreak ?? 0} days</div></div>
      </div>
    `;
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load profile: ${escapeHtml(err.message)}</div>`;
  }
}

async function loadOverviewActivities() {
  const el = document.getElementById('overview-activities');
  el.innerHTML = skeletonRows(4);
  try {
    const page = await api('/api/activities?page=0&size=5&sort=startTime,desc');
    const items = page.content || [];
    if (!items.length) {
      el.innerHTML = emptyState('🏃', 'No workouts yet — log your first one!');
      return;
    }
    el.innerHTML = items.map(activityRow).join('');
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load activities: ${escapeHtml(err.message)}</div>`;
  }
}

async function loadOverviewGoals() {
  const el = document.getElementById('overview-goals');
  el.innerHTML = `<h3 class="clbl">Active Goals</h3>` + skeletonLines(3);
  try {
    const goals = await api(`/api/goals/${session.currentUser.id}`);
    const active = goals.filter(g => !g.isCompleted && !g.isExpired).slice(0, 4);
    el.innerHTML = `<h3 class="clbl">Active Goals</h3>` + (active.length
      ? active.map(goalRow).join('')
      : emptyState('🎯', 'No active goals — set one on the Goals page.'));
  } catch (err) {
    el.innerHTML = `<h3 class="clbl">Active Goals</h3><div class="error-state">Couldn't load goals: ${escapeHtml(err.message)}</div>`;
  }
}
