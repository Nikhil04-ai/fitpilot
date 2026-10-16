import { session } from './state.js';
import { initials } from './util.js';
import { API_BASE } from './config.js';
import { toast } from './ui.js';
import { api, unreachableMessage } from './api.js';
import { wakeAwareFetch } from './wake.js';
import { nav, panelFromHash, resetPanelCache } from './nav.js';
import { refreshUnreadCount, startUnreadPolling, stopUnreadPolling } from './panels/notifications.js';

// ═══════════════════════════════════════════════════════════════════════
// AUTH
// ═══════════════════════════════════════════════════════════════════════

/**
 * Switches between the four auth views.
 *
 * 'login' and 'register' are the tabbed pair. 'forgot' and 'reset' are the two steps
 * of password recovery (PLAN P1-4) and show neither tab as selected, because neither
 * of them is a tab.
 */
export function switchAuthTab(which) {
  [['tab-login', 'login'], ['tab-register', 'register']].forEach(([id, name]) => {
    const on = which === name;
    const tab = document.getElementById(id);
    tab.classList.toggle('on', on);
    tab.setAttribute('aria-selected', String(on));
  });

  ['login', 'register', 'forgot', 'reset'].forEach(name => {
    const form = document.getElementById('form-' + name);
    if (form) form.style.display = name === which ? 'block' : 'none';
  });

  hideAuthError();
}
export function showAuthError(msg) {
  const el = document.getElementById('auth-error');
  el.textContent = msg; el.classList.add('show');
}
function hideAuthError() { document.getElementById('auth-error').classList.remove('show'); }

/**
 * POSTs login/register, which can't go through api(): a wrong password is a 401, and
 * api() treats a 401 as an expired session to refresh or end.
 *
 * `data` is null when the body isn't JSON — e.g. a proxy's HTML error page while a
 * sleeping Render instance wakes — so the caller shows the status instead of a JSON
 * parse error.
 */
