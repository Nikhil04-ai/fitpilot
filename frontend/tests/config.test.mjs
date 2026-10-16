// Which backend the page talks to (js/config.js).
//
// API_BASE is resolved at import time, so each case installs its own location and
// storage and imports a fresh copy of the module (a distinct query string is a
// distinct module instance).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv } from './helpers/env.mjs';

let n = 0;
async function load(url, storage = {}) {
  installBrowserEnv({ url, storage });
  return import(`../js/config.js?case=${++n}`);
}

const DEPLOYED = 'https://someone.github.io/fitpilot/';

test('a deployed page ignores ?api=, so a link cannot point it at another host', async () => {
  const { API_BASE, IS_LOCAL } = await load(DEPLOYED + '?api=https://attacker.example');
  assert.equal(IS_LOCAL, false);
  assert.notEqual(API_BASE, 'https://attacker.example');
  assert.match(API_BASE, /onrender\.com$/);
  assert.equal(localStorage.getItem('fp_apiBase'), null, 'the link must not be remembered');
});

test('a deployed page erases an override remembered from an earlier visit', async () => {
  const { API_BASE } = await load(DEPLOYED, { fp_apiBase: 'https://attacker.example' });
  assert.notEqual(API_BASE, 'https://attacker.example');
  assert.equal(localStorage.getItem('fp_apiBase'), null);
});

test('a deployed page honours a loopback ?api= for that load only, and never remembers it', async () => {
  const { API_BASE } = await load(DEPLOYED + '?api=http://127.0.0.1:9999/');
  assert.equal(API_BASE, 'http://127.0.0.1:9999');
  assert.equal(localStorage.getItem('fp_apiBase'), null);
});

test('a deployed page refuses a host that only looks like loopback', async () => {
  for (const sneaky of ['http://127.0.0.1.attacker.example', 'http://localhost.attacker.example/', 'https://attacker.example/127.0.0.1']) {
    const { API_BASE } = await load(DEPLOYED + '?api=' + encodeURIComponent(sneaky));
    assert.match(API_BASE, /onrender\.com$/, sneaky);
  }
});

test('locally, ?api= still points the page at another backend and is remembered', async () => {
  const { API_BASE } = await load('http://localhost:5500/?api=http://127.0.0.1:9090/');
  assert.equal(API_BASE, 'http://127.0.0.1:9090');
  assert.equal(localStorage.getItem('fp_apiBase'), 'http://127.0.0.1:9090');
});

test('locally, an override that is not an http(s) URL is ignored', async () => {
  const { API_BASE } = await load('http://localhost:5500/?api=javascript:alert(1)');
  assert.equal(API_BASE, 'http://localhost:8080');
});

test('locally, with no override, the local backend is used', async () => {
  const { API_BASE } = await load('http://localhost:5500/');
  assert.equal(API_BASE, 'http://localhost:8080');
});
