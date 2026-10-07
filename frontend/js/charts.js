// ═══════════════════════════════════════════════════════════════════════
// CHARTS
//
// Every number these draw was already being computed on the server and shown
// as a sentence (PLAN P1-3). Chart.js comes from a CDN tag in index.html, so
// there is still no build step.
// ═══════════════════════════════════════════════════════════════════════

/**
 * Live chart instances, keyed by canvas id.
 *
 * Chart.js refuses to attach a second chart to a canvas that already has one
 * ("Canvas is already in use"), and panels here re-render on every navigation —
 * so the previous instance has to be destroyed first, not just overwritten.
 */
const chartRegistry = {};

/** Colours pulled from the stylesheet so the charts can't drift from the theme. */
export function chartColor(token, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(token).trim();
  return v || fallback;
}

/** Chart.js options shared by all four charts: dark grid, mono ticks, themed tooltip. */
export function chartTheme() {
  const muted  = chartColor('--muted', '#4A5568');
  const silver = chartColor('--silver', '#8899AA');
  return {
    grid:  'rgba(136,153,170,.10)',
    tick:  { color: muted, font: { family: "'JetBrains Mono', monospace", size: 10 } },
    title: { color: silver, font: { family: "'JetBrains Mono', monospace", size: 10 } },
    legend: { labels: { color: silver, boxWidth: 10, font: { size: 11 } } },
    tooltip: {
      backgroundColor: chartColor('--panel3', '#1B2530'),
      borderColor: 'rgba(136,153,170,.20)',
      borderWidth: 1,
      titleColor: chartColor('--white', '#F1F5FF'),
      bodyColor: silver,
      titleFont: { family: "'JetBrains Mono', monospace", size: 11 },
      bodyFont:  { family: "'Inter', sans-serif", size: 12 },
      padding: 10,
      displayColors: false
    }
  };
}

/**
 * Creates (or replaces) a chart on a canvas.
 *
 * Returns false and writes a short line into the note element when Chart.js
 * isn't there — a blocked or offline CDN then costs the chart, not the panel.
 */
export function renderChart(canvasId, config, noteId) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return false;

  if (typeof Chart === 'undefined') {
    setChartNote(noteId, 'Charts need Chart.js, which could not be loaded. The figures above are unaffected.');
    return false;
  }

  destroyChart(canvasId);
  chartRegistry[canvasId] = new Chart(canvas.getContext('2d'), config);
  return true;
}

function destroyChart(canvasId) {
  if (chartRegistry[canvasId]) {
    chartRegistry[canvasId].destroy();
    delete chartRegistry[canvasId];
  }
}

/** Hides a chart's box and explains why, for the "nothing logged yet" case. */
export function chartPlaceholder(canvasId, noteId, message) {
  destroyChart(canvasId);
  const canvas = document.getElementById(canvasId);
  if (canvas && canvas.parentElement) canvas.parentElement.style.display = 'none';
  setChartNote(noteId, message);
}

export function showChartBox(canvasId) {
  const canvas = document.getElementById(canvasId);
  if (canvas && canvas.parentElement) canvas.parentElement.style.display = '';
}

export function setChartNote(noteId, text) {
  const el = noteId && document.getElementById(noteId);
  if (el) el.textContent = text || '';
}

/** "2026-09-21" -> "21 Sep". Chart axes have no room for a full ISO date. */
export function shortAxisDate(iso) {
  const d = new Date(String(iso).slice(0, 10) + 'T00:00:00');
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleDateString('en', { day: 'numeric', month: 'short' });
}

/**
 * Axis labels for a series of timestamps that may not be one-per-day.
 *
 * Body metrics are written whenever someone saves their profile, so several can
 * land on the same date — which as a plain date axis reads "22 Sep" five times
 * in a row and tells you nothing about the order. When any date repeats, the
 * whole axis switches to including the time, so it stays uniform.
 */
export function timestampAxisLabels(timestamps) {
  const hasDuplicates = arr => new Set(arr).size !== arr.length;

  let labels = timestamps.map(t => shortAxisDate(t));
  if (!hasDuplicates(labels)) return labels;

  // Several on the same day — add the clock time.
  labels = timestamps.map((t, i) => {
    const d = new Date(String(t).replace(' ', 'T'));
    if (isNaN(d.getTime())) return labels[i];
    return `${shortAxisDate(t)} ${d.toLocaleTimeString('en', { hour: '2-digit', minute: '2-digit', hour12: false })}`;
  });
  if (!hasDuplicates(labels)) return labels;

  // Still colliding — several within the same minute. Numbering the repeats is
  // the only thing left that keeps every tick distinct and in order; an axis with
  // six identical ticks tells the reader nothing about which point is which.
  const seen = {};
  const total = labels.reduce((acc, l) => { acc[l] = (acc[l] || 0) + 1; return acc; }, {});
  return labels.map(l => {
    if (total[l] === 1) return l;
    seen[l] = (seen[l] || 0) + 1;
    return `${l} (${seen[l]})`;
  });
}
