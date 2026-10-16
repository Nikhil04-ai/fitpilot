// ── Dates ─────────────────────────────────────────────────────────────

/**
 * Local wall-clock time as "YYYY-MM-DDTHH:mm:ss" — the format the backend's
 * LocalDateTime fields expect.
 *
 * Do NOT use `new Date().toISOString()` here. It returns UTC with a trailing "Z",
 * and Jackson parses that into a LocalDateTime by silently dropping the Z — so a
 * workout logged at 21:00 IST was stored as 15:30. Nothing errored; the times were
 * just wrong, which showed up as "your peak workout time" in Stats & Insights being
 * off by exactly the UTC offset.
 */
export function localDateTimeNow() {
  const d = new Date();
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
       + `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** "Mon 14 Sep, 09:30" — the row used to carry no date at all. */
export function shortDate(iso) {
  const d = new Date(iso);
  if (isNaN(d)) return '';
  return d.toLocaleString([], {
    weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit'
  });
}

/** "09:30" — a meal row is always today's, so the date would be noise. */
export function shortTime(iso) {
  const d = new Date(iso);
  if (isNaN(d)) return '';
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** An empty number input is "not given", which is not the same as zero. */
export function numberOrNull(id) {
  const raw = document.getElementById(id).value;
  if (raw === '' || raw == null) return null;
  const n = parseFloat(raw);
  return isNaN(n) ? null : n;
}

export function today() {
  const d = new Date();
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Yesterday's local date: the night a sleep entry is for unless told otherwise, as on the server. */
export function yesterday() {
  const d = new Date();
  d.setDate(d.getDate() - 1);
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** "60" not "60.0"; "62.5" stays "62.5". */
export function trimNumber(value) {
  if (value == null) return '';
  const n = Number(value);
  return Number.isInteger(n) ? String(n) : String(Math.round(n * 100) / 100);
}

// ═══════════════════════════════════════════════════════════════════════
// HELPERS
// ═══════════════════════════════════════════════════════════════════════

export function emptyState(icon, text) {
  return `<div class="empty-state"><div class="es-icon" aria-hidden="true">${icon}</div><div class="es-text">${escapeHtml(text)}</div></div>`;
}

// ── Skeletons ─────────────────────────────────────────────────────────
// A centred spinner is replaced by a full grid, so every panel load shoved the
// layout around. These stand in at roughly the size of the thing that's coming,
// so the page settles once instead of twice. aria-hidden + a live region on the
// container would be noise; the panel's own heading already says what's loading.

/** Stat tiles across the top of Overview and Health. */
export function skeletonStats(n) {
  return Array.from({ length: n }, () => `
    <div class="card sk-stat" aria-hidden="true">
      <div class="sk sk-ic"></div>
      <div class="sk-txt"><div class="sk sk-line w60"></div><div class="sk sk-line w40" style="margin-bottom:0"></div></div>
    </div>`).join('');
}

/** Activity / pain / goal list rows. */
export function skeletonRows(n) {
  return `<div aria-hidden="true">` + Array.from({ length: n }, () => `
    <div class="sk-row">
      <div class="sk sk-dot"></div>
      <div class="sk-txt"><div class="sk sk-line w60"></div><div class="sk sk-line w40" style="margin-bottom:0"></div></div>
    </div>`).join('') + `</div>`;
}

/** Whole-card placeholders, for grids of cards (plans, challenges, AI insights). */
export function skeletonCards(n) {
  return Array.from({ length: n }, () => `
    <div class="card" aria-hidden="true">
      <div class="sk sk-line w40"></div>
      <div class="sk sk-line w80"></div>
      <div class="sk sk-block" style="margin-top:12px"></div>
    </div>`).join('');
}

/** A few stacked lines, for the small text blocks inside a card. */
export function skeletonLines(n) {
  return `<div aria-hidden="true">` + Array.from({ length: n }, (_, i) =>
    `<div class="sk sk-line ${['w100','w80','w60','w40'][i % 4]}"></div>`).join('') + `</div>`;
}
/**
 * "MD" for Mona Dule, "M" for Mona. Plain text — escape it before innerHTML.
 *
 * Was inlined as `(first||'?')[0] + (last||'')[0]`, where an empty last name gives
 * `''[0]`, i.e. undefined, and the avatar read "MUNDEFINED". Last name is optional
 * at registration, so that was anyone who skipped it.
 */
export function initials(firstName, lastName) {
  return (((firstName || '?')[0]) + ((lastName || '')[0] || '')).toUpperCase();
}

export function escapeHtml(str) {
  if (str == null) return '';
  return String(str).replace(/[&<>"']/g, s => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[s]));
}
