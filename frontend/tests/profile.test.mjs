// The Profile page's handlers (js/panels/profile.js): loading, saving, changing the password
// and the three ways to download your data.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installBrowserEnv, jsonResponse, el, FakeEvent } from './helpers/env.mjs';

const { document } = installBrowserEnv({
  storage: { fp_accessToken: 'access', fp_refreshToken: 'refresh', fp_user: JSON.stringify({ id: 'u-1', firstName: 'Old' }) }
});
document.getElementById('toast').appendChild(el('div', { class: 'tic' }));
const { loadProfile, handleUpdateProfile, handleChangePassword, downloadExport } = await import('../js/panels/profile.js');
const { session } = await import('../js/state.js');

const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}✓⬇]/u;
// The fake DOM has no select.options and no form.reset(); give these two elements what the page uses.
document.getElementById('export-dataset').options = { length: 0 };
let resets = 0;
document.getElementById('form-password').reset = () => { resets++; };
const val = (id, v) => { document.getElementById(id).value = v; };
const toastText = () => document.getElementById('tmsg').textContent;

let calls = [], profile = { firstName: 'Uma', lastName: 'Tester', weight: 62.5, height: 168, age: 29 }, failWith = null, pwResponse = { accessToken: 'new-a', refreshToken: 'new-r' };
let created = [], revoked = [];
globalThis.URL.createObjectURL = (b) => { created.push(b); return 'blob:fake'; };
globalThis.URL.revokeObjectURL = (u) => { revoked.push(u); };
globalThis.fetch = async (url, opts = {}) => {
  const u = String(url);
  calls.push({ u, method: opts.method || 'GET', body: opts.body });
  if (failWith) return jsonResponse(failWith.status, { message: failWith.message });
  if (u.endsWith('/api/export/datasets')) return jsonResponse(200, { datasets: ['activities', 'pain', 'weird-new'] });
  if (u.includes('/api/export/')) return jsonResponse(200, 'FILE BYTES');
  if (u.endsWith('/password')) return jsonResponse(200, pwResponse);
  if (u.includes('/api/profile/') && opts.method === 'PUT') return jsonResponse(200, { ...profile, ...JSON.parse(opts.body) });
  if (u.includes('/api/profile/')) return jsonResponse(200, profile);
  return jsonResponse(404, {});
};

test('loading fills the form from the server and keeps the session user current', async () => {
  await loadProfile();
  assert.equal(document.getElementById('prof-first').value, 'Uma');
  assert.equal(document.getElementById('prof-weight').value, 62.5);
  assert.equal(document.getElementById('prof-age').value, 29);
  assert.equal(session.currentUser.firstName, 'Uma');
  assert.equal(JSON.parse(localStorage.getItem('fp_user')).lastName, 'Tester');
});

test('the dataset picker comes from the server, in words, and a kind the page does not know keeps its key', async () => {
  const opts = document.getElementById('export-dataset').innerHTML;
  assert.ok(opts.includes('<option value="activities">Workouts</option>'));
  assert.ok(opts.includes('<option value="pain">Pain logs</option>'));
  assert.ok(opts.includes('<option value="weird-new">weird-new</option>'));
});

test('a profile that cannot be loaded says so plainly', async () => {
  failWith = { status: 500, message: 'Boom' };
  await loadProfile();
  failWith = null;
  assert.match(toastText(), /^Could not load profile: /);
  assert.ok(!EMOJI.test(toastText()) && !toastText().includes('❌'));
});

test('saving sends the typed values, with a blank number as null, and updates the sidebar name', async () => {
  calls = [];
  document.getElementById('sb-uname').textContent = '';
  val('prof-first', '  Umaa '); val('prof-last', 'Tester'); val('prof-weight', '63.5'); val('prof-height', ''); val('prof-age', '30');
  const e = new FakeEvent('submit');
  await handleUpdateProfile(e);
  assert.equal(e.defaultPrevented, true);
  const put = calls.find(c => c.method === 'PUT');
  assert.deepEqual(JSON.parse(put.body), { firstName: 'Umaa', lastName: 'Tester', weight: 63.5, height: null, age: 30 });
  assert.equal(document.getElementById('sb-uname').textContent, 'Umaa Tester');
  assert.equal(toastText(), 'Profile updated.');
});

