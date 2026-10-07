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
      : emptyState('🤝', 'No friends yet — add someone by their email address above.');

    inviteEl.innerHTML = invites.length
      ? `<h3 class="clbl">Friend requests</h3><div class="af">${invites.map(inviteRow).join('')}</div>`
      : '';

    populateOpponentPicker();
  } catch (err) {
    listEl.innerHTML = `<div class="error-state">Couldn't load friends: ${escapeHtml(err.message)}</div>`;
  }
}

function friendRow(f) {
  const initial = (f.name || '?')[0].toUpperCase();
  return `
    <div class="ai">
      <div class="fr-av">${escapeHtml(initial)}</div>
      <div class="ai-body">
        <div class="an">${escapeHtml(f.name || 'Athlete')}</div>
        <div class="ai-sub"><span>🔥 ${f.currentStreak ?? 0} day streak</span></div>
      </div>
      <div class="ai-acts">
        <button title="Remove" class="danger" data-action="remove-friend" data-id="${f.friendshipId}">✕</button>
      </div>
    </div>`;
}

function inviteRow(f) {
  const initial = (f.name || '?')[0].toUpperCase();
  return `
    <div class="ai unread">
      <div class="fr-av">${escapeHtml(initial)}</div>
      <div class="ai-body">
        <div class="an">${escapeHtml(f.name || 'Athlete')}</div>
        <div class="ai-sub"><span>wants to connect</span></div>
      </div>
      <div class="ai-acts">
        <button title="Accept" data-action="accept-friend" data-id="${f.friendshipId}">✓</button>
        <button title="Decline" class="danger" data-action="decline-friend" data-id="${f.friendshipId}">✕</button>
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
    : '<option disabled>Add a friend first — you can only challenge friends</option>';
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
    toast('🤝 ' + res.message);
    input.value = '';
    loadFriends();
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = '+ Add friend';
  }
  return false;
}

export async function acceptFriend(friendshipId) {
  try {
    const res = await api(`/api/friends/${friendshipId}/accept`, { method: 'POST' });
    toast('🤝 ' + res.message);
    loadFriends();
    refreshUnreadCount();
  } catch (err) {
    toast('❌ ' + err.message, true);
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
    toast(isDecline ? '👋 Request declined' : '👋 Friend removed');
    loadFriends();
    refreshUnreadCount();
  } catch (err) {
    toast('❌ ' + err.message, true);
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
function challengeCard(c, isInvite) {
  const badgeClass = c.status === 'ACTIVE' ? 'bl' : c.status === 'PENDING' ? 'bn'
                   : c.status === 'COMPLETED' ? 'bp2' : 'bd';

  let actions = '';
  if (isInvite) {
    actions = `
      <button class="btn btn-prim btn-sm" style="flex:1" data-action="respond-challenge" data-id="${c.id}" data-accept="true">Accept</button>
      <button class="btn btn-sec btn-sm" style="flex:1" data-action="respond-challenge" data-id="${c.id}" data-accept="false">Decline</button>`;
  } else if (c.status === 'PENDING') {
    const waiting = pendingCount(c);
    actions = c.youAreCreator
      ? `<span class="cdl">Waiting on ${waiting} ${waiting === 1 ? 'person' : 'people'}…</span><button class="g-del" data-action="cancel-challenge" data-id="${c.id}">Cancel</button>`
      : `<span class="cdl">Waiting for others to accept…</span>`;
  } else if (c.status === 'ACTIVE') {
    const leave = c.youAreCreator ? ''
      : `<button class="g-del" data-action="leave-challenge" data-id="${c.id}">Leave</button>`;
    actions = `<span class="cdl"><span style="color:var(--red)">${c.daysRemaining ?? '?'}</span> days left</span>${leave}<button class="btn-ghost" data-action="challenge-detail" data-id="${c.id}">Details</button>`;
  } else if (c.status === 'COMPLETED') {
    actions = `<span class="cdl">${escapeHtml(c.resultMessage || 'Challenge complete')}</span><button class="btn-ghost" data-action="challenge-detail" data-id="${c.id}">Details</button>`;
  } else if (c.status === 'CANCELLED' && c.resultMessage) {
    // Settled with no contest ("Nobody else joined"). The message says why; the bare
    // status word did not (PLAN P4-14). A challenge its creator cancelled has no message.
    actions = `<span class="cdl">${escapeHtml(c.resultMessage)}</span>`;
  } else {
    actions = `<span class="cdl">${escapeHtml(c.status || '')}</span>`;
  }

  return `
    <div class="chc">
      <span class="cbadge ${badgeClass}">${escapeHtml(c.status || '')}</span>
      <div class="ctype">// ${escapeHtml((c.type || '').toLowerCase())}</div>
      <div class="ctitle">${escapeHtml(c.title || 'Challenge')}</div>
      ${standingsList(c)}
      <div class="cf">${actions}</div>
    </div>`;
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

  return `<div class="std">${rows.map(p => {
    const competing = p.status === 'ACCEPTED';
    const classes = ['stdr'];
    if (p.isYou) classes.push('me');
    if (!competing) classes.push('out');
    if (competing && p.position === 1 && c.status !== 'PENDING') classes.push('lead');

    const label = competing
      ? (p.score ?? 0)
      : p.status === 'INVITED' ? 'invited' : 'declined';

    return `
      <div class="${classes.join(' ')}">
        <span class="stdp">${competing && p.position ? '#' + p.position : '—'}</span>
        <span class="stdn">${escapeHtml(p.isYou ? 'You' : (p.name || 'Athlete'))}${p.isCreator ? ' ·' : ''}</span>
        <span class="stds">${escapeHtml(String(label))}</span>
      </div>`;
  }).join('')}</div>`;
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
    toast('👋 You left the challenge.');
    loadChallenges();
  } catch (err) {
    toast('❌ ' + err.message, true);
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
      : emptyState('⚔️', 'No challenges yet — invite a friend to compete!');
  } catch (err) {
    chList.innerHTML = `<div class="error-state" style="grid-column:1/-1">Couldn't load challenges: ${escapeHtml(err.message)}</div>`;
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
    toast('⚔️ Challenge sent!');
    loadChallenges();
  } catch (err) {
    toast('❌ ' + err.message, true);
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
    toast(accept ? '✅ Challenge accepted! Game on.' : '👋 Challenge declined.');
    loadChallenges();
  } catch (err) {
    toast('❌ ' + err.message, true);
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
    toast('🗑 Challenge cancelled.');
    loadChallenges();
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
}

