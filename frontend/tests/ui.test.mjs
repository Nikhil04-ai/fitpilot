// js/ui.js: the confirm dialog, modals, pager and toast.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el } from './helpers/env.mjs';

const { document } = installBrowserEnv();
const ui = await import('../js/ui.js');
const { session } = await import('../js/state.js');

// The confirm modal as index.html has it: ✕, Cancel first, then the confirm button.
const opener = document.body.appendChild(el('button', { id: 'opener' }));
document.body.appendChild(
  el('div', { class: 'modal-overlay', id: 'modal-confirm' },
    el('div', { class: 'modal' },
      el('h2', { id: 'confirm-title' }),
      el('button', { class: 'modal-close', 'data-action': 'close-modal', 'data-modal': 'modal-confirm' }),
      el('p', { id: 'confirm-message' }),
      el('button', { id: 'confirm-cancel', 'data-action': 'close-modal', 'data-modal': 'modal-confirm' }),
      el('button', { id: 'confirm-ok', class: 'btn btn-danger', 'data-action': 'confirm-accept' }))));
const overlay = document.getElementById('modal-confirm');

beforeEach(() => {
  ui.closeModal('modal-confirm');
  opener.focus();
});

test('confirmDialog fills in the text and opens the modal', () => {
  ui.confirmDialog({ title: 'Delete goal?', message: 'Cannot be undone.', confirmLabel: 'Delete' });
  assert.ok(overlay.classList.contains('show'));
  assert.equal(document.getElementById('confirm-title').textContent, 'Delete goal?');
  assert.equal(document.getElementById('confirm-message').textContent, 'Cannot be undone.');
  assert.equal(document.getElementById('confirm-ok').textContent, 'Delete');
  assert.equal(document.getElementById('confirm-cancel').textContent, 'Cancel');
  assert.ok(document.body.classList.contains('modal-open'), 'page behind is scroll-locked');
});

test('focus lands on Cancel, so Enter on a destructive prompt does not confirm it', () => {
  ui.confirmDialog({ message: 'x' });
  assert.equal(document.activeElement.id, 'confirm-cancel');
});

test('the confirm button resolves true, closes, and returns focus to the opener', async () => {
  const answer = ui.confirmDialog({ message: 'x' });
  ui.acceptConfirm();
  assert.equal(await answer, true);
  assert.ok(!overlay.classList.contains('show'));
  assert.equal(document.activeElement, opener);
  assert.ok(!document.body.classList.contains('modal-open'));
});

test('every other way out — Cancel, ✕, Escape, backdrop — resolves false', async () => {
  // All four route through closeModal('modal-confirm').
  const answer = ui.confirmDialog({ message: 'x' });
  ui.closeModal('modal-confirm');
  assert.equal(await answer, false);
});

test('Escape closes it as a "no"', async () => {
  const answer = ui.confirmDialog({ message: 'x' });
  document.dispatchEvent(Object.assign(new Event('keydown'), { key: 'Escape' }));
  assert.equal(await answer, false);
  assert.ok(!overlay.classList.contains('show'));
});

test('a second prompt answers the first with "no" rather than leaving it hanging', async () => {
  const first = ui.confirmDialog({ message: 'one' });
  const second = ui.confirmDialog({ message: 'two' });
  assert.equal(await first, false);
  ui.acceptConfirm();
  assert.equal(await second, true);
});

test('labels and messages are text, never HTML', () => {
  ui.confirmDialog({ title: '<img src=x onerror=alert(1)>', message: '<b>hi</b>' });
  assert.equal(document.getElementById('confirm-title').textContent, '<img src=x onerror=alert(1)>');
  assert.equal(document.getElementById('confirm-title').innerHTML, '', 'nothing parsed as markup');
});

test('danger: false swaps the red button for the primary style', () => {
  ui.confirmDialog({ message: 'x', danger: false });
  const ok = document.getElementById('confirm-ok');
  assert.ok(ok.classList.contains('btn-prim'));
  assert.ok(!ok.classList.contains('btn-danger'));
  ui.confirmDialog({ message: 'x' });
  assert.ok(ok.classList.contains('btn-danger'));
});

test('renderPager calls the given function with the page, instead of a global by name', () => {
  const pager = document.getElementById('pager');
  // innerHTML isn't parsed by the fake DOM, so stand in the two buttons it would produce
  // and check the markup separately.
  const pages = [];
  const realQSA = pager.querySelectorAll.bind(pager);
  const prev = el('button', { 'data-page': '1' });
  const next = el('button', { 'data-page': '3' });
  pager.querySelectorAll = sel => (sel === 'button[data-page]' ? [prev, next] : realQSA(sel));

  ui.renderPager('pager', 2, 5, p => pages.push(p));
  assert.match(pager.innerHTML, /data-page="1"/);
  assert.match(pager.innerHTML, /data-page="3"/);
  assert.match(pager.innerHTML, /3 of 5/);
  assert.doesNotMatch(pager.innerHTML, /onclick/);
  prev.click(); next.click();
  assert.deepEqual(pages, [1, 3]);
});

test('renderPager disables Prev on the first page and Next on the last', () => {
  ui.renderPager('pager2', 0, 2, () => {});
  const html = document.getElementById('pager2').innerHTML;
  assert.match(html, /disabled aria-label="Previous page"/);
  assert.doesNotMatch(html, /disabled aria-label="Next page"/);
});

test('renderPager clears itself when everything fits on one page', () => {
  const p = document.getElementById('pager3');
  p.innerHTML = 'old';
  ui.renderPager('pager3', 0, 1, () => {});
  assert.equal(p.innerHTML, '');
});

test('toast sets text, not HTML, and errors are announced assertively', () => {
  document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
  ui.toast('<img src=x onerror=alert(1)>', true);
  assert.equal(document.getElementById('tmsg').textContent, '<img src=x onerror=alert(1)>');
  assert.ok(document.getElementById('toast').classList.contains('err'));
  assert.equal(document.getElementById('toast').getAttribute('aria-live'), 'assertive');
  ui.hideToast();
  assert.ok(!document.getElementById('toast').classList.contains('on'));
});

test('toast stays quiet while a dead session is being handled', () => {
  document.getElementById('tmsg').textContent = 'before';
  session.expiredInProgress = true;
  ui.toast('duplicate error', true);
  assert.equal(document.getElementById('tmsg').textContent, 'before');
  session.expiredInProgress = false;
});

test('installLogoFallback swaps an already-broken logo for its placeholder', () => {
  const img = document.body.appendChild(el('img', { id: 'logo' }));
  img.complete = true; img.naturalWidth = 0;
  ui.installLogoFallback('logo', 'logo-fb');
  assert.equal(img.style.display, 'none');
  assert.equal(document.getElementById('logo-fb').style.display, 'flex');
});

test('installLogoFallback waits for the error event while the logo is still loading', () => {
  const img = document.body.appendChild(el('img', { id: 'logo2' }));
  img.complete = false;
  ui.installLogoFallback('logo2', 'logo2-fb');
  assert.notEqual(img.style.display, 'none');
  img.dispatchEvent(new Event('error'));
  assert.equal(img.style.display, 'none');
});