async function postAuth(path, body) {
  let res;
  try {
    res = await wakeAwareFetch(API_BASE + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
  } catch (_) {
    throw new Error(unreachableMessage());
  }
  let data = null;
  try { data = await res.json(); } catch (_) { /* not JSON */ }
  return { res, data };
}

export async function handleLogin(e) {
  e.preventDefault();
  hideAuthError();
  const btn = document.getElementById('login-btn');
  btn.disabled = true; btn.textContent = 'Logging in…';

  const email    = document.getElementById('login-email').value.trim();
  const password = document.getElementById('login-password').value;

  try {
    const { res, data } = await postAuth('/api/auth/login', { email, password });
    if (!res.ok) throw new Error((data && data.error) || `Login failed (${res.status})`);

    session.accessToken  = data.accessToken;
    session.refreshToken = data.refreshToken;
    session.currentUser  = data.user;
    localStorage.setItem('fp_accessToken', session.accessToken);
    localStorage.setItem('fp_refreshToken', session.refreshToken);
    localStorage.setItem('fp_user', JSON.stringify(session.currentUser));

    enterApp();
  } catch (err) {
    showAuthError(err.message);
  } finally {
    btn.disabled = false; btn.textContent = '💪 Log In';
  }
}

export async function handleRegister(e) {
  e.preventDefault();
  hideAuthError();
  const btn = document.getElementById('register-btn');
  btn.disabled = true; btn.textContent = 'Creating account…';

  const payload = {
    firstName: document.getElementById('reg-first').value.trim(),
    lastName:  document.getElementById('reg-last').value.trim(),
    email:     document.getElementById('reg-email').value.trim(),
    password:  document.getElementById('reg-password').value,
    weight:    parseFloat(document.getElementById('reg-weight').value) || null,
    height:    parseFloat(document.getElementById('reg-height').value) || null,
    age:       parseInt(document.getElementById('reg-age').value) || null
  };

  try {
    const { res, data } = await postAuth('/api/auth/register', payload);
    if (!res.ok) {
      // A validation failure is a map of field -> message rather than { error }.
      const msg = data && typeof data === 'object' && !data.error
        ? Object.values(data).join(', ')
        : ((data && data.error) || `Registration failed (${res.status})`);
      throw new Error(msg);
    }

    toast('🎉 Account created! Logging you in…');
    document.getElementById('login-email').value = payload.email;
    document.getElementById('login-password').value = payload.password;
    switchAuthTab('login');
    await handleLogin({ preventDefault(){} });
  } catch (err) {
    showAuthError(err.message);
  } finally {
    btn.disabled = false; btn.textContent = '🚀 Create Account';
  }
}

// ── Password recovery (PLAN P1-4) ─────────────────────────────────────

/** The token from ?reset=..., held only while the reset form is open. */
let pendingResetToken = null;

/**
 * Asks for a reset link.
 *
 * Reports success unconditionally, matching the API: saying whether the address was
 * recognised would hand that answer to anyone with a list of email addresses.
 */
export async function handleForgotPassword(e) {
  e.preventDefault();
  const btn = document.getElementById('forgot-btn');
  btn.disabled = true; btn.textContent = 'Sending...';

  try {
    const res = await api('/api/auth/forgot-password', {
      method: 'POST',
      body: JSON.stringify({ email: document.getElementById('forgot-email').value.trim() })
    });
    toast(res.message || 'If an account exists for that address, a reset link is on its way.');
    switchAuthTab('login');
  } catch (err) {
    showAuthError(err.message);
  } finally {
    btn.disabled = false; btn.textContent = 'Send reset link';
  }
}

/** Completes a reset using the token the emailed link carried. */
export async function handleResetPassword(e) {
  e.preventDefault();

  const password = document.getElementById('reset-password').value;
  const confirm  = document.getElementById('reset-password2').value;
  if (password !== confirm) { showAuthError('Those two passwords do not match.'); return; }

  const btn = document.getElementById('reset-btn');
  btn.disabled = true; btn.textContent = 'Saving...';

  try {
    await api('/api/auth/reset-password', {
      method: 'POST',
      body: JSON.stringify({ token: pendingResetToken, newPassword: password })
    });

    // The reset revoked every refresh token server-side, so anything still in
    // storage is dead — clear it rather than leave a session that cannot be used.
    session.accessToken = session.refreshToken = session.currentUser = null;
    try {
      localStorage.removeItem('fp_accessToken');
      localStorage.removeItem('fp_refreshToken');
      localStorage.removeItem('fp_user');
    } catch (_) { /* storage blocked - nothing was saved to clear */ }

    clearResetTokenFromUrl();
    document.getElementById('form-reset').reset();
    toast('Password updated. Log in with your new one.');
    switchAuthTab('login');
  } catch (err) {
    showAuthError(err.message);
  } finally {
    btn.disabled = false; btn.textContent = 'Set new password';
  }
}

/**
 * Shows the reset form when the page was opened from an emailed link.
 *
 * Returns true when it took over the screen, so boot knows not to try restoring a
 * saved session on top of it.
 */
export function consumeResetLink() {
  let token = null;
  try { token = new URLSearchParams(location.search).get('reset'); } catch (_) {}
  if (!token) return false;

  pendingResetToken = token;
  // Out of the address bar straight away, not only after a successful reset: a link
  // that was abandoned, or whose reset failed, used to leave a live token in the URL
  // and in browser history until it expired. It is kept in memory for the form.
  stripResetFromUrl();
  document.getElementById('app-shell').classList.remove('show');
  document.getElementById('auth-screen').style.display = 'flex';
  switchAuthTab('reset');
  return true;
}

/**
 * Takes the token back out of the address bar once it has been read.
 *
 * A live reset token in a URL ends up in browser history and in the Referer of
 * anything the page loads, and stays usable until it expires.
 */
function clearResetTokenFromUrl() {
  pendingResetToken = null;
  stripResetFromUrl();
}

function stripResetFromUrl() {
  try {
    const url = new URL(location.href);
    url.searchParams.delete('reset');
    history.replaceState(null, '', url.toString());
  } catch (_) { /* older browser - leaving the URL alone is not fatal */ }
}

export function doLogout() {
  if (session.currentUser) {
    // The backend revokes the sessions of whoever proves who they are — the access
    // token if it's still valid, otherwise the refresh token being handed back.
    // It deliberately no longer accepts a bare userId (anyone could have logged
    // anyone else out with that).
    fetch(API_BASE + '/api/auth/logout', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(session.accessToken ? { 'Authorization': 'Bearer ' + session.accessToken } : {})
      },
      body: JSON.stringify({ refreshToken: session.refreshToken })
    }).catch(() => {});
  }
  resetPanelCache();
  stopUnreadPolling();
  session.accessToken = session.refreshToken = session.currentUser = null;
  localStorage.removeItem('fp_accessToken');
  localStorage.removeItem('fp_refreshToken');
  localStorage.removeItem('fp_user');
  document.getElementById('app-shell').classList.remove('show');
  document.getElementById('auth-screen').style.display = 'flex';
  // Drop the panel hash so the login screen isn't sitting on a stale #recovery URL.
  history.replaceState(null, '', location.pathname + location.search);
}

export function enterApp() {
  document.getElementById('auth-screen').style.display = 'none';
  document.getElementById('app-shell').classList.add('show');
  document.getElementById('fab-log').style.display = 'flex';

  document.getElementById('sb-av').textContent = initials(session.currentUser.firstName, session.currentUser.lastName);
  document.getElementById('sb-uname').textContent = [session.currentUser.firstName, session.currentUser.lastName].filter(Boolean).join(' ') || 'Athlete';
  document.getElementById('sb-uemail').textContent = session.currentUser.email || '';
  document.getElementById('topbar-streak').textContent = session.currentUser.currentStreak ?? 0;

  // The unread badge is in the header, so it has to be right whichever panel the
  // session opens on — not only once the notifications panel is visited.
  refreshUnreadCount();
  startUnreadPolling();

  // Honour a #panel in the URL so a bookmarked or shared link lands where it says
  // it will. Falls back to overview when the hash is absent or unrecognised.
  nav(panelFromHash() || 'overview');
}
