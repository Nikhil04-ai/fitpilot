// The Workout Plans page (js/panels/plans.js): the plan card, the active plan, and the
// confirmation before a switch drops the plan you are on.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, jsonResponse, el } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1' }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
document.body.appendChild(
  el('div', { class: 'modal-overlay', id: 'modal-confirm' },
    el('div', { class: 'modal' },
      el('h2', { id: 'confirm-title' }), el('p', { id: 'confirm-message' }),
      el('button', { id: 'confirm-cancel', 'data-action': 'close-modal', 'data-modal': 'modal-confirm' }),
      el('button', { id: 'confirm-ok', class: 'btn btn-danger', 'data-action': 'confirm-accept' }))));
const { planCard, activePlanSheet, enrollInPlan, loadWorkoutPlans } = await import('../js/panels/plans.js');
const ui = await import('../js/ui.js');

const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u;
const plan = (over) => ({ id: 'p1', name: '7-Day Beginner Cardio', description: 'Light cardio every day with one rest day.',
  totalDays: 7, difficulty: 'BEGINNER', goal: 'WEIGHT_LOSS', ...over });
const active = (over) => ({ planName: '7-Day Beginner Cardio', planDifficulty: 'BEGINNER', planGoal: 'ENDURANCE',
  currentDay: 3, totalDays: 7, completedDays: 2, progressPercent: 28.6, isCompleted: false,
  todaysWorkout: { dayNumber: 3, activityType: 'WEIGHT_TRAINING', durationMinutes: 35, instructions: 'Upper body', isRestDay: false }, ...over });

test('a plan card names the goal and the difficulty in words, never as the enum', () => {
  const html = planCard(plan());
  assert.ok(html.includes('class="ctype">Weight loss<'));
  assert.ok(html.includes('class="cbadge">Beginner<'));
  assert.ok(!html.includes('WEIGHT_LOSS') && !html.includes('BEGINNER'));
  assert.ok(!html.includes('//'), 'no code-comment slashes');
});

test('a plan card is a ruled note with its length and an Enroll button that carries the plan', () => {
  const html = planCard(plan());
  assert.ok(html.includes('class="chc plan-card"'));
  assert.ok(html.includes('<b class="num">7</b> days'));
  assert.ok(html.includes('data-action="enroll-plan" data-id="p1" data-name="7-Day Beginner Cardio"'));
  assert.ok(html.includes('btn btn-sec btn-sm'), 'secondary: the topbar holds the only primary');
  assert.ok(!html.includes('style='), 'no inline style');
  assert.ok(!EMOJI.test(html));
});

test('the plan you are on says Active in the lime and offers no Enroll', () => {
  const html = planCard(plan(), true);
  assert.ok(html.includes('cbadge bl">Active<'));
  assert.ok(!html.includes('enroll-plan'));
});

test('a one-day plan says "1 day", and free text is escaped', () => {
  const html = planCard(plan({ totalDays: 1, name: '<img src=x onerror=alert(1)>', description: '<b>x</b>' }));
  assert.ok(html.includes('<b class="num">1</b> day<'));
  assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt;'));
  assert.ok(!html.includes('<img') && !html.includes('<b>x'));
});

test('the active plan shows progress as a percentage, a bar and the days done', () => {
  const html = activePlanSheet(active());
  assert.ok(html.includes('Day 3 of 7'));
  assert.ok(html.includes('2 of 7 days done'));
  assert.ok(html.includes('class="gp">29%<'));
  assert.ok(html.includes('role="progressbar"') && html.includes('aria-valuenow="29"'));
  assert.ok(html.includes('style="width:29%"'));
  assert.ok(!html.includes('gf done'));
});

test('today is the activity and its minutes, with a secondary button to tick it off', () => {
  const html = activePlanSheet(active());
  assert.ok(html.includes('Today, day 3'));
  assert.match(html, /class="plan-act">Weight training<span class="u">35 min<\/span>/);
  assert.ok(html.includes('Upper body'));
  assert.ok(html.includes('class="btn btn-sec" data-action="complete-plan-day">Mark today complete<'));
  assert.ok(!EMOJI.test(html) && !html.includes('✓'));
});

test('a rest day reads as one, with no minutes', () => {
  const html = activePlanSheet(active({ todaysWorkout: { activityType: null, durationMinutes: 0, instructions: 'Rest and recovery', isRestDay: true } }));
  assert.ok(html.includes('class="plan-act">Rest day<'));
  assert.ok(!html.includes('0 min'));
  assert.ok(html.includes('Rest and recovery'));
});

test('a finished plan has a note and no button', () => {
  const html = activePlanSheet(active({ isCompleted: true, completedDays: 7, progressPercent: 100, todaysWorkout: undefined }));
  assert.ok(html.includes('Plan complete. Well done.'));
  assert.ok(!html.includes('complete-plan-day'));
  assert.ok(html.includes('gf done'));
  assert.ok(!EMOJI.test(html));
});

// ── the page, against a fake server ──
let enrolls = 0, activeBody = { message: 'No active plan.' };
globalThis.fetch = async (url, opts = {}) => {
  const u = String(url);
  if (u.includes('/api/plans/active/')) return jsonResponse(200, activeBody);
  if (u.endsWith('/enroll') && opts.method === 'POST') { enrolls++; return jsonResponse(200, {}); }
  if (u.endsWith('/api/plans')) return jsonResponse(200, [plan(), plan({ id: 'p2', name: '14-Day Flexibility Program', goal: 'FLEXIBILITY', totalDays: 14 })]);
  return jsonResponse(404, {});
};

test('with no plan, the active sheet stays hidden and every plan offers Enroll', async () => {
  await loadWorkoutPlans();
  assert.equal(document.getElementById('active-plan-card').hidden, true);
  assert.equal((document.getElementById('plans-list').innerHTML.match(/enroll-plan/g) || []).length, 2);
});

test('first enrolment asks nothing and goes straight through', async () => {
  enrolls = 0;
  await enrollInPlan('p1', '7-Day Beginner Cardio');
  assert.equal(enrolls, 1);
  assert.ok(!document.getElementById('modal-confirm').classList.contains('show'));
});

test('with a plan active, its card says Active and the other still offers Enroll', async () => {
  activeBody = active();
  await loadWorkoutPlans();
  assert.equal(document.getElementById('active-plan-card').hidden, false);
  const list = document.getElementById('plans-list').innerHTML;
  assert.equal((list.match(/cbadge bl">Active</g) || []).length, 1);
  assert.equal((list.match(/enroll-plan/g) || []).length, 1);
  assert.ok(list.includes('data-id="p2"'));
});

test('switching plans asks first, says what is lost, and enrols only after a yes', async () => {
  enrolls = 0;
  const done = enrollInPlan('p2', '14-Day Flexibility Program');
  await new Promise(r => setTimeout(r, 0));
  assert.ok(document.getElementById('modal-confirm').classList.contains('show'), 'the question is open');
  assert.match(document.getElementById('confirm-message').textContent, /7-Day Beginner Cardio.*drops your progress/);
  assert.equal(enrolls, 0, 'nothing is sent while the question is open');
  ui.acceptConfirm();
  await done;
  assert.equal(enrolls, 1);
});

test('a no leaves the plan you are on alone', async () => {
  enrolls = 0;
  const done = enrollInPlan('p2', '14-Day Flexibility Program');
  await new Promise(r => setTimeout(r, 0));
  ui.closeModal('modal-confirm');
  await done;
  assert.equal(enrolls, 0);
});
