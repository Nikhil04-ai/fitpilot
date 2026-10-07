import { session } from '../state.js';
import { emptyState, escapeHtml, shortDate, skeletonRows } from '../util.js';
import { renderPager, toast } from '../ui.js';
import { api } from '../api.js';
import { nav, panelLoaders } from '../nav.js';

// ═══════════════════════════════════════════════════════════════════════
// NOTIFICATIONS  (PLAN P2-2)
// ═══════════════════════════════════════════════════════════════════════

const NOTIF_ICON = {
  WEEKLY_DIGEST: '📋', PLATEAU: '📉', CHALLENGE_RESULT: '⚔️',
  GOAL_DEADLINE: '🎯', GENERAL: '🔔'
};

let notifPage = 0;

/**
 * Keeps the unread badge current without loading the feed.
 *
 * Two elements show the same number — the bell in the header and the sidebar link —
 * because which one is visible depends on the viewport, and the mobile layout collapses
 * the sidebar behind a menu button.
 */
export async function refreshUnreadCount() {
  try {
    const { unread } = await api('/api/notifications/unread-count');
    const bell = document.getElementById('bell-count');
    const badge = document.getElementById('notif-badge');
    const label = unread > 99 ? '99+' : String(unread);

    if (bell) { bell.textContent = label; bell.hidden = !unread; }
    if (badge) { badge.textContent = label; badge.style.display = unread ? '' : 'none'; }
  } catch (_) {
    // A failed count is not worth a toast — the feed itself still works.
  }
}

/**
 * Notifications are written by the server on its own schedule — the Monday digest, a
 * challenge settling overnight, a friend's request — so a count read once at login went
 * stale for the rest of the session. Polls once a minute while the tab is visible, and
 * catches up the moment a hidden tab is shown again.
 */
const UNREAD_POLL_MS = 60_000;
let unreadPollTimer = null;

export function startUnreadPolling() {
  stopUnreadPolling();
  unreadPollTimer = setInterval(() => {
    if (session.currentUser && !document.hidden) refreshUnreadCount();
  }, UNREAD_POLL_MS);
}

export function stopUnreadPolling() {
  if (unreadPollTimer) clearInterval(unreadPollTimer);
  unreadPollTimer = null;
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && session.currentUser && unreadPollTimer) refreshUnreadCount();
});

export async function loadNotifications(page = 0) {
  notifPage = page;
  const el = document.getElementById('notif-list');
  el.innerHTML = skeletonRows(4);
  try {
    const res = await api(`/api/notifications?page=${page}&size=20`);
    const items = res.content || [];

    el.innerHTML = items.length
      ? items.map(notificationRow).join('')
      : emptyState('🔔', 'Nothing yet. Digests, plateau warnings and challenge results land here.');

    renderPager('notif-pager', page, res.page?.totalPages ?? 1, loadNotifications);
    refreshUnreadCount();
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load notifications: ${escapeHtml(err.message)}</div>`;
  }
}

function notificationRow(n) {
  const icon = NOTIF_ICON[n.type] || '🔔';
  const when = n.createdAt ? shortDate(n.createdAt) : '';

  // Only the id goes on the buttons. Titles and bodies are free text, and the
  // handlers look up what they need by id (AGENT.md §8).
  return `
    <div class="ai ${n.isRead ? '' : 'unread'}">
      <div class="nt-ic">${icon}</div>
      <div class="ai-body">
        <div class="an">${escapeHtml(n.title || '')}</div>
        ${n.body ? `<div class="nt-body">${escapeHtml(n.body)}</div>` : ''}
        <div class="ai-sub"><span>${escapeHtml(when)}</span></div>
      </div>
      <div class="ai-acts">
        ${n.targetPanel ? `<button title="Open" data-action="open-notification" data-id="${n.id}">→</button>` : ''}
        ${n.isRead ? '' : `<button title="Mark read" data-action="mark-notification-read" data-id="${n.id}">✓</button>`}
      </div>
    </div>`;
}

/**
 * Opens what a notification is about, marking it read on the way.
 *
 * The stored value is a panel name, not a URL: the frontend already routes on
 * `#panel` and owns that mapping, so a URL in the database would be a second copy of
 * the routing table quietly going stale.
 */
export async function openNotification(id) {
  const target = (await markNotificationRead(id, { silent: true })) || {};
  if (target.targetPanel && panelLoaders[target.targetPanel]) {
    nav(target.targetPanel);
  }
}

export async function markNotificationRead(id, opts = {}) {
  try {
    const updated = await api(`/api/notifications/${id}/read`, { method: 'POST' });
    if (!opts.silent) loadNotifications(notifPage);
    refreshUnreadCount();
    return updated;
  } catch (err) {
    toast('❌ ' + err.message, true);
    return null;
  }
}

export async function markAllNotificationsRead() {
  const btn = document.getElementById('mark-all-read-btn');
  btn.disabled = true;
  try {
    const { marked } = await api('/api/notifications/read-all', { method: 'POST' });
    toast(marked ? `✅ Marked ${marked} as read` : 'Nothing unread');
    loadNotifications(notifPage);
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false;
  }
}
