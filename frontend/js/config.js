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
 * Resolution order: ?api=<url> → a previously remembered override → the built-in
 * default. The override exists so the deployed frontend can be pointed at a backend
 * without editing and redeploying this file (handy when the Render URL changes, or
 * for testing a branch deploy).
 */
function resolveApiBase() {
  let override = null;
  try {
    override = new URLSearchParams(location.search).get('api')
            || localStorage.getItem('fp_apiBase');
  } catch (_) { /* storage blocked — fall through to the default */ }

  if (override) {
    override = override.replace(/\/+$/, '');
    try { localStorage.setItem('fp_apiBase', override); } catch (_) {}
    return override;
  }
  return IS_LOCAL ? 'http://localhost:8080' : DEPLOYED_BACKEND_URL;
}

export const API_BASE = resolveApiBase();

/** True when deployed but nobody has filled in the backend URL yet. */
export const BACKEND_URL_UNSET = !IS_LOCAL && API_BASE === PLACEHOLDER_BACKEND_URL;
