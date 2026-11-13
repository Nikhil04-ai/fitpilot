// The notification row and the feed page (js/panels/notifications.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, jsonResponse, el } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1' }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { notificationRow, loadNotifications, refreshUnreadCount, markNotificationRead, markAllNotificationsRead } =
  await import('../js/panels/notifications.js');

const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}✓]/u;
const note = (over) => ({ id: 'n1', type: 'WEEKLY_DIGEST', title: 'Your week is ready', body: 'You trained 4 times.',
  isRead: false, targetPanel: 'ai', createdAt: '2026-11-09T08:00:00', ...over });

test('a notification is a log row: when, its title and text, its kind in words, its actions', () => {
  const html = notificationRow(note());
  assert.ok(html.includes('class="ai logrow nt unread"'));
  assert.ok(html.includes('class="ai-when"'));
  assert.ok(html.includes('class="nt-ttl"'));
  assert.ok(html.includes('Your week is ready'));
  assert.ok(html.includes('class="ai-note">You trained 4 times.<'));
  assert.ok(html.includes('class="ai-sub">Weekly digest<'));
});

test('the kind is named for each type, and a general or unknown one carries no label', () => {
  const kinds = { WEEKLY_DIGEST: 'Weekly digest', PLATEAU: 'Plateau', CHALLENGE_RESULT: 'Challenge result', GOAL_DEADLINE: 'Goal deadline' };
  for (const [type, word] of Object.entries(kinds)) assert.ok(notificationRow(note({ type })).includes(`class="ai-sub">${word}<`), type);
  for (const type of ['GENERAL', 'SOMETHING_NEW', undefined]) assert.ok(!notificationRow(note({ type })).includes('ai-sub'), String(type));
});

test('unread is a class, a bold title, and words for a screen reader; read is none of them', () => {
  const unread = notificationRow(note());
  assert.ok(unread.includes('<span class="visually-hidden">Unread. </span>'));
  assert.ok(unread.includes('data-action="mark-notification-read"'));
  const read = notificationRow(note({ isRead: true }));
  assert.ok(!read.includes('unread') && !read.includes('Unread') && !read.includes('mark-notification-read'));
  assert.ok(read.includes('class="ai logrow nt"'));
});

test('Open is offered only when there is somewhere to go, and each button names its notification', () => {
  const both = notificationRow(note());
  assert.ok(both.includes('data-action="open-notification" data-id="n1" aria-label="Open: Your week is ready">Open<'));
  assert.ok(both.includes('data-id="n1" aria-label="Mark read: Your week is ready">Mark read<'));
  assert.ok(!notificationRow(note({ targetPanel: null })).includes('open-notification'));
  assert.ok(!notificationRow(note({ targetPanel: null, isRead: true })).includes('<button'));
});

test('no emoji, arrow, tick or inline style; no body leaves no empty note; free text is escaped', () => {
  for (const html of [notificationRow(note()), notificationRow(note({ body: null, isRead: true }))]) {
    assert.ok(!EMOJI.test(html) && !html.includes('→') && !html.includes('style=') && !html.includes('title="'), html);
  }
  assert.ok(!notificationRow(note({ body: null })).includes('ai-note'));
  const evil = notificationRow(note({ title: '<img src=x onerror=alert(1)>', body: '<b>hi</b>' }));
  assert.ok(evil.includes('&lt;img src=x onerror=alert(1)&gt;') && evil.includes('&lt;b&gt;hi&lt;/b&gt;'));
  assert.ok(!evil.includes('<img') && !evil.includes('<b>'));
  assert.ok(evil.includes('aria-label="Open: &lt;img'), 'the label is escaped too');
});

// ── the page, against a fake server ──
let unread = 3, items = [note(), note({ id: 'n2', isRead: true, targetPanel: null, type: 'GENERAL' })], posts = [], fail = false;
globalThis.fetch = async (url, opts = {}) => {
  const u = String(url);
  if (opts.method === 'POST') {
    posts.push(u);
    if (fail) return jsonResponse(500, { message: 'Server said no' });
    if (u.endsWith('/read-all')) { unread = 0; return jsonResponse(200, { marked: 3 }); }
    unread = Math.max(0, unread - 1);
    return jsonResponse(200, { id: 'n1', targetPanel: 'ai' });
  }
  if (u.includes('/api/notifications/unread-count')) return jsonResponse(200, { unread });
  if (u.includes('/api/notifications?')) return jsonResponse(200, { content: items, page: { totalPages: 1, totalElements: items.length } });
  return jsonResponse(404, {});
};

test('the feed lists the rows, and the count, bell and sidebar badge all say 3 unread', async () => {
  await loadNotifications();
  await new Promise(r => setTimeout(r, 0));
  const list = document.getElementById('notif-list').innerHTML;
  assert.equal((list.match(/class="ai logrow nt/g) || []).length, 2);
  assert.equal(document.getElementById('notif-count').textContent, '3 unread');
  assert.equal(document.getElementById('bell-count').textContent, '3');
  assert.equal(document.getElementById('bell-count').hidden, false);
  assert.equal(document.getElementById('notif-badge').textContent, '3');
  assert.equal(document.getElementById('notif-badge').hidden, false);
  assert.equal(document.getElementById('notif-badge').style.display, undefined, 'hidden by attribute, not by inline style');
});

test('nothing unread hides the bell and the badge and says nothing in the count', async () => {
  unread = 0;
  await refreshUnreadCount();
  assert.equal(document.getElementById('bell-count').hidden, true);
  assert.equal(document.getElementById('notif-badge').hidden, true);
  assert.equal(document.getElementById('notif-count').textContent, '');
  unread = 150;
  await refreshUnreadCount();
  assert.equal(document.getElementById('bell-count').textContent, '99+');
  assert.equal(document.getElementById('notif-count').textContent, '99+ unread');
});

test('an empty feed is one sentence with no icon', async () => {
  items = []; unread = 0;
  await loadNotifications();
  const html = document.getElementById('notif-list').innerHTML;
  assert.ok(html.includes('Nothing yet. Digests, plateau warnings and challenge results land here.'));
  assert.ok(!html.includes('es-icon') && !EMOJI.test(html));
});

test('marking one read posts to its id and brings the count down; a failure shows the message plain', async () => {
  items = [note()]; unread = 3; posts = []; fail = false;
  await markNotificationRead('n1');
  assert.equal(posts.length, 1);
  assert.ok(posts[0].endsWith('/api/notifications/n1/read'), posts[0]);
  await new Promise(r => setTimeout(r, 0));
  assert.equal(document.getElementById('notif-count').textContent, '2 unread');

  fail = true;
  assert.equal(await markNotificationRead('n1', { silent: true }), null);
  const msg = document.getElementById('tmsg').textContent;
  assert.match(msg, /Server said no/);
  assert.ok(!EMOJI.test(msg) && !msg.includes('❌'), msg);
});

test('mark all read reports how many it marked, without a tick', async () => {
  fail = false; unread = 3; items = [note()];
  await markAllNotificationsRead();
  assert.equal(document.getElementById('tmsg').textContent, 'Marked 3 as read');
  assert.ok(!EMOJI.test(document.getElementById('tmsg').textContent));
  assert.equal(document.getElementById('mark-all-read-btn').disabled, false);
});
