import { session } from './state.js';
import { toggleSidebar } from './ui.js';
import { loadOverview } from './panels/overview.js';
import { loadActivities } from './panels/activities.js';
import { loadGoals } from './panels/goals.js';
import { loadNutrition } from './panels/nutrition.js';
import { loadPersonalBests } from './panels/bests.js';
import { loadRecommendations } from './panels/coach.js';
import { loadPainLog } from './panels/pain.js';
import { loadWorkoutPlans } from './panels/plans.js';
import { loadInsights } from './panels/insights.js';
import { loadChallenges } from './panels/social.js';
import { loadRecovery } from './panels/recovery.js';
import { loadHealth } from './panels/health.js';
import { loadNotifications } from './panels/notifications.js';
import { loadProfile } from './panels/profile.js';

// ═══════════════════════════════════════════════════════════════════════
// NAVIGATION
// ═══════════════════════════════════════════════════════════════════════

export const panelLoaders = {
  overview:   loadOverview,
  activities: loadActivities,
  nutrition:  loadNutrition,
  goals:      loadGoals,
  notifications: loadNotifications,
  bests:      loadPersonalBests,
  ai:         loadRecommendations,
  pain:       loadPainLog,
  plans:      loadWorkoutPlans,
  insights:   loadInsights,
  challenges: loadChallenges,
  recovery:   loadRecovery,
  health:     loadHealth,
  profile:    loadProfile
};
const panelTitles = {
  overview: 'Overview', activities: 'Activities', nutrition: 'Nutrition', goals: 'Goals',
  notifications: 'Notifications',
  bests: 'Personal Bests', ai: 'AI Coach', pain: 'Pain Log', plans: 'Workout Plans',
  insights: 'Stats & Insights', challenges: 'Challenges', recovery: 'Muscle Recovery',
  health: 'Health Tracking', profile: 'Profile Settings'
};

// ── Panel load scheduling ─────────────────────────────────────────────
// nav() used to fire its loader unconditionally on every click, with no cache and
// no de-duplication. Clicking through the sidebar quickly stacked several identical
// request sets on top of each other. Two guards fix that:
//   - panelInFlight: a second nav() to a panel already loading is a no-op.
//   - panelLoadedAt: a revisit within PANEL_TTL reuses what's on screen.
// Both are keyed by panel and both are bypassed by calling a loader directly, which
// is what every mutation path already does (logging a workout, deleting a goal), so
// freshly written data is never served from this cache.
const PANEL_TTL = 30000;
const panelInFlight = {};
const panelLoadedAt = {};
let currentPanel = null;

/** Forces the next visit to a panel to refetch. Call after a write that affects it. */
export function invalidatePanel(panel) { delete panelLoadedAt[panel]; }

/** Every panel refetches on its next visit. Driven from api() on any successful write. */
export function invalidateAllPanels() { Object.keys(panelLoadedAt).forEach(k => delete panelLoadedAt[k]); }

/**
 * Drops the panel freshness cache. Without this, logging out and back in as
 * someone else could show the previous account's panels for up to PANEL_TTL,
 * because nav() would decide they were still fresh.
 */
export function resetPanelCache() {
  Object.keys(panelLoadedAt).forEach(k => delete panelLoadedAt[k]);
  Object.keys(panelInFlight).forEach(k => delete panelInFlight[k]);
  currentPanel = null;
}

export function panelFromHash() {
  const h = (location.hash || '').replace(/^#\/?/, '');
  return panelLoaders[h] ? h : null;
}

export function nav(panel, opts = {}) {
  if (!panelLoaders[panel]) panel = 'overview';

  document.querySelectorAll('.sb-link').forEach(l => {
    const on = l.dataset.panel === panel;
    l.classList.toggle('on', on);
    // aria-current is what tells a screen reader which section is showing; the
    // .on class is purely visual and says nothing to assistive tech.
    if (on) l.setAttribute('aria-current', 'page'); else l.removeAttribute('aria-current');
  });
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('on'));
  document.getElementById('panel-' + panel).classList.add('on');
  document.getElementById('topbar-ttl').textContent = panelTitles[panel];
  toggleSidebar(false);

  // Keep the URL in step so refresh, back/forward and bookmarks all work. Guarded
  // against re-entry: writing the hash fires hashchange, which calls nav() again.
  const wanted = '#' + panel;
  if (!opts.fromHash && location.hash !== wanted) {
    history.pushState(null, '', wanted);
  }

  currentPanel = panel;
  schedulePanelLoad(panel, opts.force);
}

function schedulePanelLoad(panel, force) {
  const loader = panelLoaders[panel];
  if (!loader) return;
  if (panelInFlight[panel]) return;
  if (!force && Date.now() - (panelLoadedAt[panel] || 0) < PANEL_TTL) return;

  panelInFlight[panel] = true;
  Promise.resolve()
    .then(loader)
    // A loader that throws still has to clear its flag, or the panel is stuck
    // refusing to reload for the rest of the session. Loaders paint their own
    // error states, so there is nothing to report here.
    .catch(() => {})
    .finally(() => {
      delete panelInFlight[panel];
      panelLoadedAt[panel] = Date.now();
    });
}

// Back/forward buttons and manually edited hashes.
window.addEventListener('hashchange', () => {
  if (!session.currentUser) return;
  const panel = panelFromHash() || 'overview';
  if (panel !== currentPanel) nav(panel, { fromHash: true });
});
window.addEventListener('popstate', () => {
  if (!session.currentUser) return;
  const panel = panelFromHash() || 'overview';
  if (panel !== currentPanel) nav(panel, { fromHash: true });
});
