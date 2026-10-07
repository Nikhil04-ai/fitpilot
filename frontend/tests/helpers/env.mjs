// ═══════════════════════════════════════════════════════════════════════
// A just-enough browser for running the real js/ modules under `node --test`.
//
// No jsdom, no npm: the frontend has no package.json and these tests keep it that
// way. Call installBrowserEnv() BEFORE dynamically importing any app module — several
// read localStorage/location at import time (state.js, config.js). Each test file runs
// in its own process, so every file starts from a clean global scope.
// ═══════════════════════════════════════════════════════════════════════

class FakeClassList {
  constructor() { this.set = new Set(); }
  add(...c) { c.forEach(x => this.set.add(x)); }
  remove(...c) { c.forEach(x => this.set.delete(x)); }
  contains(c) { return this.set.has(c); }
  toggle(c, force) {
    const on = force === undefined ? !this.set.has(c) : !!force;
    if (on) this.set.add(c); else this.set.delete(c);
    return on;
  }
}

/** One simple selector: tag, #id, .class, [attr], [attr="v"], :not([attr]). */
function matchesSimple(el, sel) {
  const nots = [];
  sel = sel.replace(/:not\(([^)]*)\)/g, (_, inner) => { nots.push(inner); return ''; });
  if (nots.some(n => matchesSimple(el, n))) return false;
  const re = /([#.]?)([\w-]+)|\[([\w-]+)(?:="([^"]*)")?\]/g;
  let m, any = false;
  while ((m = re.exec(sel))) {
    any = true;
    if (m[3]) {
      const v = el.getAttribute(m[3]);
      if (v === null || (m[4] !== undefined && v !== m[4])) return false;
    } else if (m[1] === '#') { if (el.id !== m[2]) return false; }
    else if (m[1] === '.') { if (!el.classList.contains(m[2])) return false; }
    else if (el.tagName !== m[2].toUpperCase()) return false;
  }
  return any || nots.length > 0;
}

export class FakeElement extends EventTarget {
  constructor(tag = 'div', attrs = {}) {
    super();
    this.tagName = tag.toUpperCase();
    this.attrs = new Map();
    this.classList = new FakeClassList();
    this.style = {};
    this.children = [];
    this.parentElement = null;
    this.textContent = '';
    this.innerHTML = '';
    this.value = '';
    this.hidden = false;
    this.disabled = false;
    this.isConnected = true;
    for (const [k, v] of Object.entries(attrs)) this.setAttribute(k, v);
  }
  get id() { return this.getAttribute('id') || ''; }
  set id(v) { this.setAttribute('id', v); }
  get className() { return [...this.classList.set].join(' '); }
  set className(v) { this.classList = new FakeClassList(); String(v).split(/\s+/).filter(Boolean).forEach(c => this.classList.add(c)); }
  /** Live view of data-* attributes, camel-cased like the real one. */
  get dataset() {
    const out = {};
    for (const [k, v] of this.attrs) {
      if (k.startsWith('data-')) out[k.slice(5).replace(/-(\w)/g, (_, c) => c.toUpperCase())] = v;
    }
    return out;
  }
  setAttribute(k, v) {
    if (k === 'class') { this.className = v; return; }
    this.attrs.set(k, String(v));
  }
  getAttribute(k) {
    if (k === 'class') return this.className || null;
    if (k === 'disabled') return this.disabled ? '' : (this.attrs.has(k) ? this.attrs.get(k) : null);
    return this.attrs.has(k) ? this.attrs.get(k) : null;
  }
  removeAttribute(k) { this.attrs.delete(k); }
  appendChild(child) { child.parentElement = this; this.children.push(child); return child; }
  append(...kids) { kids.forEach(k => this.appendChild(k)); }
  remove() {
    if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(c => c !== this);
    this.parentElement = null;
  }
  contains(node) { for (let n = node; n; n = n.parentElement) if (n === this) return true; return false; }
  matches(selector) { return selector.split(',').some(s => matchesSimple(this, s.trim())); }
  closest(selector) { for (let n = this; n; n = n.parentElement) if (n.matches(selector)) return n; return null; }
  querySelectorAll(selector) {
    const out = [];
    const walk = el => el.children.forEach(c => { if (c.matches(selector)) out.push(c); walk(c); });
    walk(this);
    return out;
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  focus() { globalThis.document.activeElement = this; }
  /** Fires listeners added directly on this element (not delegated ones). */
  click() { if (!this.disabled) this.dispatchEvent(new Event('click')); }
  get offsetParent() { return this.hidden ? null : this.parentElement; }
}

/**
 * A plain event object for js/actions.js's dispatchAction(): a real Event's target is
 * read-only and only set by dispatching it through a real DOM.
 */
export class FakeEvent {
  constructor(type, { target } = {}) {
    this.type = type;
    this.target = target;
    this.defaultPrevented = false;
  }
  preventDefault() { this.defaultPrevented = true; }
}

class FakeDocument extends FakeElement {
  constructor() {
    super('#document');
    this.body = this.appendChild(new FakeElement('body'));
    this.activeElement = this.body;
    this.hidden = false;
  }
  /**
   * Elements by id. Anything a test didn't build is created on first use, so app code
   * that pokes at the auth screen or a badge just works; tests that care about
   * structure build it themselves with el() and attach it to the body.
   */
  getElementById(id) {
    const found = this.querySelector(`#${id}`);
    if (found) return found;
    return this.body.appendChild(new FakeElement('div', { id }));
  }
  createElement(tag) { return new FakeElement(tag); }
}

function makeStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem: k => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: k => map.delete(k),
    clear: () => map.clear(),
    get length() { return map.size; },
    _map: map
  };
}

/**
 * Installs window, document, location, history and localStorage as globals.
 * `url` decides IS_LOCAL and API_BASE in config.js; `storage` seeds localStorage.
 */
export function installBrowserEnv({ url = 'http://localhost:5500/', storage = {} } = {}) {
  const loc = new URL(url);
  const location = {
    get hostname() { return loc.hostname; },
    get search() { return loc.search; },
    get hash() { return loc.hash; },
    get pathname() { return loc.pathname; },
    get protocol() { return loc.protocol; },
    get href() { return loc.href; }
  };
  const history = {
    pushState(_, __, u) { const next = new URL(u, loc.href); loc.hash = next.hash; loc.search = next.search; },
    replaceState(_, __, u) { this.pushState(_, __, u); }
  };
  const document = new FakeDocument();
  const window = new EventTarget();

  Object.assign(globalThis, { window, document, location, history, localStorage: makeStorage(storage) });
  return { document, window };
}

/** Builds a FakeElement with attributes and children: el('button', {'data-action': 'x'}, child). */
export function el(tag, attrs = {}, ...children) {
  const e = new FakeElement(tag, attrs);
  children.forEach(c => e.appendChild(c));
  return e;
}

/** A Response-like object for a fake fetch. */
export function jsonResponse(status, body) {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: async () => { if (body === undefined) throw new SyntaxError('no body'); return body; },
    blob: async () => body
  };
}
