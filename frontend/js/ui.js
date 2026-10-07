import { session } from './state.js';

// ── Toast ─────────────────────────────────────────────────────────────
let toastTimer;

/**
 * Errors get twice the dwell time of successes, because they're the ones worth
 * reading. Both are dismissible — a toast used to be the only channel for a failed
 * save and it vanished after 3.5s whether or not anyone was looking.
 */
const TOAST_MS = { ok: 3500, err: 7000 };

export function toast(msg, isError) {
  // While we're already showing the person a clear "please log in again"
  // message on the auth screen, suppress any other in-flight error toasts
  // triggered by the SAME root cause (e.g. several parallel API calls all
  // failing at once because the account is gone) — otherwise a confusing
  // duplicate error appears on top of the message we already showed.
  if (session.expiredInProgress) return;

  const t = document.getElementById('toast');
  // textContent, never innerHTML: toasts carry server text, and some of it holds
  // another user's name ("You're now friends with <their name>"). A name chosen as
  // <img onerror=...> ran as script in whoever accepted the request, with their
  // tokens sitting in localStorage.
  document.getElementById('tmsg').textContent = msg;
  t.querySelector('.tic').textContent = isError ? '⚠️' : '✓';
  t.classList.toggle('err', !!isError);
  t.classList.add('on');
  // An error is also announced assertively; a success stays polite so it doesn't
  // interrupt whatever a screen reader is already reading.
  t.setAttribute('aria-live', isError ? 'assertive' : 'polite');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(hideToast, isError ? TOAST_MS.err : TOAST_MS.ok);
}

export function hideToast() {
  clearTimeout(toastTimer);
  document.getElementById('toast').classList.remove('on');
}

// ── Sidebar (mobile) ──────────────────────────────────────────────────
/** Visibility of the toggle itself is CSS's job; this only handles the open state. */
export function toggleSidebar(force) {
  const sb = document.getElementById('sidebar');
  const open = force !== undefined ? force : !sb.classList.contains('show');
  sb.classList.toggle('show', open);
  document.getElementById('mobile-menu-btn').setAttribute('aria-expanded', String(open));
}

// ── Modal helpers ─────────────────────────────────────────────────────
// Modals used to be a class toggle and nothing else: no Escape, no focus trap,
// focus never came back to whatever opened them, and the page behind stayed
// scrollable.

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),'
                + 'textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/** Stack, not a single value — a modal can be opened from inside another one. */
const modalStack = [];

export function openModal(id) {
  const overlay = document.getElementById(id);
  if (!overlay || overlay.classList.contains('show')) return;

  modalStack.push({ id, returnFocus: document.activeElement });
  overlay.classList.add('show');
  document.body.classList.add('modal-open');

  // Focus the first real control, skipping the ✕ button so a keyboard user lands
  // on the form rather than on the way out of it.
  const focusables = [...overlay.querySelectorAll(FOCUSABLE)]
    .filter(el => !el.classList.contains('modal-close'));
  (focusables[0] || overlay.querySelector('.modal-close'))?.focus();
}

export function closeModal(id) {
  const overlay = document.getElementById(id);
  if (!overlay || !overlay.classList.contains('show')) return;

  overlay.classList.remove('show');
  // Every way out of the confirm dialog that isn't its confirm button — Cancel, ✕,
  // Escape, a click on the backdrop — ends up here, and all of them mean "no".
  if (id === CONFIRM_ID) settleConfirm(false);
  const i = modalStack.findIndex(m => m.id === id);
  const entry = i >= 0 ? modalStack.splice(i, 1)[0] : null;
  if (!modalStack.length) document.body.classList.remove('modal-open');

  // Send focus back where it came from, so closing a modal doesn't dump the
  // keyboard user at the top of the document.
  if (entry?.returnFocus?.isConnected) entry.returnFocus.focus();
}

function topModal() { return modalStack.length ? modalStack[modalStack.length - 1].id : null; }

document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('.modal-overlay').forEach(ov => {
    ov.addEventListener('click', e => { if (e.target === ov) closeModal(ov.id); });
  });
});

// ── Confirm dialog ────────────────────────────────────────────────────
// Replaces window.confirm(), which can't be styled, reads "localhost:5500 says",
// blocks the whole tab, and is suppressed outright once someone ticks "don't let
// this page create more dialogs" — after which every delete silently did nothing.

const CONFIRM_ID = 'modal-confirm';
let confirmResolve = null;

