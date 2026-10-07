import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonRows } from '../util.js';
import { closeModal, renderPager, toast } from '../ui.js';
import { api } from '../api.js';

// ═══════════════════════════════════════════════════════════════════════
// PAIN LOG — Feature 6
// ═══════════════════════════════════════════════════════════════════════

const BODY_PART_LABEL = {
  LEFT_KNEE:'Left Knee', RIGHT_KNEE:'Right Knee', LEFT_SHOULDER:'Left Shoulder', RIGHT_SHOULDER:'Right Shoulder',
  LOWER_BACK:'Lower Back', UPPER_BACK:'Upper Back', LEFT_ANKLE:'Left Ankle', RIGHT_ANKLE:'Right Ankle',
  NECK:'Neck', HIPS:'Hips', OTHER:'Other'
};
const SEVERITY_COLOR = { 1:'var(--cyan)', 2:'var(--cyan)', 3:'var(--gold)', 4:'var(--gold)', 5:'var(--red)' };

function painRow(p) {
  const color = SEVERITY_COLOR[p.severity] || 'var(--muted)';
  const date = p.loggedAt ? new Date(p.loggedAt).toLocaleDateString('en', { day: 'numeric', month: 'short' }) : '';
  return `
    <div class="ai">
      <div class="ad" style="background:${color};box-shadow:0 0 6px ${color}"></div>
      <div class="an">${BODY_PART_LABEL[p.bodyPart] || p.bodyPart} — severity ${p.severity}/5${p.description ? ' · ' + escapeHtml(p.description) : ''}</div>
      <div class="am">${date}</div>
    </div>`;
}

export async function loadPainLog(page = 0) {
  const warnEl = document.getElementById('pain-warnings');
  const listEl = document.getElementById('pain-list');
  listEl.innerHTML = skeletonRows(4);

  try {
    const patterns = await api(`/api/pain-log/patterns/${session.currentUser.id}`);
    warnEl.innerHTML = patterns.length
      ? patterns.map(p => `<div class="warnbox" style="margin-bottom:12px"><div class="wtt">⚠ Pattern Detected</div><div class="wtb">${escapeHtml(p)}</div></div>`).join('')
      : '';
  } catch (err) {
    warnEl.innerHTML = '';
  }

  try {
    // Paged and already newest first — the whole history used to arrive in one
    // response and be sorted here, growing with every entry (PLAN P3-3).
    const res = await api(`/api/pain-log/${session.currentUser.id}?page=${page}&size=15`);
    const rows = res.content || [];
    listEl.innerHTML = rows.length ? rows.map(painRow).join('') : emptyState('🩹', 'No pain logged — hopefully it stays that way!');
    renderPager('pain-pager', page, res.page?.totalPages ?? 1, loadPainLog);
  } catch (err) {
    listEl.innerHTML = `<div class="error-state">Couldn't load pain history: ${escapeHtml(err.message)}</div>`;
  }
}

export async function handleLogPain(e) {
  e.preventDefault();
  const btn = document.getElementById('log-pain-btn');
  btn.disabled = true; btn.textContent = 'Logging…';
  const payload = {
    userId: session.currentUser.id,
    activityId: null,
    bodyPart: document.getElementById('pain-bodypart').value,
    severity: parseInt(document.getElementById('pain-severity').value),
    description: document.getElementById('pain-description').value.trim() || null
  };
  try {
    await api('/api/pain-log', { method: 'POST', body: JSON.stringify(payload) });
    closeModal('modal-log-pain');
    document.getElementById('form-log-pain').reset();
    toast('🩹 Pain logged. Take care of yourself!');
    loadPainLog();
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Log It';
  }
}
