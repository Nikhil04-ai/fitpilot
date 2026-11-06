import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonCards, skeletonRows } from '../util.js';
import { closeModal, confirmDialog, openModal, toast } from '../ui.js';
import { api } from '../api.js';
import { refreshUnreadCount } from './notifications.js';

// ═══════════════════════════════════════════════════════════════════════
// FRIENDS  (PLAN P2-4)
// ═══════════════════════════════════════════════════════════════════════

/** Accepted friends, cached so the challenge modal can offer them by name. */
let friendsCache = [];

async function loadFriends() {
  const listEl = document.getElementById('friends-list');
  const inviteEl = document.getElementById('friend-invites');
  if (!listEl) return;
  listEl.innerHTML = skeletonRows(2);

  try {
    const [friends, invites] = await Promise.all([
      api('/api/friends'),
      api('/api/friends/invites').catch(() => [])
    ]);

    friendsCache = friends;
    listEl.innerHTML = friends.length
      ? friends.map(friendRow).join('')
      : emptyState('', 'No friends yet. Add someone by their email address above.');

    inviteEl.innerHTML = invites.length
      ? `<h4 class="mini-ttl">Friend requests</h4><div class="af">${invites.map(inviteRow).join('')}</div>`
      : '';

    populateOpponentPicker();
  } catch (err) {
    listEl.innerHTML = `<div class="error-state">Couldn't load friends: ${escapeHtml(err.message)}</div>`;
  }
}

/** A friend: their initial, their name and streak, and the one action on them. */
export function friendRow(f) {
  const name = f.name || 'Athlete';
  return `
    <div class="ai person">
      <div class="fr-av" aria-hidden="true">${escapeHtml(name[0].toUpperCase())}</div>
      <div class="ai-body">
        <div class="person-name">${escapeHtml(name)}</div>
        <div class="ai-sub"><span>${f.currentStreak ?? 0} day streak</span></div>
      </div>
      <div class="ai-acts">
        <button type="button" class="danger" data-action="remove-friend" data-id="${f.friendshipId}" aria-label="Remove ${escapeHtml(name)}">Remove</button>
      </div>
    </div>`;
}

/** A friend request waiting on you: marked unread until you answer it. */
export function inviteRow(f) {
  const name = f.name || 'Athlete';
  return `
    <div class="ai person unread">
      <div class="fr-av" aria-hidden="true">${escapeHtml(name[0].toUpperCase())}</div>
      <div class="ai-body">
        <div class="person-name">${escapeHtml(name)}</div>
        <div class="ai-sub"><span>wants to connect</span></div>
      </div>
      <div class="ai-acts">
        <button type="button" data-action="accept-friend" data-id="${f.friendshipId}" aria-label="Accept ${escapeHtml(name)}">Accept</button>
        <button type="button" class="danger" data-action="decline-friend" data-id="${f.friendshipId}" aria-label="Decline ${escapeHtml(name)}">Decline</button>
      </div>
    </div>`;
}

/** Fills the challenge modal's picker, so nobody has to paste a UUID any more. */
function populateOpponentPicker() {
  const select = document.getElementById('ch-opponents');
  if (!select) return;
  select.innerHTML = friendsCache.length
    ? friendsCache.map(f =>
        `<option value="${escapeHtml(f.userId)}">${escapeHtml(f.name || 'Athlete')}</option>`).join('')
    : '<option disabled>Add a friend first: you can only challenge friends</option>';
}

