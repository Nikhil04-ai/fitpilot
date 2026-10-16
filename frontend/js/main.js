// ═══════════════════════════════════════════════════════════════════════
// FitPilot — frontend entry point
//
// Native ES modules, loaded straight by the browser: no bundler, no build step.
//   config.js   which backend to call (API_BASE)
//   state.js    the session: tokens and the signed-in user
//   api.js      api() — every backend call goes through it (AGENT.md §8)
//   auth.js     login, register, password reset, logout, enterApp()
//   nav.js      panel switching, load de-duplication, #panel deep links
//   ui.js       toast, modals, confirm dialog, sidebar, pager
//   actions.js  the delegated click/submit/change listeners (data-action etc.)
//   wake.js     the "server is waking up" notice for a sleeping Render instance
//   charts.js   Chart.js helpers shared by the panels
//   util.js     escapeHtml, dates, skeletons
//   panels/     one module per sidebar panel
// This file boots the session and maps every data-action name to its handler
// (bottom).
// ═══════════════════════════════════════════════════════════════════════

import { session } from './state.js';
import { acceptConfirm, closeModal, hideToast, installLogoFallback, openModal, toggleSidebar } from './ui.js';
import { installActionDelegation, registerActions } from './actions.js';
import { api, forceSessionExpired } from './api.js';
import { consumeResetLink, doLogout, enterApp, handleForgotPassword, handleLogin, handleRegister, handleResetPassword, switchAuthTab } from './auth.js';
import { nav } from './nav.js';
import { addExerciseRow, deleteActivity, handleEditActivity, handleLogActivity, handleLogNatural, openEditActivity, rateActivity, switchLogTab } from './panels/activities.js';
import { deleteGoal, goalTargetHint, handleCreateGoal, handleEditGoal, openCreateGoal, openEditGoal } from './panels/goals.js';
import { deleteMeal, handleEditMeal, handleLogMeal, handleLogMealNatural, openEditMeal, switchMealTab } from './panels/nutrition.js';
import { generateDigestNow, generateRecommendationNow } from './panels/coach.js';
import { handleLogPain } from './panels/pain.js';
import { completeTodayPlan, enrollInPlan } from './panels/plans.js';
import { checkPlateau } from './panels/insights.js';
import { acceptFriend, cancelChallenge, handleAddFriend, handleCreateChallenge, leaveChallenge, openChallengeDetail, removeFriend, respondChallenge } from './panels/social.js';
import { fillSleepHours, handleLogSleep, logWater, openLogSleep } from './panels/health.js';
import { markAllNotificationsRead, markNotificationRead, openNotification } from './panels/notifications.js';
import { downloadExport, handleChangePassword, handleUpdateProfile } from './panels/profile.js';

// ═══════════════════════════════════════════════════════════════════════
// BOOT
// ═══════════════════════════════════════════════════════════════════════

window.addEventListener('DOMContentLoaded', async () => {
  // A reset link takes precedence over any saved session: the person is here to
  // set a new password, and the reset is about to revoke that session anyway.
  if (consumeResetLink()) return;

  if (!session.accessToken || !session.currentUser) return;

  // Validate the saved session actually still exists in the database before
  // trusting it. This catches the case where the browser has an old login
  // saved from before the database was reset/recreated — without this check,
  // the app would silently try to use a dead user id and every write (like
  // logging a workout) would fail with a confusing database error.
  try {
    await api(`/api/profile/${session.currentUser.id}`);
    enterApp();
  } catch (err) {
    // api() already self-heals on the 409 "no longer exists" case, and its message
    // says exactly what happened — don't overwrite it with this vaguer one.
    if (session.expiredInProgress) return;
    // Everything else that can fail at boot: a 401 that survived a refresh, or the
    // backend being unreachable. Either way the saved session can't be trusted.
    forceSessionExpired('Your saved session is no longer valid. Please log in again.');
  }
});

// ── Actions ───────────────────────────────────────────────────────────
// Every data-action / data-submit / data-change name used in index.html and in
// the HTML the panels build, mapped to what it does (see actions.js). Arguments
// come from the element's own data-* attributes. An unregistered name logs a
// console error when used, and tests/markup.test.mjs fails on one before that.

