import { session } from './state.js';
import { API_BASE, BACKEND_URL_UNSET, IS_LOCAL } from './config.js';
import { showAuthError, switchAuthTab } from './auth.js';
import { invalidateAllPanels, resetPanelCache } from './nav.js';
import { stopUnreadPolling } from './panels/notifications.js';
import { wakeAwareFetch } from './wake.js';

// ═══════════════════════════════════════════════════════════════════════
// API CLIENT — every backend call goes through here
// ═══════════════════════════════════════════════════════════════════════

/**
 * What to tell someone when fetch() itself failed, i.e. no response at all.
 *
 * Distinguishes "the backend URL was never configured" from "the backend is down":
 * the placeholder resolves to a host that doesn't exist, so every call failed with a
 * misleading "is the backend running?" message on the deployed site. Login and
 * register use this too — they used to say "localhost:8080" on GitHub Pages as well.
 */
export function unreachableMessage() {
  if (BACKEND_URL_UNSET) {
    return 'This deployment has no backend URL configured yet. '
      + 'Set DEPLOYED_BACKEND_URL in js/config.js, or open this page with ?api=https://your-backend-url';
  }
  return IS_LOCAL
    ? 'Cannot reach the server. Is the backend running on localhost:8080?'
    : 'Cannot reach the server at ' + API_BASE + '. It may be starting up — Render free tier '
      + 'instances sleep after 15 minutes and take 30–60s to wake. Try again in a moment.';
}

/**
 * Core fetch wrapper.
 * - Adds Authorization header automatically if we have a token
 * - Tries a silent token refresh on 401, then retries once
 * - Throws a normal Error with the backend's message on failure
 */
export async function api(path, options = {}) {
  const doFetch = async (token) => {
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    return wakeAwareFetch(API_BASE + path, { ...options, headers });
  };

  let res;
  try {
    res = await doFetch(session.accessToken);
  } catch (networkErr) {
    throw new Error(unreachableMessage());
  }

  // Access token expired — try one silent refresh, then retry the original call
  if (res.status === 401 && path !== '/api/auth/refresh') {
    const refreshed = session.refreshToken ? await tryRefreshToken() : false;
    if (refreshed) {
      res = await doFetch(session.accessToken);
    }
    // Still 401 after refreshing (or with no refresh token left to try): the
    // session is genuinely over — revoked on another device, refresh token past
    // its 30 days, or the server's JWT secret changed. Send the person to the
    // login screen with a reason instead of letting every panel show its own
    // "Authentication required" error.
    if (res.status === 401 && session.accessToken) {
      forceSessionExpired('Your session has expired. Please log in again.');
    }
  }

  if (res.status === 204) return null; // No Content

  // A file download needs the Response itself, not a parsed body — but it still wants
  // everything above it: the bearer header, the silent refresh, the dead-session check.
  // Hence an option here rather than a second fetch() elsewhere, which would quietly
  // opt the export routes out of all three (AGENT.md §8). Only success returns early:
  // a failed download falls through to the error handling below, which is where the
  // dead-session 409 is recognised (PLAN P4-1 — it used to throw before reaching it).
  if (options.raw && res.ok) return res;

  let body = null;
  try { body = await res.json(); } catch (_) { /* empty body */ }

  if (!res.ok) {
    const msg = (body && (body.error || body.message)) || `Request failed (${res.status})`;

    // Backend returns 409 with this specific message when the saved session
    // points to a user id that no longer exists in the database (e.g. the DB
    // was reset AFTER the browser already had a session saved, so the
    // one-time boot check never re-ran). Match the account-specific phrase
    // only — a 409 about some OTHER missing record (e.g. a stale activity or
    // challenge reference) should NOT force a full logout, since the
    // person's account itself is still perfectly valid in that case.
    if (res.status === 409 && msg.toLowerCase().includes('account that no longer exists')) {
      forceSessionExpired(msg);
    }

    throw new Error(msg);
  }

  // Any successful write invalidates the panel freshness cache. Doing it here
  // rather than at each call site means a new mutation can't forget to: one
  // workout moves stats, streaks, goals, bests, recovery and challenge scores,
  // so there is no useful per-panel mapping to maintain anyway.
  const method = (options.method || 'GET').toUpperCase();
  if (method !== 'GET' && method !== 'HEAD') invalidateAllPanels();

  return body;
}

/** Clears a dead session and sends the person back to login with a clear reason. */
export function forceSessionExpired(reason) {
  session.expiredInProgress = true;
  resetPanelCache();
  stopUnreadPolling();
  session.accessToken = session.refreshToken = session.currentUser = null;
  localStorage.removeItem('fp_accessToken');
  localStorage.removeItem('fp_refreshToken');
  localStorage.removeItem('fp_user');
  document.getElementById('app-shell').classList.remove('show');
  document.getElementById('auth-screen').style.display = 'flex';
  switchAuthTab('login');
  showAuthError(reason || 'Your session is no longer valid. Please log in again.');
  // Re-enable toasts after other in-flight calls (from the same failed page
  // load) have had a chance to fail and be silently suppressed above.
  setTimeout(() => { session.expiredInProgress = false; }, 1500);
}

async function tryRefreshToken() {
  try {
    const res = await wakeAwareFetch(API_BASE + '/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: session.refreshToken })
    });
    if (!res.ok) return false;
    const data = await res.json();
    storeTokens(data.accessToken, data.refreshToken);
    return true;
  } catch (_) {
    return false;
  }
}

/** Replaces the session's token pair, in memory and in localStorage. */
export function storeTokens(accessToken, refreshToken) {
  session.accessToken  = accessToken;
  session.refreshToken = refreshToken;
  localStorage.setItem('fp_accessToken', accessToken);
  localStorage.setItem('fp_refreshToken', refreshToken);
}
