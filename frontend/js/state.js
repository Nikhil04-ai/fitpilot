// ── Session state ─────────────────────────────────────────────────────
// One object rather than separate `let`s: several modules reassign these (login,
// refresh, logout, a profile reload), and an imported binding is read-only.
export const session = {
  accessToken:  localStorage.getItem('fp_accessToken')  || null,
  refreshToken: localStorage.getItem('fp_refreshToken') || null,
  currentUser:  savedUser(),
  // true briefly while forceSessionExpired() is handling a dead session
  expiredInProgress: false
};

/**
 * The saved user, or null. This runs as the app loads, so a bad value in storage used
 * to throw here, fail every module that imports this one, and leave a login form whose
 * buttons did nothing — with no way to sign in again and overwrite it.
 */
function savedUser() {
  try {
    return JSON.parse(localStorage.getItem('fp_user') || 'null');
  } catch (_) {
    return null;
  }
}
