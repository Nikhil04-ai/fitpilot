import { session } from '../state.js';
import { emptyState, escapeHtml, figure, skeletonFigures, skeletonLines, skeletonRows } from '../util.js';
import { api } from '../api.js';
import { activityRow, rememberActivities } from './activities.js';
import { goalRow } from './goals.js';
import { loadOverviewAiTeaser } from './coach.js';

// ═══════════════════════════════════════════════════════════════════════
// OVERVIEW
//
// This week in four figures, the log of recent sessions, and beside it the goals
// and the athlete. Every number on it comes from the API.
// ═══════════════════════════════════════════════════════════════════════

export async function loadOverview() {
  await Promise.all([loadOverviewStats(), loadOverviewProfile(), loadOverviewActivities(), loadOverviewGoals(), loadOverviewAiTeaser()]);
}

function sheetHead(title) {
  return `<div class="sheet-head"><h2 class="sheet-ttl">${title}</h2></div>`;
}

/** A body measurement: the figure, then its unit a step smaller. A missing one is a dash. */
function measure(label, value, unit) {
  const known = value != null && value !== '';
  const shown = known ? escapeHtml(String(value)) : '–';
  const u = unit && known ? `<span class="u">${unit}</span>` : '';
  return `<div><dt>${label}</dt><dd>${shown}${u}</dd></div>`;
}

async function loadOverviewStats() {
  const el = document.getElementById('overview-stats');
  el.innerHTML = skeletonFigures(4);
  try {
    // These two don't depend on each other. Awaiting them in series doubled the
    // time to first paint on the panel everyone lands on.
    const [weekly, allTime] = await Promise.all([
      api(`/api/stats/weekly/${session.currentUser.id}`),
      api(`/api/stats/all-time/${session.currentUser.id}`)
    ]);
    // The week leads: what you did these seven days, then the run you're on, then
    // the career.
    el.innerHTML = [
      // "Last 7 days", because that is what /stats/weekly counts. "This week" read as
      // Monday to Sunday, which is how the digest counts a week, so two screens gave
      // different answers to the same question (found 2026-11-16).
      figure(weekly.activityCount ?? 0, 'Workouts, last 7 days'),
      figure(weekly.totalCalories ?? 0, 'Calories, last 7 days'),
      figure(session.currentUser.currentStreak ?? 0, 'Day streak', `Best: ${allTime.longestStreak ?? 0}`),
      figure(allTime.totalWorkouts ?? 0, 'Total workouts')
    ].join('');
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load stats: ${escapeHtml(err.message)}</div>`;
  }
}

async function loadOverviewProfile() {
  const el = document.getElementById('overview-profile');
  el.innerHTML = sheetHead('Athlete profile') + skeletonLines(4);
  try {
    const p = await api(`/api/profile/${session.currentUser.id}`);
    session.currentUser = { ...session.currentUser, ...p };
    localStorage.setItem('fp_user', JSON.stringify(session.currentUser));
    document.getElementById('topbar-streak').textContent = p.currentStreak ?? 0;

    const bmi = (p.weight && p.height) ? (p.weight / ((p.height/100)**2)).toFixed(1) : null;

    // The streak ring this card used to draw repeated the Day streak figure and the
    // topbar's; the streak is shown once now, as a figure.
    el.innerHTML = sheetHead('Athlete profile') + `
      <div class="athlete-name">${escapeHtml([p.firstName, p.lastName].filter(Boolean).join(' '))}</div>
      <div class="athlete-mail">${escapeHtml(p.email || '')}</div>
      <dl class="body-figs">
        ${measure('Weight', p.weight, 'kg')}
        ${measure('Height', p.height, 'cm')}
        ${measure('BMI', bmi)}
        ${measure('Age', p.age)}
      </dl>`;
  } catch (err) {
    el.innerHTML = sheetHead('Athlete profile') + `<div class="error-state">Couldn't load profile: ${escapeHtml(err.message)}</div>`;
  }
}

async function loadOverviewActivities() {
  const el = document.getElementById('overview-activities');
  el.innerHTML = skeletonRows(4);
  try {
    const page = await api('/api/activities?page=0&size=5&sort=startTime,desc');
    const items = page.content || [];
    if (!items.length) {
      // What belongs here, and the one action that fills it.
      el.innerHTML = emptyState('', 'No workouts yet. Log your first one to start your streak.',
        '<button class="btn btn-sec btn-sm" data-action="open-modal" data-modal="modal-log-activity">Log workout</button>');
      return;
    }
    rememberActivities(items);   // so the rows' Edit buttons can open the dialog
    el.innerHTML = items.map(activityRow).join('');
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load activities: ${escapeHtml(err.message)}</div>`;
  }
}

async function loadOverviewGoals() {
  const el = document.getElementById('overview-goals');
  el.innerHTML = sheetHead('Active goals') + skeletonLines(3);
  try {
    const goals = await api(`/api/goals/${session.currentUser.id}`);
    const active = goals.filter(g => !g.isCompleted && !g.isExpired).slice(0, 4);
    el.innerHTML = sheetHead('Active goals') + (active.length
      ? active.map(goalRow).join('')
      : emptyState('', 'No active goals yet.',
          '<button class="btn btn-sec btn-sm" data-action="nav" data-panel="goals">Set a goal</button>'));
  } catch (err) {
    el.innerHTML = sheetHead('Active goals') + `<div class="error-state">Couldn't load goals: ${escapeHtml(err.message)}</div>`;
  }
}