registerActions('click', {
  // ui
  'open-modal':     el => openModal(el.dataset.modal),
  'close-modal':    el => closeModal(el.dataset.modal),
  'confirm-accept': () => acceptConfirm(),
  'hide-toast':     () => hideToast(),
  'toggle-sidebar': () => toggleSidebar(),
  // auth
  'auth-tab':       el => switchAuthTab(el.dataset.tab),
  'logout':         () => doLogout(),
  // nav
  'nav':            el => nav(el.dataset.panel),
  // panels/activities
  'log-tab':         el => switchLogTab(el.dataset.tab),
  'add-exercise':    el => addExerciseRow(el.dataset.target),
  'rate-activity':   el => rateActivity(el.dataset.id, Number(el.dataset.rating)),
  'edit-activity':   el => openEditActivity(el.dataset.id),
  'delete-activity': el => deleteActivity(el.dataset.id),
  // panels/goals
  'create-goal':    () => openCreateGoal(),
  'edit-goal':      el => openEditGoal(el.dataset.id, el.dataset.target, el.dataset.deadline),
  'delete-goal':    el => deleteGoal(el.dataset.id),
  // panels/nutrition
  'meal-tab':       el => switchMealTab(el.dataset.tab),
  'edit-meal':      el => openEditMeal(el.dataset.id),
  'delete-meal':    el => deleteMeal(el.dataset.id),
  // panels/coach
  'generate-digest':  () => generateDigestNow(),
  'generate-insight': () => generateRecommendationNow(),
  // panels/plans
  'enroll-plan':       el => enrollInPlan(el.dataset.id),
  'complete-plan-day': () => completeTodayPlan(),
  // panels/insights
  'check-plateau':  () => checkPlateau(),
  // panels/social
  'accept-friend':     el => acceptFriend(el.dataset.id),
  'remove-friend':     el => removeFriend(el.dataset.id),
  'decline-friend':    el => removeFriend(el.dataset.id, true),
  'respond-challenge': el => respondChallenge(el.dataset.id, el.dataset.accept === 'true'),
  'cancel-challenge':  el => cancelChallenge(el.dataset.id),
  'leave-challenge':   el => leaveChallenge(el.dataset.id),
  'challenge-detail':  el => openChallengeDetail(el.dataset.id),
  // panels/health
  'log-water':      () => logWater(),
  'open-log-sleep': () => openLogSleep(),
  // panels/notifications
  'open-notification':      el => openNotification(el.dataset.id),
  'mark-notification-read': el => markNotificationRead(el.dataset.id),
  'mark-all-read':          () => markAllNotificationsRead(),
  // panels/profile
  'export':         el => downloadExport(el.dataset.format)
});

// Submit handlers take the event, as they always have, and call preventDefault().
registerActions('submit', {
  'login':           (_, e) => handleLogin(e),
  'register':        (_, e) => handleRegister(e),
  'forgot-password': (_, e) => handleForgotPassword(e),
  'reset-password':  (_, e) => handleResetPassword(e),
  'log-activity':    (_, e) => handleLogActivity(e),
  'log-natural':     (_, e) => handleLogNatural(e),
  'edit-activity':   (_, e) => handleEditActivity(e),
  'log-pain':        (_, e) => handleLogPain(e),
  'log-sleep':       (_, e) => handleLogSleep(e),
  'create-goal':     (_, e) => handleCreateGoal(e),
  'edit-goal':       (_, e) => handleEditGoal(e),
  'log-meal':        (_, e) => handleLogMeal(e),
  'log-meal-natural': (_, e) => handleLogMealNatural(e),
  'edit-meal':       (_, e) => handleEditMeal(e),
  'add-friend':      (_, e) => handleAddFriend(e),
  'create-challenge': (_, e) => handleCreateChallenge(e),
  'update-profile':  (_, e) => handleUpdateProfile(e),
  'change-password': (_, e) => handleChangePassword(e)
});

registerActions('change', {
  'goal-target-hint': () => goalTargetHint(),
  'sleep-times':      () => fillSleepHours()
});

installActionDelegation(document);

// Were inline onerror attributes. Modules run after parsing, so the elements exist.
installLogoFallback('auth-logo-img', 'auth-logo-fb');
installLogoFallback('sb-logo-img', 'sb-logo-fb');
