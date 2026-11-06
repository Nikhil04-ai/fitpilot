// Friends and challenges on the Challenges page (js/panels/social.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1' }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { friendRow, inviteRow, challengeCard, scoreHistoryTable } = await import('../js/panels/social.js');

const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u;

test('friend and request rows act in words and say whom they act on', () => {
  const f = friendRow({ name: 'Ravi', currentStreak: 4, friendshipId: 'f1' });
  assert.ok(!EMOJI.test(f), f);
  assert.match(f, /aria-label="Remove Ravi">Remove</);
  assert.match(f, />4 day streak</);
  const r = inviteRow({ name: 'Mona', friendshipId: 'f2' });
  assert.match(r, /aria-label="Accept Mona">Accept</);
  assert.match(r, /aria-label="Decline Mona">Decline</);
  assert.match(r, /class="ai person unread"/);
});

test('a friend name is escaped wherever it lands, including the aria-label', () => {
  const f = friendRow({ name: '"><img src=x>', friendshipId: 'f1' });
  assert.ok(!f.includes('<img'));
  assert.ok(f.includes('&quot;&gt;&lt;img'));
});

const challenge = (over) => ({ id: 'c1', type: 'CALORIES_BURNED', status: 'ACTIVE', title: 'Week burn',
  daysRemaining: 3, youAreCreator: true, participants: [
    { name: 'Asha', isYou: true, isCreator: true, status: 'ACCEPTED', position: 1, score: 900 },
    { name: 'Ravi', status: 'ACCEPTED', position: 2, score: 700 },
    { name: 'Mona', status: 'INVITED' }
  ], ...over });

test('a challenge says what it is scored on and its status in words', () => {
  const html = challengeCard(challenge(), false);
  assert.match(html, />Most calories burned</);
  assert.match(html, /class="cbadge bl">Active</);
  assert.ok(!html.includes('// '), 'no code-style enum');
  assert.ok(!EMOJI.test(html));
  assert.ok(!html.includes('style='));
});

test('the standings name the host in words and leave an unranked place empty, not a dash', () => {
  const html = challengeCard(challenge(), false);
  assert.match(html, /You <span class="std-tag">host<\/span>/);
  assert.ok(!html.includes('—'));
  assert.ok(!/ ·</.test(html));
  assert.match(html, /<li class="stdr out">\s*<span class="stdp"><\/span>/);
});

test('only an active challenge carries the live badge', () => {
  assert.match(challengeCard(challenge({ status: 'PENDING' }), false), /class="cbadge bn">Pending</);
  assert.match(challengeCard(challenge({ status: 'COMPLETED', resultMessage: 'You won' }), false), /class="cbadge bp2">Completed</);
});

test('the score history is a table: a row per day, a column per person, blanks where someone has no score', () => {
  const html = scoreHistoryTable([
    { date: '2026-11-01', standings: [{ name: 'Asha', score: 300 }, { name: 'Ravi', score: 200 }] },
    { date: '2026-11-02', standings: [{ name: 'Asha', score: 650 }] }
  ]);
  assert.match(html, /<th scope="col">Day<\/th><th scope="col" class="num">Asha<\/th><th scope="col" class="num">Ravi<\/th>/);
  assert.equal((html.match(/<tr>/g) || []).length, 3, 'a head row and two days');
  assert.match(html, /<td class="num">650<\/td><td class="num"><\/td>/);
  assert.ok(!html.includes(' · '));
});
