// ═══════════════════════════════════════════════════════════════════════
// ACTIONS — one delegated listener per event type, instead of inline on*=""
//
// Markup names what should happen; main.js maps the name to a function:
//   <button data-action="delete-goal" data-id="…">   click
//   <form   data-submit="login">                     submit
//   <select data-change="goal-target-hint">          change
//
// Click, submit and change get separate attributes on purpose. A click inside a
// form bubbles up through the form, so if forms used data-action too, clicking
// any field in one would fire its submit handler.
//
// This replaced ~95 inline handlers and the 56 functions main.js used to put on
// window for them to find. Inline handlers only see window (module scope is not
// global), break silently when a name is missing from that list, and cannot run
// under a Content-Security-Policy without 'unsafe-inline'.
// ═══════════════════════════════════════════════════════════════════════

const registries = {
  click:  { attr: 'action', handlers: Object.create(null) },
  submit: { attr: 'submit', handlers: Object.create(null) },
  change: { attr: 'change', handlers: Object.create(null) }
};

/**
 * Adds handlers for one event type. Each is called as handler(element, event),
 * where element is the one carrying the data-* attribute — read arguments from
 * its dataset rather than from the event target, which may be a child of it.
 */
export function registerActions(type, map) {
  Object.assign(registries[type].handlers, map);
}

/** The registered names for one event type. The tests check markup against these. */
export function actionNames(type) {
  return Object.keys(registries[type].handlers);
}

/**
 * Routes one event to its handler. Returns true when a handler ran.
 *
 * A name with no handler is reported on the console rather than ignored: the old
 * window-list version of this failed only when someone clicked the button.
 */
export function dispatchAction(e) {
  const reg = registries[e.type];
  if (!reg || !e.target || typeof e.target.closest !== 'function') return false;

  const el = e.target.closest(`[data-${reg.attr}]`);
  if (!el) return false;

  // A submit must never fall through to a real form post — that reloads the page
  // and drops the session out of memory. The handlers call this too; doing it here
  // as well covers one that throws before reaching its own call.
  if (e.type === 'submit') e.preventDefault();

  const name = el.dataset[reg.attr];
  const handler = reg.handlers[name];
  if (!handler) {
    console.error(`No ${e.type} handler registered for "${name}"`);
    return false;
  }
  handler(el, e);
  return true;
}

/** Attaches the three delegated listeners. Called once, from main.js. */
export function installActionDelegation(root = document) {
  Object.keys(registries).forEach(type => root.addEventListener(type, dispatchAction));
}
