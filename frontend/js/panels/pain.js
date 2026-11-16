import { session } from '../state.js';
import { emptyState, escapeHtml, shortDate, skeletonRows } from '../util.js';
import { closeModal, openModal, renderPager, toast } from '../ui.js';
import { api } from '../api.js';

// ═══════════════════════════════════════════════════════════════════════
// PAIN LOG — Feature 6
// ═══════════════════════════════════════════════════════════════════════

const BODY_PART_LABEL = {
  LEFT_KNEE:'Left knee', RIGHT_KNEE:'Right knee', LEFT_SHOULDER:'Left shoulder', RIGHT_SHOULDER:'Right shoulder',
  LOWER_BACK:'Lower back', UPPER_BACK:'Upper back', LEFT_ANKLE:'Left ankle', RIGHT_ANKLE:'Right ankle',
  NECK:'Neck', HIPS:'Hips', OTHER:'Other'
};
// The words the Log Pain form offers for each step of the 1 to 5 scale.
const SEVERITY_WORD = { 1:'mild', 2:'noticeable', 3:'moderate', 4:'significant', 5:'severe' };

/** "Weight training, Mon 16 Nov, 07:30": a workout named well enough to pick from a list. */
export function workoutName(a) {
  if (!a) return '';
  const type = a.type === 'HIIT' ? 'HIIT'
    : String(a.type || 'Workout').toLowerCase().replace(/_/g, ' ').replace(/^./, c => c.toUpperCase());
  const when = a.startTime ? shortDate(a.startTime) : '';
  return [type, when].filter(Boolean).join(', ');
}

/**
 * One entry, as a row of the log: when, where, then how bad.
 *
 * Severity is a figure and a word rather than a coloured dot, so it can be read down
 * a column and a dot's colour is not the only thing saying it. Its name is visually
 * hidden until the row stacks, as on the meal rows, and always read by a screen reader.
 */
export function painRow(p) {
  const when = p.loggedAt ? shortDate(p.loggedAt) : '';
  const part = BODY_PART_LABEL[p.bodyPart] || p.bodyPart || 'Unspecified';
  const word = SEVERITY_WORD[p.severity];
  return `
    <div class="ai logrow pain">
      <div class="ai-when">${escapeHtml(when)}</div>
      <div class="ai-body">
        <div class="an">${escapeHtml(part)}</div>
        ${p.description ? `<div class="ai-note">${escapeHtml(p.description)}</div>` : ''}
        ${p.activity ? `<div class="ai-note">During ${escapeHtml(workoutName(p.activity))}</div>` : ''}
      </div>
      <div class="am"><span><span class="k">Severity </span>${escapeHtml(p.severity)} <span class="u">of 5${word ? ', ' + word : ''}</span></span></div>
    </div>`;
}

/**
 * The recurring-pain alerts, as one warm band. They are the server's sentences, so
 * they are shown as written; a warning is the one thing on this page that earns colour.
 */
export function patternsNote(patterns) {
  if (!patterns || !patterns.length) return '';
  return `<div class="note note-warn"><strong>Pattern detected.</strong><ul class="ac-list">${patterns.map(p => `<li>${escapeHtml(p)}</li>`).join('')}</ul></div>`;
}

export async function loadPainLog(page = 0) {
  const warnEl = document.getElementById('pain-warnings');
  const listEl = document.getElementById('pain-list');
  const count = document.getElementById('pain-count');
  listEl.innerHTML = skeletonRows(4);

  try {
    const patterns = await api(`/api/pain-log/patterns/${session.currentUser.id}`);
    warnEl.innerHTML = patternsNote(patterns);
  } catch (err) {
    warnEl.innerHTML = '';
  }

  try {
    // Paged and already newest first — the whole history used to arrive in one
    // response and be sorted here, growing with every entry (PLAN P3-3).
    const res = await api(`/api/pain-log/${session.currentUser.id}?page=${page}&size=15`);
    const rows = res.content || [];
    const total = res.page?.totalElements;
    if (count) count.textContent = total ? `${total} ${total === 1 ? 'entry' : 'entries'}` : '';
    listEl.innerHTML = rows.length ? rows.map(painRow).join('')
      : emptyState('', 'No pain logged.',
          '<button type="button" class="btn btn-sec btn-sm" data-action="open-log-pain">Log pain</button>');
    renderPager('pain-pager', page, res.page?.totalPages ?? 1, loadPainLog);
  } catch (err) {
    listEl.innerHTML = `<div class="error-state">Couldn't load pain history: ${escapeHtml(err.message)}</div>`;
    document.getElementById('pain-pager').innerHTML = '';
    if (count) count.textContent = '';
  }
}

/**
 * Opens Log Pain with the recent workouts to pick from. The dialog could never link an
 * entry to a workout, though the API always could (found 2026-11-16): the form sent
 * activityId: null every time. It opens at once and fills the list as it arrives; if
 * the list can't load, the entry is simply logged unlinked, as before.
 */
export async function openLogPain() {
  const sel = document.getElementById('pain-activity');
  sel.innerHTML = '<option value="">Not linked to a workout</option>';
  openModal('modal-log-pain');
  try {
    const res = await api('/api/activities?page=0&size=10&sort=startTime,desc');
    const rows = res.content || [];
    sel.innerHTML = '<option value="">Not linked to a workout</option>'
      + rows.map(a => `<option value="${escapeHtml(a.id)}">${escapeHtml(workoutName(a))}</option>`).join('');
  } catch (err) {
    // Not worth a toast: the entry can still be logged, just not linked.
  }
}

export async function handleLogPain(e) {
  e.preventDefault();
  const btn = document.getElementById('log-pain-btn');
  btn.disabled = true; btn.textContent = 'Logging…';
  const payload = {
    userId: session.currentUser.id,
    activityId: document.getElementById('pain-activity').value || null,
    bodyPart: document.getElementById('pain-bodypart').value,
    severity: parseInt(document.getElementById('pain-severity').value),
    description: document.getElementById('pain-description').value.trim() || null
  };
  try {
    await api('/api/pain-log', { method: 'POST', body: JSON.stringify(payload) });
    closeModal('modal-log-pain');
    document.getElementById('form-log-pain').reset();
    toast('Pain logged. Take care of yourself.');
    loadPainLog();
  } catch (err) {
    toast(err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Log pain';
  }
}