/** Shows score history + all-time head-to-head record for a specific challenge. */
export async function openChallengeDetail(challengeId) {
  document.getElementById('cd-title').textContent = 'Loading…';
  document.getElementById('cd-content').innerHTML = `<div class="loading-state"><div class="spinner"></div></div>`;
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

    let html = `<h3 class="clbl">Standings</h3>${standingsList(c)}`;

    if (leaderboard.length) {
      html += `<h3 class="clbl">Score History</h3><div class="rl" style="margin-bottom:20px">` +
        leaderboard.map(l => {
          // The snapshot carries everyone now, not two columns (PLAN P2-4).
          const line = (l.standings || [])
            .map(row => `${escapeHtml(row.name || 'Athlete')}: ${row.score ?? 0}`)
            .join(' · ');
          return `
          <div class="ri">
            <div class="rmu">${new Date(l.date).toLocaleDateString('en',{day:'numeric',month:'short'})}</div>
            <div class="rt2">${line || '—'}</div>
          </div>`;
        }).join('') + `</div>`;
    }

    if (h2h) {
      html += `
        <h3 class="clbl">Head-to-Head — All Time vs ${escapeHtml(soleOpponent.name || 'Opponent')}</h3>
        <div class="pstats" style="grid-template-columns:repeat(3,1fr)">
          <div class="ps"><div class="psv">${h2h.userAWins ?? 0}</div><div class="psk">Your wins</div></div>
          <div class="ps"><div class="psv">${h2h.draws ?? 0}</div><div class="psk">Draws</div></div>
          <div class="ps"><div class="psv">${h2h.userBWins ?? 0}</div><div class="psk">Their wins</div></div>
        </div>`;
    }
    document.getElementById('cd-content').innerHTML = html;
  } catch (err) {
    document.getElementById('cd-title').textContent = 'Challenge';
    document.getElementById('cd-content').innerHTML = `<div class="error-state">${escapeHtml(err.message)}</div>`;
  }
}
