// js/actions.js: routing click/submit/change events to handlers by data-* name.
import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, el, FakeEvent } from './helpers/env.mjs';

installBrowserEnv();
const { registerActions, dispatchAction, installActionDelegation, actionNames } = await import('../js/actions.js');

const seen = [];
registerActions('click', {
  'delete-goal': (elem, e) => seen.push(['delete-goal', elem.dataset.id, e.type]),
  'nav': elem => seen.push(['nav', elem.dataset.panel])
});
registerActions('submit', { 'login': (_, e) => seen.push(['login', e.defaultPrevented]) });
registerActions('change', { 'goal-target-hint': () => seen.push(['hint']) });

test('a click on a child of the button reaches the button\'s handler with its data-*', () => {
  seen.length = 0;
  const icon = el('span');
  const btn = el('button', { 'data-action': 'delete-goal', 'data-id': 'g-7' }, icon);
  el('div', {}, btn);
  assert.equal(dispatchAction(new FakeEvent('click', { target: icon })), true);
  assert.deepEqual(seen, [['delete-goal', 'g-7', 'click']]);
});

test('a click outside any data-action element is ignored', () => {
  seen.length = 0;
  assert.equal(dispatchAction(new FakeEvent('click', { target: el('div', {}) })), false);
  assert.deepEqual(seen, []);
});

test('a click inside a data-submit form does NOT trigger the form\'s submit handler', () => {
  seen.length = 0;
  const input = el('input');
  el('form', { 'data-submit': 'login' }, input);
  assert.equal(dispatchAction(new FakeEvent('click', { target: input })), false);
  assert.deepEqual(seen, []);
});

test('submit always prevents the real form post, then calls the handler', () => {
  seen.length = 0;
  const form = el('form', { 'data-submit': 'login' });
  const e = new FakeEvent('submit', { target: form });
  dispatchAction(e);
  assert.equal(e.defaultPrevented, true);
  assert.deepEqual(seen, [['login', true]]);
});

test('an unregistered submit name is still prevented, and logged', () => {
  const err = mock.method(console, 'error', () => {});
  const e = new FakeEvent('submit', { target: el('form', { 'data-submit': 'nope' }) });
  assert.equal(dispatchAction(e), false);
  assert.equal(e.defaultPrevented, true, 'never falls through to a page reload');
  assert.match(err.mock.calls[0].arguments[0], /No submit handler registered for "nope"/);
  err.mock.restore();
});

test('an unregistered click name is reported instead of failing silently', () => {
  const err = mock.method(console, 'error', () => {});
  assert.equal(dispatchAction(new FakeEvent('click', { target: el('button', { 'data-action': 'typo' }) })), false);
  assert.equal(err.mock.callCount(), 1);
  err.mock.restore();
});

test('change events route by data-change', () => {
  seen.length = 0;
  dispatchAction(new FakeEvent('change', { target: el('select', { 'data-change': 'goal-target-hint' }) }));
  assert.deepEqual(seen, [['hint']]);
});

test('the three attributes are separate: data-action does not answer a submit', () => {
  seen.length = 0;
  const e = new FakeEvent('submit', { target: el('form', { 'data-action': 'nav', 'data-panel': 'x' }) });
  assert.equal(dispatchAction(e), false);
  assert.deepEqual(seen, []);
});

test('installActionDelegation listens for click, submit and change on the root', () => {
  const types = [];
  installActionDelegation({ addEventListener: (type, fn) => { types.push(type); assert.equal(fn, dispatchAction); } });
  assert.deepEqual(types.sort(), ['change', 'click', 'submit']);
});

test('actionNames lists what has been registered', () => {
  assert.ok(actionNames('click').includes('delete-goal'));
  assert.deepEqual(actionNames('submit'), ['login']);
});

test('a target without closest() (e.g. a text node or the document) is ignored', () => {
  assert.equal(dispatchAction({ type: 'click', target: {} }), false);
  assert.equal(dispatchAction({ type: 'click', target: null }), false);
});
