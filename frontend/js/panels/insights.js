import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonLines } from '../util.js';
import { api } from '../api.js';
import { chartColor, chartPlaceholder, chartTheme, renderChart, setChartNote, shortAxisDate, showChartBox } from '../charts.js';

// ═══════════════════════════════════════════════════════════════════════
// STATS & INSIGHTS — monthly trends, peak workout time, plateau detection
// ═══════════════════════════════════════════════════════════════════════

export async function loadInsights() {
  await Promise.all([loadMonthlyStats(), loadTimingStats(), loadVolumeChart()]);
}

async function loadMonthlyStats() {
  const el = document.getElementById('monthly-stats');
  el.innerHTML = skeletonLines(3);
  try {
    const m = await api(`/api/stats/monthly/${session.currentUser.id}`);
    el.innerHTML = `
      <div class="pstats" style="grid-template-columns:1fr 1fr">
        <div class="ps"><div class="psv">${m.totalCalories ?? 0}</div><div class="psk">Calories</div></div>
        <div class="ps"><div class="psv">${m.activityCount ?? 0}</div><div class="psk">Workouts</div></div>
        <div class="ps"><div class="psv">${m.totalDurationMinutes ?? 0}</div><div class="psk">Minutes</div></div>
        <div class="ps"><div class="psv">${m.weekOverWeekCalorieChange ?? '—'}</div><div class="psk">WoW Change</div></div>
      </div>
      <div style="margin-top:12px;font-size:12px;color:var(--muted)">Most frequent: ${(m.mostFrequentActivityType || '—').replace('_', ' ')}</div>
    `;
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load monthly stats: ${escapeHtml(err.message)}</div>`;
  }
}

async function loadTimingStats() {
  const el = document.getElementById('timing-stats');
  el.innerHTML = skeletonLines(3);
  try {
    const t = await api(`/api/stats/timing/${session.currentUser.id}`);
    if (t.message) {
      el.innerHTML = emptyState('⏰', t.message);
      chartPlaceholder('chart-hours', 'chart-hours-note', t.message);
      return;
    }
    el.innerHTML = `
      <div style="font-family:var(--fm);font-size:26px;color:var(--blue)">${t.peakTimeWindow || '—'}</div>
      <div style="font-size:12px;color:var(--muted);margin:6px 0 12px">${t.completionRateAtPeak || ''} of workouts happen in this window</div>
      <div style="background:rgba(14,165,255,.06);border:1px solid rgba(14,165,255,.15);border-radius:9px;padding:11px 14px;font-size:12px;color:var(--silver)">💡 ${escapeHtml(t.suggestion || '')}</div>
    `;
    // Same payload, no second request — the histogram is the distribution behind
    // the sentence this card just rendered.
    renderHoursChart(t);
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load timing data: ${escapeHtml(err.message)}</div>`;
    setChartNote('chart-hours-note', `Couldn't load timing data: ${err.message}`);
  }
}

