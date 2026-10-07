import { session } from '../state.js';
import { escapeHtml, today } from '../util.js';
import { toast } from '../ui.js';
import { api, storeTokens } from '../api.js';

// ═══════════════════════════════════════════════════════════════════════
// DATA EXPORT  (PLAN P2-5)
// ═══════════════════════════════════════════════════════════════════════

/** Human names for the dataset keys the backend publishes. */
const DATASET_LABEL = {
  'activities': 'Workouts',
  'exercises': 'Exercises (every set)',
  'meals': 'Meals',
  'goals': 'Goals',
  'body-metrics': 'Weight & BMI history',
  'sleep': 'Sleep logs',
  'water': 'Water logs',
  'pain': 'Pain logs',
  'personal-bests': 'Records by activity',
  'exercise-records': 'Records by movement'
};

/**
 * Fills the dataset picker from the server rather than from a copy of the list here.
 *
 * A hardcoded list is one that goes stale the first time a dataset is added on the
 * backend, and the failure is silent — the option simply never appears.
 */
async function loadExportDatasets() {
  const select = document.getElementById('export-dataset');
  if (!select || select.options.length) return;
  try {
    const meta = await api('/api/export/datasets');
    select.innerHTML = (meta.datasets || [])
      .map(d => `<option value="${escapeHtml(d)}">${escapeHtml(DATASET_LABEL[d] || d)}</option>`)
      .join('');
  } catch (_) {
    // The two whole-account buttons still work without it, so this stays quiet.
    select.innerHTML = '<option value="activities">Workouts</option>';
  }
}

/**
 * Downloads an export.
 *
 * Goes through api({ raw: true }) rather than fetch(), so the bearer header, the silent
 * token refresh and the dead-session check all still apply — a download that bypassed
 * them would be the one call in the app that logs you out by failing instead of
 * refreshing (AGENT.md §8).
 *
 * The filename is derived here rather than read from Content-Disposition: that header
 * isn't visible to JavaScript cross-origin unless the server explicitly exposes it, and
 * this page is served from a different origin than the API in every deployed setup.
 */
export async function downloadExport(format) {
  const dataset = format === 'csv'
    ? (document.getElementById('export-dataset').value || 'activities')
    : null;

  const path = format === 'zip' ? '/api/export/zip'
             : format === 'json' ? '/api/export/json'
             : `/api/export/csv/${dataset}`;

  const name = format === 'csv'
    ? `fitpilot-${dataset}-${today()}.csv`
    : `fitpilot-export-${today()}.${format}`;

  toast('⬇ Preparing your export…');
  try {
    const res = await api(path, { raw: true });
    const blob = await res.blob();

    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    // Revoking frees the blob; without it the whole export stays in memory for the
    // life of the tab, which for a few years of training data is not nothing.
    URL.revokeObjectURL(url);

    toast(`✅ Downloaded ${name}`);
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
}

// ═══════════════════════════════════════════════════════════════════════
// PROFILE
// ═══════════════════════════════════════════════════════════════════════

export async function loadProfile() {
  // Populated here so the picker is ready whenever the panel is on screen.
  loadExportDatasets();
  try {
    const p = await api(`/api/profile/${session.currentUser.id}`);
    session.currentUser = { ...session.currentUser, ...p };
    localStorage.setItem('fp_user', JSON.stringify(session.currentUser));
    document.getElementById('prof-first').value  = p.firstName || '';
    document.getElementById('prof-last').value   = p.lastName  || '';
    document.getElementById('prof-weight').value = p.weight ?? '';
    document.getElementById('prof-height').value = p.height ?? '';
    document.getElementById('prof-age').value    = p.age ?? '';
  } catch (err) {
    toast('❌ Could not load profile: ' + err.message, true);
  }
}

export async function handleUpdateProfile(e) {
  e.preventDefault();
  const payload = {
    firstName: document.getElementById('prof-first').value.trim(),
    lastName:  document.getElementById('prof-last').value.trim(),
    weight: parseFloat(document.getElementById('prof-weight').value) || null,
    height: parseFloat(document.getElementById('prof-height').value) || null,
    age: parseInt(document.getElementById('prof-age').value) || null
  };
  try {
    const p = await api(`/api/profile/${session.currentUser.id}`, { method: 'PUT', body: JSON.stringify(payload) });
    session.currentUser = { ...session.currentUser, ...p };
    localStorage.setItem('fp_user', JSON.stringify(session.currentUser));
    document.getElementById('sb-uname').textContent = [p.firstName, p.lastName].filter(Boolean).join(' ');
    toast('✅ Profile updated!');
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
  return false;
}

export async function handleChangePassword(e) {
  e.preventDefault();
  const payload = {
    currentPassword: document.getElementById('pwd-current').value,
    newPassword: document.getElementById('pwd-new').value
  };
  try {
    const res = await api(`/api/profile/${session.currentUser.id}/password`, { method: 'PUT', body: JSON.stringify(payload) });
    // The change revokes every refresh token, this device's included (PLAN P4-3). The
    // response carries a fresh pair; without storing it this tab would be signed out
    // the next time its access token lapsed.
    if (res && res.refreshToken) storeTokens(res.accessToken, res.refreshToken);
    document.getElementById('form-password').reset();
    toast('🔒 Password changed. Other devices have been signed out.');
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
  return false;
}
