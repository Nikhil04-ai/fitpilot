// Static checks over index.html and every js/ module, run against the real action
// registry in main.js. These replace "is every inline handler on window?", which used
// to be checked by clicking every button in a browser.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installBrowserEnv } from './helpers/env.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(join(ROOT, 'index.html'), 'utf8');

function jsFiles(dir = join(ROOT, 'js')) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(d =>
    d.isDirectory() ? jsFiles(join(dir, d.name)) : d.name.endsWith('.js') ? [join(dir, d.name)] : []);
}
const sources = [['index.html', html],
  ...jsFiles().map(f => [relative(ROOT, f).replace(/\\/g, '/'), readFileSync(f, 'utf8')])];

/** Every value of one data-* attribute across the page and the panels' templates. */
function used(attr) {
  const names = new Map();
  for (const [file, text] of sources) {
    for (const m of text.matchAll(new RegExp(`data-${attr}="([^"$]*)"`, 'g'))) {
      if (!names.has(m[1])) names.set(m[1], file);
    }
  }
  return names;
}

installBrowserEnv();
await import('../js/main.js');
const { actionNames } = await import('../js/actions.js');

test('no inline event-handler attributes anywhere — the CSP would block them', () => {
  for (const [file, text] of sources) {
    const hits = [...text.matchAll(/<[^>]*\son[a-z]+\s*=\s*["']/gi)].map(m => m[0].slice(0, 80));
    assert.deepEqual(hits, [], `${file} still has inline handlers`);
  }
});

for (const [type, attr] of [['click', 'action'], ['submit', 'submit'], ['change', 'change']]) {
  test(`every data-${attr} name used in markup has a ${type} handler in main.js`, () => {
    const registered = new Set(actionNames(type));
    const missing = [...used(attr)].filter(([name]) => !registered.has(name))
      .map(([name, file]) => `${name} (${file})`);
    assert.deepEqual(missing, []);
  });

  test(`every registered ${type} handler is used by some markup`, () => {
    const inMarkup = used(attr);
    assert.deepEqual(actionNames(type).filter(n => !inMarkup.has(n)), []);
  });
}

test('main.js no longer puts handlers on window', () => {
  for (const [file, text] of sources.filter(([f]) => f.endsWith('.js'))) {
    assert.doesNotMatch(text, /Object\.assign\(\s*window/, file);
    assert.doesNotMatch(text, /\bwindow\.\w+\s*=[^=]/, file);
  }
});

test('no native confirm/alert/prompt dialogs', () => {
  for (const [file, text] of sources.filter(([f]) => f.endsWith('.js'))) {
    assert.doesNotMatch(text, /(^|[^.\w])(confirm|alert|prompt)\s*\(/m, file);
  }
});

test('every data-modal names a modal that exists', () => {
  for (const [name, file] of used('modal')) {
    assert.match(html, new RegExp(`class="modal-overlay" id="${name}"`), `${name} (${file})`);
  }
});

test('index.html has no inline <script> blocks, and every script it loads exists', () => {
  const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)];
  assert.ok(scripts.length >= 3);
  for (const [, attrs, body] of scripts) {
    const src = /src="([^"]+)"/.exec(attrs);
    assert.ok(src, `inline script: ${body.trim().slice(0, 60)}`);
    assert.equal(body.trim(), '');
    if (!/^https?:/.test(src[1])) assert.ok(existsSync(join(ROOT, src[1])), `missing ${src[1]}`);
  }
});

test('the CSP is set, comes before anything it governs, and allows no inline script', () => {
  const meta = /<meta http-equiv="Content-Security-Policy" content="([^"]+)">/.exec(html);
  assert.ok(meta, 'CSP meta tag present');
  const firstGoverned = Math.min(...['<link', '<style', '<script'].map(t => html.indexOf(t)).filter(i => i >= 0));
  assert.ok(meta.index < firstGoverned, 'a meta CSP only applies to what comes after it');

  const policy = Object.fromEntries(meta[1].split(';').map(d => d.trim().split(/\s+/)).map(([k, ...v]) => [k, v]));
  assert.ok(policy['script-src'], 'script-src set');
  assert.ok(!policy['script-src'].includes("'unsafe-inline'"));
  assert.ok(!policy['script-src'].includes("'unsafe-eval'"));
  assert.deepEqual(policy['object-src'], ["'none'"]);
  // The pinned Chart.js and the fonts must stay reachable.
  assert.ok(policy['script-src'].includes('https://cdn.jsdelivr.net'));
  assert.ok(policy['font-src'].includes('https://fonts.gstatic.com'));
  assert.ok(html.includes('src="https://cdn.jsdelivr.net/npm/chart.js@'));
  assert.ok(html.includes('integrity="sha384-'), 'Chart.js keeps its SRI hash');
});

test('the CSP lets the app reach every backend config.js can resolve to', () => {
  const csp = /Content-Security-Policy" content="([^"]+)"/.exec(html)[1];
  const connect = /connect-src ([^;]+)/.exec(csp)[1].split(/\s+/);
  assert.ok(connect.includes('http://localhost:*'), 'local backend');
  assert.ok(connect.includes('https:'), 'Render, or any ?api= override');
});

test('the confirm and wake-notice elements the JS needs are in the page', () => {
  for (const id of ['modal-confirm', 'confirm-title', 'confirm-message', 'confirm-ok', 'confirm-cancel', 'wake-notice']) {
    assert.match(html, new RegExp(`id="${id}"`), id);
  }
  // Cancel before confirm, so it is what openModal() focuses.
  assert.ok(html.indexOf('id="confirm-cancel"') < html.indexOf('id="confirm-ok"'));
});