function settleConfirm(result) {
  const resolve = confirmResolve;
  confirmResolve = null;
  if (resolve) resolve(result);
}

/**
 * Asks a yes/no question in a FitPilot modal. Resolves true only when the confirm
 * button is pressed; every other way of closing it resolves false.
 *
 * Focus lands on Cancel, so Enter on a destructive prompt doesn't destroy anything.
 * Labels go in with textContent — a message may one day carry a name.
 */
export function confirmDialog({ title = 'Are you sure?', message = '', confirmLabel = 'Confirm',
                                cancelLabel = 'Cancel', danger = true } = {}) {
  // Only one at a time. A second request answers the first with "no" rather than
  // leaving its caller waiting forever.
  settleConfirm(false);

  document.getElementById('confirm-title').textContent = title;
  document.getElementById('confirm-message').textContent = message;
  const ok = document.getElementById('confirm-ok');
  ok.textContent = confirmLabel;
  ok.classList.toggle('btn-danger', danger);
  ok.classList.toggle('btn-prim', !danger);
  document.getElementById('confirm-cancel').textContent = cancelLabel;

  return new Promise(resolve => {
    confirmResolve = resolve;
    openModal(CONFIRM_ID);
  });
}

/** The confirm button. Settles first, so closeModal's "no" finds nothing left to answer. */
export function acceptConfirm() {
  settleConfirm(true);
  closeModal(CONFIRM_ID);
}

// ── Logo fallback ─────────────────────────────────────────────────────
/**
 * Swaps a logo that failed to load for its 💪 placeholder. Was an inline onerror.
 *
 * Module scripts run after parsing, by which time a missing image may already have
 * failed — its error event long gone. So the current state is checked as well as
 * listened for.
 */
export function installLogoFallback(imgId, fallbackId) {
  const img = document.getElementById(imgId);
  if (!img) return;
  const fail = () => {
    img.style.display = 'none';
    document.getElementById(fallbackId).style.display = 'flex';
  };
  if (img.complete && img.naturalWidth === 0) fail();
  else img.addEventListener('error', fail, { once: true });
}

document.addEventListener('keydown', e => {
  const openId = topModal();

  if (e.key === 'Escape') {
    if (openId) { closeModal(openId); return; }
    // No modal open — Escape also backs out of the mobile sidebar.
    if (document.getElementById('sidebar')?.classList.contains('show')) toggleSidebar(false);
    return;
  }

  // Focus trap: Tab must not escape the topmost dialog into the page behind it.
  if (e.key !== 'Tab' || !openId) return;
  const overlay = document.getElementById(openId);
  const items = [...overlay.querySelectorAll(FOCUSABLE)].filter(el => el.offsetParent !== null);
  if (!items.length) return;

  const first = items[0], last = items[items.length - 1];
  if (e.shiftKey && document.activeElement === first)      { e.preventDefault(); last.focus(); }
  else if (!e.shiftKey && document.activeElement === last)  { e.preventDefault(); first.focus(); }
  else if (!overlay.contains(document.activeElement))       { e.preventDefault(); first.focus(); }
});

/**
 * Prev / "n of m" / Next.
 *
 * This used to render one button per page. At ten rows a page, a year of daily
 * training is 36 buttons wrapping over three lines, and it grows forever.
 *
 * onPage is the function itself, called with the page to load. It used to be a
 * global function's name spliced into an onclick attribute.
 */
export function renderPager(containerId, page, totalPages, onPage) {
  const pager = document.getElementById(containerId);
  if (!pager) return;
  if (totalPages <= 1) { pager.innerHTML = ''; return; }

  const atStart = page <= 0;
  const atEnd   = page >= totalPages - 1;
  pager.className = 'pager';
  pager.innerHTML = `
    <button class="btn-ghost" ${atStart ? 'disabled' : ''} aria-label="Previous page"
            data-page="${page - 1}">← Prev</button>
    <span class="pager-pos" aria-live="polite">${page + 1} of ${totalPages}</span>
    <button class="btn-ghost" ${atEnd ? 'disabled' : ''} aria-label="Next page"
            data-page="${page + 1}">Next →</button>`;
  // Fresh buttons every render, so their listeners go with them on the next one.
  pager.querySelectorAll('button[data-page]').forEach(btn =>
    btn.addEventListener('click', () => onPage(Number(btn.dataset.page))));
}