test('the save button is disabled while it saves and is back to its words afterwards, success or not', async () => {
  const btn = document.getElementById('save-profile-btn');
  let during;
  const real = globalThis.fetch;
  globalThis.fetch = async (...a) => { during = [btn.disabled, btn.textContent]; return real(...a); };
  await handleUpdateProfile(new FakeEvent('submit'));
  assert.deepEqual(during, [true, 'Saving…']);
  assert.deepEqual([btn.disabled, btn.textContent], [false, 'Save changes']);
  globalThis.fetch = real;

  failWith = { status: 400, message: 'Weight must be between 20 and 500 kg.' };
  await handleUpdateProfile(new FakeEvent('submit'));
  failWith = null;
  assert.equal(toastText(), 'Weight must be between 20 and 500 kg.', 'the server words, no cross');
  assert.deepEqual([btn.disabled, btn.textContent], [false, 'Save changes']);
});

test('changing the password stores the fresh pair, clears the form and says what happened to other devices', async () => {
  calls = [];
  val('pwd-current', 'old-pass'); val('pwd-new', 'new-pass-123');
  await handleChangePassword(new FakeEvent('submit'));
  const put = calls.find(c => c.u.endsWith('/password'));
  assert.deepEqual(JSON.parse(put.body), { currentPassword: 'old-pass', newPassword: 'new-pass-123' });
  assert.equal(session.accessToken, 'new-a');
  assert.equal(localStorage.getItem('fp_refreshToken'), 'new-r');
  assert.equal(resets, 1, 'the typed passwords are cleared');
  assert.equal(toastText(), 'Password changed. Other devices have been signed out.');
  assert.ok(!EMOJI.test(toastText()));
  const btn = document.getElementById('change-password-btn');
  assert.deepEqual([btn.disabled, btn.textContent], [false, 'Change password']);
});

test('a refused password change keeps the tokens, shows the reason plain and re-enables the button', async () => {
  const before = session.accessToken;
  failWith = { status: 400, message: 'Current password is incorrect.' };
  await handleChangePassword(new FakeEvent('submit'));
  failWith = null;
  assert.equal(session.accessToken, before);
  assert.equal(toastText(), 'Current password is incorrect.');
  assert.equal(document.getElementById('change-password-btn').disabled, false);
});

test('each download goes to its own path with a dated name, and the blob is freed after the click', async () => {
  const day = new Date(), p = n => String(n).padStart(2, '0');
  const today = `${day.getFullYear()}-${p(day.getMonth() + 1)}-${p(day.getDate())}`;
  document.getElementById('export-dataset').value = 'pain';
  for (const [format, path, name] of [
    ['zip', '/api/export/zip', `fitpilot-export-${today}.zip`],
    ['json', '/api/export/json', `fitpilot-export-${today}.json`],
    ['csv', '/api/export/csv/pain', `fitpilot-pain-${today}.csv`],
  ]) {
    calls = []; created = []; revoked = [];
    const links = [];
    const real = document.createElement;
    document.createElement = (t) => { const e = real.call(document, t); if (t === 'a') links.push(e); return e; };
    await downloadExport(format);
    document.createElement = real;
    assert.ok(calls.some(c => c.u.endsWith(path)), `${format} requests ${path}`);
    assert.equal(links.length, 1);
    assert.equal(links[0].download, name);
    assert.equal(created.length, 1);
    assert.deepEqual(revoked, ['blob:fake']);
    assert.equal(toastText(), `Downloaded ${name}`);
    assert.ok(!EMOJI.test(toastText()), toastText());
  }
});

test('a failed download shows the server reason plain, and nothing is left to free', async () => {
  created = []; revoked = [];
  failWith = { status: 500, message: 'Export failed' };
  await downloadExport('json');
  failWith = null;
  assert.equal(toastText(), 'Export failed');
  assert.equal(created.length, 0);
  assert.equal(revoked.length, 0);
});

test('"Preparing" is said before the request, with no arrow glyph', async () => {
  let said;
  const real = globalThis.fetch;
  globalThis.fetch = async (...a) => { said = toastText(); return real(...a); };
  await downloadExport('zip');
  globalThis.fetch = real;
  assert.equal(said, 'Preparing your export…');
});