export async function checkPlateau() {
  const type = document.getElementById('plateau-type').value;
  const el = document.getElementById('plateau-result');
  el.innerHTML = `<div class="loading-state"><div class="spinner"></div>Checking…</div>`;
  try {
    const r = await api(`/api/stats/plateau/${session.currentUser.id}?activityType=${type}`);
    if (r.status === 'insufficient_data') {
      el.innerHTML = `<div style="font-size:12px;color:var(--muted);padding:8px 0">${escapeHtml(r.message)}</div>`;
      return;
    }
    const plateaued = r.plateauDetected;
    el.innerHTML = `
      <div style="display:flex;gap:24px;margin-bottom:14px">
        <div><div style="font-family:var(--fm);font-size:20px;color:var(--blue)">${r.recentAverageCalories}</div><div class="psk">Recent avg cal</div></div>
        <div><div style="font-family:var(--fm);font-size:20px;color:var(--muted)">${r.previousAverageCalories}</div><div class="psk">Previous avg cal</div></div>
        <div><div style="font-family:var(--fm);font-size:20px;color:${plateaued?'var(--red)':'var(--cyan)'}">${r.improvementPercent}</div><div class="psk">Change</div></div>
      </div>
      ${plateaued
        ? `<div style="background:rgba(255,184,0,.07);border:1px solid rgba(255,184,0,.2);border-radius:9px;padding:11px 14px;font-size:12px;color:var(--silver)">⚠️ ${escapeHtml(r.recommendation || 'Plateau detected.')}</div>`
        : `<div style="background:rgba(0,255,209,.07);border:1px solid rgba(0,255,209,.2);border-radius:9px;padding:11px 14px;font-size:12px;color:var(--silver)">✅ No plateau — you're progressing!</div>`}
    `;
  } catch (err) {
    el.innerHTML = `<div class="error-state">${escapeHtml(err.message)}</div>`;
  }
}

// ── Training volume ────────────────────────────────────────────────────

/**
 * Calories per day as bars, with minutes as a line on a second axis.
 *
 * The server returns rest days as explicit zeros, so the gaps in someone's week
 * are part of the picture rather than smoothed over.
 */
async function loadVolumeChart() {
  try {
    const series = await api(`/api/stats/daily/${session.currentUser.id}?days=30`);
    const totalCalories = series.reduce((sum, d) => sum + (d.calories || 0), 0);

    if (!totalCalories) {
      chartPlaceholder('chart-volume', 'chart-volume-note',
        'Nothing logged in the last 30 days yet — log a workout and this fills in.');
      return;
    }
    showChartBox('chart-volume');

    const t = chartTheme();
    const blue = chartColor('--blue', '#0EA5FF');
    const cyan = chartColor('--cyan', '#00FFD1');

    renderChart('chart-volume', {
      data: {
        labels: series.map(d => shortAxisDate(d.date)),
        datasets: [
          {
            type: 'bar',
            label: 'Calories',
            data: series.map(d => d.calories),
            backgroundColor: 'rgba(14,165,255,.45)',
            borderColor: blue,
            borderWidth: 1,
            borderRadius: 3,
            yAxisID: 'y'
          },
          {
            type: 'line',
            label: 'Minutes',
            data: series.map(d => d.minutes),
            borderColor: cyan,
            backgroundColor: cyan,
            borderWidth: 2,
            pointRadius: 0,
            pointHoverRadius: 4,
            // No spline. Smoothing a daily series that is mostly zeros bows the line
            // up across rest days and draws training that did not happen.
            tension: 0,
            yAxisID: 'y1'
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: { legend: t.legend, tooltip: t.tooltip },
        scales: {
          x:  { grid: { display: false }, ticks: Object.assign({}, t.tick, { maxTicksLimit: 10 }) },
          y:  { position: 'left',  beginAtZero: true, grid: { color: t.grid }, ticks: t.tick,
                title: Object.assign({ display: true, text: 'kcal' }, t.title) },
          y1: { position: 'right', beginAtZero: true, grid: { display: false }, ticks: t.tick,
                title: Object.assign({ display: true, text: 'min' }, t.title) }
        }
      }
    }, 'chart-volume-note');

    const activeDays = series.filter(d => d.workouts > 0).length;
    setChartNote('chart-volume-note',
      `${activeDays} of the last ${series.length} days had a session — ${totalCalories.toLocaleString()} kcal in total.`);
  } catch (err) {
    setChartNote('chart-volume-note', `Couldn't load volume data: ${err.message}`);
  }
}

// ── Peak-hour histogram ────────────────────────────────────────────────

/**
 * Workout count per hour of day.
 *
 * Draws the shape behind the "you train at 07:00" sentence the card above already
 * shows — one dominant hour and a scattered week produce the same sentence but
 * very different bars. Takes the distribution the timing endpoint now returns, so
 * it costs no extra request.
 */
function renderHoursChart(timing) {
  const hours = timing && timing.hourlyDistribution;
  if (!Array.isArray(hours) || !hours.some(n => n > 0)) {
    chartPlaceholder('chart-hours', 'chart-hours-note',
      'Not enough workouts with a start time in the last 30 days to show a pattern.');
    return;
  }
  showChartBox('chart-hours');

  const t = chartTheme();
  const peak = timing.peakWorkoutHour;
  const blue = chartColor('--blue', '#0EA5FF');
  const cyan = chartColor('--cyan', '#00FFD1');

  renderChart('chart-hours', {
    type: 'bar',
    data: {
      labels: hours.map((_, h) => String(h).padStart(2, '0')),
      datasets: [{
        label: 'Workouts',
        data: hours,
        // The peak hour is the point of the chart, so it gets the accent colour
        // and every other bar recedes.
        backgroundColor: hours.map((_, h) => h === peak ? cyan : 'rgba(14,165,255,.30)'),
        borderColor:     hours.map((_, h) => h === peak ? cyan : blue),
        borderWidth: 1,
        borderRadius: 3
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: Object.assign({}, t.tooltip, {
          callbacks: {
            title: items => `${items[0].label}:00–${String(Number(items[0].label) + 1).padStart(2, '0')}:00`,
            label: item => `${item.parsed.y} workout${item.parsed.y === 1 ? '' : 's'}`
          }
        })
      },
      scales: {
        x: { grid: { display: false }, ticks: Object.assign({}, t.tick, { maxTicksLimit: 12 }),
             title: Object.assign({ display: true, text: 'hour of day' }, t.title) },
        y: { beginAtZero: true, grid: { color: t.grid },
             ticks: Object.assign({}, t.tick, { precision: 0 }) }
      }
    }
  }, 'chart-hours-note');

  setChartNote('chart-hours-note',
    `${timing.activitiesAnalysed} workouts analysed. ${timing.completionRateAtPeak} of them start in the highlighted hour.`);
}
