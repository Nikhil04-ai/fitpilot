import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv } from './helpers/env.mjs';

installBrowserEnv();
const util = await import('../js/util.js');
const charts = await import('../js/charts.js');

test('escapeHtml neutralises every character that can open markup or end an attribute', () => {
  assert.equal(util.escapeHtml(`<img src=x onerror="a('b')">&`),
    '&lt;img src=x onerror=&quot;a(&#39;b&#39;)&quot;&gt;&amp;');
  assert.equal(util.escapeHtml(null), '');
  assert.equal(util.escapeHtml(undefined), '');
  assert.equal(util.escapeHtml(42), '42');
});

test('initials never reads "undefined" when the optional last name is missing', () => {
  assert.equal(util.initials('Mona', 'Dule'), 'MD');
  assert.equal(util.initials('mona', ''), 'M');
  assert.equal(util.initials('Mona', undefined), 'M');
  assert.equal(util.initials('', ''), '?');
});

test('trimNumber drops a trailing .0 and rounds to two places', () => {
  assert.equal(util.trimNumber(60), '60');
  assert.equal(util.trimNumber(60.0), '60');
  assert.equal(util.trimNumber(62.5), '62.5');
  assert.equal(util.trimNumber(1.23456), '1.23');
  assert.equal(util.trimNumber('7'), '7');
  assert.equal(util.trimNumber(null), '');
});

test('localDateTimeNow is local wall-clock time with no zone suffix (AGENT.md §8)', () => {
  const s = util.localDateTimeNow();
  assert.match(s, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
  assert.ok(!s.endsWith('Z'));
  const d = new Date();
  assert.ok(s.startsWith(`${d.getFullYear()}-`));
});

test('today is YYYY-MM-DD in local time', () => {
  assert.match(util.today(), /^\d{4}-\d{2}-\d{2}$/);
  assert.equal(util.today(), util.localDateTimeNow().slice(0, 10));
});

test('shortDate and shortTime return empty strings for an unparseable date', () => {
  assert.equal(util.shortDate('not a date'), '');
  assert.equal(util.shortTime('not a date'), '');
  assert.notEqual(util.shortDate('2026-09-14T09:30:00'), '');
});

test('numberOrNull treats an empty field as "not given", not zero', () => {
  document.getElementById('n').value = '';
  assert.equal(util.numberOrNull('n'), null);
  document.getElementById('n').value = '0';
  assert.equal(util.numberOrNull('n'), 0);
  document.getElementById('n').value = '12.5';
  assert.equal(util.numberOrNull('n'), 12.5);
  document.getElementById('n').value = 'abc';
  assert.equal(util.numberOrNull('n'), null);
});

test('emptyState escapes its text', () => {
  assert.ok(util.emptyState('🏃', '<b>x</b>').includes('&lt;b&gt;x&lt;/b&gt;'));
});

test('skeleton helpers render the requested number of placeholders', () => {
  assert.equal((util.skeletonRows(3).match(/sk-row/g) || []).length, 3);
  assert.equal((util.skeletonStats(4).match(/sk-stat/g) || []).length, 4);
});

test('shortAxisDate formats an ISO date as "Sep 21" and passes junk through untouched', () => {
  assert.equal(charts.shortAxisDate('2026-09-21'), 'Sep 21');
  assert.equal(charts.shortAxisDate('2026-09-21T23:59:00'), 'Sep 21');
  assert.equal(charts.shortAxisDate('nope'), 'nope');
});

test('timestampAxisLabels stays a plain date axis when every day is distinct', () => {
  assert.deepEqual(charts.timestampAxisLabels(['2026-09-20T08:00:00', '2026-09-21T08:00:00']),
    ['Sep 20', 'Sep 21']);
});

test('timestampAxisLabels adds the time when two readings share a date', () => {
  const labels = charts.timestampAxisLabels(['2026-09-21T08:00:00', '2026-09-21T19:30:00']);
  assert.deepEqual(labels, ['Sep 21 08:00', 'Sep 21 19:30']);
});

test('timestampAxisLabels numbers readings that collide even to the minute', () => {
  const labels = charts.timestampAxisLabels(
    ['2026-09-21T08:00:10', '2026-09-21T08:00:40', '2026-09-22T08:00:00']);
  assert.equal(new Set(labels).size, 3, 'every tick must be distinct');
  assert.deepEqual(labels.slice(0, 2), ['Sep 21 08:00 (1)', 'Sep 21 08:00 (2)']);
});

test('renderChart degrades to a note instead of throwing when Chart.js is missing', () => {
  delete globalThis.Chart;
  document.getElementById('c1');
  assert.equal(charts.renderChart('c1', {}, 'c1-note'), false);
  assert.match(document.getElementById('c1-note').textContent, /Chart\.js/);
});