export async function handleAddFriend(e) {
  e.preventDefault();
  const btn = document.getElementById('add-friend-btn');
  const input = document.getElementById('friend-email');
  btn.disabled = true; btn.textContent = 'Sending…';

  try {
    const res = await api('/api/friends/request', {
      method: 'POST', body: JSON.stringify({ email: input.value.trim() })
    });
    // The backend answers identically for a known and an unknown address on purpose —
    // otherwise this is a free "does this person have an account" lookup. The message
    // it returns is the one to show, rather than a cheerier one this page invents.
    toast(res.message);
    input.value = '';
    loadFriends();
  } catch (err) {
    toast(err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Add friend';
  }
  return false;
}

export async function acceptFriend(friendshipId) {
  try {
    const res = await api(`/api/friends/${friendshipId}/accept`, { method: 'POST' });
    toast(res.message);
    loadFriends();
    refreshUnreadCount();
  } catch (err) {
    toast(err.message, true);
  }
}

export async function removeFriend(friendshipId, isDecline = false) {
  if (!isDecline && !(await confirmDialog({
    title: 'Remove friend?',
    message: 'You can add them again later.',
    confirmLabel: 'Remove'
  }))) return;
  try {
    await api(`/api/friends/${friendshipId}`, { method: 'DELETE' });
    toast(isDecline ? 'Request declined' : 'Friend removed');
    loadFriends();
    refreshUnreadCount();
  } catch (err) {
    toast(err.message, true);
  }
}

// ═══════════════════════════════════════════════════════════════════════
// CHALLENGES
// ═══════════════════════════════════════════════════════════════════════

/**
 * One challenge card, with ranked standings rather than a two-avatar "VS" block.
 *
 * The old layout put "you" on the left and "them" on the right, which was not a style
 * choice — it was the data model showing through, because a challenge held exactly two
 * people (PLAN P2-4). A ranked list reads correctly for two and for eight.
 */
/** What a challenge is scored on, in words. The enum used to show through as "// calories_burned". */
const CHALLENGE_TYPE_LABEL = {
  CALORIES_BURNED: 'Most calories burned', WORKOUT_COUNT: 'Most workouts logged',
  TOTAL_DURATION: 'Most minutes trained', STREAK_LENGTH: 'Longest streak'
};
/** The status, in sentence case. Only a live challenge carries the lime: it is the one in play. */
const STATUS_META = {
  ACTIVE: { label: 'Active', cls: 'bl' }, PENDING: { label: 'Pending', cls: 'bn' },
  COMPLETED: { label: 'Completed', cls: 'bp2' }, CANCELLED: { label: 'Cancelled', cls: 'bd' }
};

export function challengeCard(c, isInvite) {
  const status = STATUS_META[c.status] || { label: c.status || '', cls: 'bd' };

  let actions = '';
  if (isInvite) {
    actions = `
      <button type="button" class="btn btn-sec btn-sm" data-action="respond-challenge" data-id="${c.id}" data-accept="true">Accept</button>
      <button type="button" class="g-del" data-action="respond-challenge" data-id="${c.id}" data-accept="false">Decline</button>`;
  } else if (c.status === 'PENDING') {
    const waiting = pendingCount(c);
    actions = c.youAreCreator
      ? `<span class="cdl">Waiting on ${waiting} ${waiting === 1 ? 'person' : 'people'}…</span><button type="button" class="g-del" data-action="cancel-challenge" data-id="${c.id}">Cancel</button>`
      : `<span class="cdl">Waiting for others to accept…</span>`;
  } else if (c.status === 'ACTIVE') {
    const leave = c.youAreCreator ? ''
      : `<button type="button" class="g-del" data-action="leave-challenge" data-id="${c.id}">Leave</button>`;
    const days = c.daysRemaining ?? '?';
    actions = `<span class="cdl"><b class="num">${days}</b> ${days === 1 ? 'day' : 'days'} left</span>${leave}<button type="button" class="btn-ghost" data-action="challenge-detail" data-id="${c.id}">Details</button>`;
  } else if (c.status === 'COMPLETED') {
    actions = `<span class="cdl">${escapeHtml(c.resultMessage || 'Challenge complete')}</span><button type="button" class="btn-ghost" data-action="challenge-detail" data-id="${c.id}">Details</button>`;
  } else if (c.status === 'CANCELLED' && c.resultMessage) {
    // Settled with no contest ("Nobody else joined"). The message says why; the bare
    // status word did not (PLAN P4-14). A challenge its creator cancelled has no message.
    actions = `<span class="cdl">${escapeHtml(c.resultMessage)}</span>`;
  } else {
    actions = `<span class="cdl">${escapeHtml(status.label)}</span>`;
  }

  return `
    <article class="chc">
      <div class="ac-head">
        <span class="ctype">${escapeHtml(CHALLENGE_TYPE_LABEL[c.type] || (c.type || '').toLowerCase())}</span>
        <span class="cbadge ${status.cls}">${escapeHtml(status.label)}</span>
      </div>
      <h4 class="ctitle">${escapeHtml(c.title || 'Challenge')}</h4>
      ${standingsList(c)}
      <div class="cf">${actions}</div>
    </article>`;
}

/**
 * The score history as a table: a row per day, a column per person. It used to be one
 * line per day of "Name: 120 · Name: 90", which made reading one person's progress
 * down the days a hunt. The snapshot carries everyone, not two columns (PLAN P2-4).
 */
export function scoreHistoryTable(leaderboard) {
  const names = [];
  leaderboard.forEach(l => (l.standings || []).forEach(r => {
    const n = r.name || 'Athlete';
    if (!names.includes(n)) names.push(n);
  }));
  const day = d => new Date(d).toLocaleDateString('en', { day: 'numeric', month: 'short' });
  return `<div class="tbl-wrap"><table class="tbl">
    <thead><tr><th scope="col">Day</th>${names.map(n => `<th scope="col" class="num">${escapeHtml(n)}</th>`).join('')}</tr></thead>
    <tbody>${leaderboard.map(l => `<tr><th scope="row">${escapeHtml(day(l.date))}</th>${names.map(n => {
      const row = (l.standings || []).find(r => (r.name || 'Athlete') === n);
      return `<td class="num">${row ? escapeHtml(String(row.score ?? 0)) : ''}</td>`;
    }).join('')}</tr>`).join('')}</tbody>
  </table></div>`;
}

/** How many invitations are still unanswered. */
function pendingCount(c) {
  return (c.participants || []).filter(p => p.status === 'INVITED').length;
}

/**
 * The standings.
 *
 * Non-competitors are shown greyed out with their status rather than ranked last on
 * zero — an unanswered invite is a person who hasn't replied, not somebody losing.
 */
function standingsList(c) {
  const rows = c.participants || [];
  if (!rows.length) return '';

  // A ranked list: position, name, score. "host" says who started it, where a trailing
  // middle dot used to, which nobody could read.
  return `<ol class="std">${rows.map(p => {
    const competing = p.status === 'ACCEPTED';
    const classes = ['stdr'];
    if (p.isYou) classes.push('me');
    if (!competing) classes.push('out');
    if (competing && p.position === 1 && c.status !== 'PENDING') classes.push('lead');

    const label = competing
      ? (p.score ?? 0)
      : p.status === 'INVITED' ? 'invited' : 'declined';

    return `
      <li class="${classes.join(' ')}">
        <span class="stdp">${competing && p.position ? p.position : ''}</span>
        <span class="stdn">${escapeHtml(p.isYou ? 'You' : (p.name || 'Athlete'))}${p.isCreator ? ' <span class="std-tag">host</span>' : ''}</span>
        <span class="stds">${escapeHtml(String(label))}</span>
      </li>`;
  }).join('')}</ol>`;
}

export async function leaveChallenge(id) {
  const ok = await confirmDialog({
    title: 'Leave challenge?',
    message: 'The others carry on without you.',
    confirmLabel: 'Leave'
  });
  if (!ok) return;
  try {
    await api(`/api/challenges/${id}/leave`, { method: 'POST' });
    toast('You left the challenge.');
    loadChallenges();
  } catch (err) {
    toast(err.message, true);
  }
}

export async function loadChallenges() {
  // Friends are part of this panel now: a challenge is issued by picking a name
  // rather than pasting a UUID (PLAN P2-4).
  loadFriends();

  const invSec = document.getElementById('invites-section');
  const invList = document.getElementById('invites-list');
  const chList = document.getElementById('challenges-list');
  chList.innerHTML = skeletonCards(2);

  try {
    const invites = await api(`/api/challenges/invites/${session.currentUser.id}`);
    if (invites.length) {
      invSec.style.display = 'block';
      invList.innerHTML = invites.map(c => challengeCard(c, true)).join('');
      document.getElementById('invite-badge').style.display = 'inline-block';
      document.getElementById('invite-badge').textContent = invites.length;
    } else {
      invSec.style.display = 'none';
      document.getElementById('invite-badge').style.display = 'none';
    }
  } catch (err) {
    invSec.style.display = 'none';
  }

  try {
    const all = await api(`/api/challenges/user/${session.currentUser.id}`);
    // Only challenges you are in. An unanswered invite is already in the invites
    // section above — once somebody else accepts, a group challenge turns ACTIVE while
    // you are still INVITED, so filtering on the challenge's status alone listed it
    // twice. A challenge you declined or left is no longer yours to act on.
    const mine = all.filter(c => c.yourStatus === 'ACCEPTED');
    chList.innerHTML = mine.length
      ? mine.map(c => challengeCard(c, false)).join('')
      : emptyState('', 'No challenges yet. Invite a friend to compete.',
          '<button type="button" class="btn btn-sec btn-sm" data-action="open-modal" data-modal="modal-create-challenge">New challenge</button>');
  } catch (err) {
    chList.innerHTML = `<div class="error-state">Couldn't load challenges: ${escapeHtml(err.message)}</div>`;
  }
}

export async function handleCreateChallenge(e) {
  e.preventDefault();
  const btn = document.getElementById('create-challenge-btn');
  btn.disabled = true; btn.textContent = 'Sending…';
  const picked = Array.from(document.getElementById('ch-opponents').selectedOptions)
    .map(o => o.value)
    .filter(Boolean);

  if (!picked.length) {
    toast('Pick at least one friend to challenge.', true);
    btn.disabled = false; btn.textContent = 'Send Challenge';
    return false;
  }

  const payload = {
    creatorId: session.currentUser.id,
    opponentIds: picked,
    type: document.getElementById('ch-type').value,
    title: document.getElementById('ch-title').value.trim() || null,
    durationDays: parseInt(document.getElementById('ch-days').value) || 7
  };
  try {
    await api('/api/challenges', { method: 'POST', body: JSON.stringify(payload) });
    closeModal('modal-create-challenge');
    document.getElementById('form-create-challenge').reset();
    toast('Challenge sent');
    loadChallenges();
  } catch (err) {
    toast(err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Send Challenge';
  }
}

export async function respondChallenge(id, accept) {
  try {
    await api(`/api/challenges/${id}/respond`, {
      method: 'POST',
      body: JSON.stringify({ userId: session.currentUser.id, accept })
    });
    toast(accept ? 'Challenge accepted. Game on.' : 'Challenge declined.');
    loadChallenges();
  } catch (err) {
    toast(err.message, true);
  }
}

export async function cancelChallenge(id) {
  // "Keep it" rather than the default "Cancel", which next to "Cancel challenge"
  // would leave two buttons that both read as the same thing.
  const ok = await confirmDialog({
    title: 'Cancel challenge?',
    message: 'Both sides lose the scores logged so far.',
    confirmLabel: 'Cancel challenge',
    cancelLabel: 'Keep it'
  });
  if (!ok) return;
  try {
    await api(`/api/challenges/${id}/cancel?userId=${session.currentUser.id}`, { method: 'DELETE' });
    toast('Challenge cancelled.');
    loadChallenges();
  } catch (err) {
    toast(err.message, true);
  }
}

/** Shows score history + all-time head-to-head record for a specific challenge. */
export async function openChallengeDetail(challengeId) {
  document.getElementById('cd-title').textContent = 'Loading…';
  document.getElementById('cd-content').innerHTML = `<div class="loading-state">Loading…</div>`;
  openModal('modal-challenge-detail');

  try {
    const c = await api(`/api/challenges/${challengeId}?viewingUserId=${session.currentUser.id}`);
    document.getElementById('cd-title').textContent = c.title || 'Challenge';

    // Head-to-head still means "the two of us", so it only makes sense when there is
    // exactly one other competitor. In a group it is replaced by the standings.
    const others = (c.participants || []).filter(p => !p.isYou && p.status === 'ACCEPTED');
    const soleOpponent = others.length === 1 ? others[0] : null;

    const [leaderboard, h2h] = await Promise.all([
      api(`/api/challenges/${challengeId}/leaderboard`).catch(() => []),
      soleOpponent
        ? api(`/api/challenges/h2h/${session.currentUser.id}/${soleOpponent.userId}`).catch(() => null)
        : Promise.resolve(null)
    ]);

    let html = `<h3 class="mini-ttl">Standings</h3>${standingsList(c)}`;

    if (leaderboard.length) {
      html += `<h3 class="mini-ttl">Score history</h3>${scoreHistoryTable(leaderboard)}`;
    }

    if (h2h) {
      html += `
        <h3 class="mini-ttl">All-time head-to-head with ${escapeHtml(soleOpponent.name || 'your opponent')}</h3>
        <dl class="body-figs h2h-figs">
          <div><dt>Your wins</dt><dd>${h2h.userAWins ?? 0}</dd></div>
          <div><dt>Draws</dt><dd>${h2h.draws ?? 0}</dd></div>
          <div><dt>Their wins</dt><dd>${h2h.userBWins ?? 0}</dd></div>
        </dl>`;
    }
    document.getElementById('cd-content').innerHTML = html;
  } catch (err) {
    document.getElementById('cd-title').textContent = 'Challenge';
    document.getElementById('cd-content').innerHTML = `<div class="error-state">${escapeHtml(err.message)}</div>`;
  }
}
