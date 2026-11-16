import { session } from '../state.js';
import { emptyState, escapeHtml, shortDate, skeletonRows } from '../util.js';
import { renderPager, toast } from '../ui.js';
import { api } from '../api.js';
import { nav, panelLoaders } from '../nav.js';

// ═══════════════════════════════════════════════════════════════════════
// NOTIFICATIONS  (PLAN P2-2)
// ═══════════════════════════════════════════════════════════════════════

// What each kind of notification is about, in words. A general one is not about anything in
// particular, so it carries no label; neither does a kind this page doesn't know yet.
const KIND_LABEL = {
  WEEKLY_DIGEST: 'Weekly digest', PLATEAU: 'Plateau', CHALLENGE_RESULT: 'Challenge result',
  GOAL_DEADLINE: 'Goal deadline'
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
    const count = document.getElementById('notif-count');
    const label = unread > 99 ? '99+' : String(unread);

    if (bell) { bell.textContent = label; bell.hidden = !unread; }
    // The button's aria-label overrides its content, so the count shown in red was never
    // part of its accessible name: a screen reader heard "Notifications" either way
    // (found 2026-11-16). The name says the count when there is one.
    const bellBtn = document.getElementById('bell-btn');
    if (bellBtn) bellBtn.setAttribute('aria-label', unread ? `Notifications, ${label} unread` : 'Notifications');
    if (badge) { badge.textContent = label; badge.hidden = !unread; }
    // The page's own count, in words; nothing is said when nothing is waiting.
    if (count) count.textContent = unread ? `${label} unread` : '';
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
      : emptyState('', 'Nothing yet. Digests, plateau warnings and challenge results land here.');

    renderPager('notif-pager', page, res.page?.totalPages ?? 1, loadNotifications);
    refreshUnreadCount();
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load notifications: ${escapeHtml(err.message)}</div>`;
    document.getElementById('notif-pager').innerHTML = '';
  }
}

/**
 * One notification, as a row of the feed: when, then what it says, then what you can do.
 *
 * Unread is a paper step and a heavier title (`.ai.unread`), and is also said in words to a
 * screen reader, so it never rests on the weight alone. The buttons are named for their
 * notification, as the workout rows' are. Only the id goes in the data attributes: titles and
 * bodies are free text, and the handlers look up what they need by id (AGENT.md §8).
 */
export function notificationRow(n) {
  const when = n.createdAt ? shortDate(n.createdAt) : '';
  const kind = KIND_LABEL[n.type] || '';
  const named = escapeHtml(n.title || 'notification');
  return `
    <div class="ai logrow nt${n.isRead ? '' : ' unread'}">
      <div class="ai-when">${escapeHtml(when)}</div>
      <div class="ai-body">
        <div class="nt-ttl">${n.isRead ? '' : '<span class="visually-hidden">Unread. </span>'}${escapeHtml(n.title || '')}</div>
        ${n.body ? `<div class="ai-note">${escapeHtml(n.body)}</div>` : ''}
        ${kind ? `<div class="ai-sub">${escapeHtml(kind)}</div>` : ''}
      </div>
      <div class="ai-acts">
        ${n.targetPanel ? `<button type="button" data-action="open-notification" data-id="${n.id}" aria-label="Open: ${named}">Open</button>` : ''}
        ${n.isRead ? '' : `<button type="button" data-action="mark-notification-read" data-id="${n.id}" aria-label="Mark read: ${named}">Mark read</button>`}
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
    toast(err.message, true);
    return null;
  }
}

export async function markAllNotificationsRead() {
  const btn = document.getElementById('mark-all-read-btn');
  btn.disabled = true;
  try {
    const { marked } = await api('/api/notifications/read-all', { method: 'POST' });
    toast(marked ? `Marked ${marked} as read` : 'Nothing unread');
    loadNotifications(notifPage);
  } catch (err) {
    toast(err.message, true);
  } finally {
    btn.disabled = false;
  }
}
