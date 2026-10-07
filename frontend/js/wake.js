import { IS_LOCAL } from './config.js';

// ═══════════════════════════════════════════════════════════════════════
// WAKE NOTICE — "the server is waking up", for Render's free tier
//
// A free Render instance sleeps after 15 minutes without traffic and takes 30–60s
// to answer the first request after that. With nothing on screen, a login that
// hangs for 40 seconds looks broken, and people retry or leave.
//
// A slow request alone doesn't mean a cold start: a Gemini call can take several
// seconds against a server that is wide awake. So the notice is only armed when the
// server could actually be asleep — nothing heard from it in SLEEP_AFTER_MS, which
// includes "never, since this page loaded" — and only shown once such a request has
// been pending for NOTICE_DELAY_MS. The 60s unread-count poll keeps an open, visible
// tab's last-heard time fresh, so an active session never sees it.
// ═══════════════════════════════════════════════════════════════════════

/** Render's idle timeout is 15 minutes; a minute of margin either side of it. */
export const SLEEP_AFTER_MS = 14 * 60 * 1000;
/** Long enough that a warm server never triggers it, short enough to beat the urge to retry. */
export const NOTICE_DELAY_MS = 3000;

/**
 * The tracker itself, with its clock, timers and DOM effects passed in so the logic
 * can be tested without a browser. The app uses the default instance below.
 */
export function createWakeTracker({
  enabled,
  now = () => Date.now(),
  setTimer = (fn, ms) => setTimeout(fn, ms),
  clearTimer = id => clearTimeout(id),
  show,
  hide,
  delayMs = NOTICE_DELAY_MS,
  sleepAfterMs = SLEEP_AFTER_MS
}) {
  let lastHeardAt = null;   // null: no response yet on this page
  let inFlight = 0;
  let timer = null;
  let shown = false;

  function settle() {
    if (timer !== null) { clearTimer(timer); timer = null; }
    if (shown) { shown = false; hide(); }
  }

  return {
    /** Wraps one request. Resolves/rejects exactly as the request does. */
    track(request) {
      const mayBeAsleep = enabled && (lastHeardAt === null || now() - lastHeardAt > sleepAfterMs);
      inFlight++;
      if (mayBeAsleep && timer === null && !shown) {
        timer = setTimer(() => { timer = null; shown = true; show(); }, delayMs);
      }
      return request.then(
        res => {
          // Any response at all means the instance is up, so the notice goes even if
          // other requests are still pending — they'll be quick now.
          lastHeardAt = now();
          inFlight--;
          settle();
          return res;
        },
        err => {
          inFlight--;
          // A network failure says nothing about whether it's awake. Keep the notice
          // while anything else is still waiting; the failed call shows its own error.
          if (inFlight === 0) settle();
          throw err;
        });
    },
    get isShown() { return shown; }
  };
}

function noticeEl() { return document.getElementById('wake-notice'); }

const tracker = createWakeTracker({
  // Locally the backend doesn't sleep, so a slow request there is just slow.
  enabled: !IS_LOCAL,
  show: () => noticeEl()?.classList.add('show'),
  hide: () => noticeEl()?.classList.remove('show')
});

/** fetch(), plus the wake notice. Every call to the backend goes through this. */
export function wakeAwareFetch(url, options) {
  return tracker.track(fetch(url, options));
}
