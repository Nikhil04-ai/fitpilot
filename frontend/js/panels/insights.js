import { session } from '../state.js';
import { emptyState, escapeHtml, figure, skeletonFigures, skeletonLines } from '../util.js';
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
  el.innerHTML = `<div class="figs">${skeletonFigures(4)}</div>`;
  try {
    const m = await api(`/api/stats/monthly/${session.currentUser.id}`);
    const type = m.mostFrequentActivityType;
    const most = !type || type === 'N/A' ? 'No workouts in the last 30 days.'
      : `Most frequent: ${type.replace('_', ' ').toLowerCase()}.`;
    el.innerHTML = `
      <div class="figs">${[
        figure(m.activityCount ?? 0, 'Workouts'),
        figure(m.totalCalories ?? 0, 'Calories'),
        figure(m.totalDurationMinutes ?? 0, 'Minutes'),
        // null when the week before had no calories: no percentage can be given.
        figure(m.weekOverWeekCalorieChange ?? '–', 'Calories vs the week before')
      ].join('')}</div>
      <p class="figs-note">${escapeHtml(most)}</p>`;
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
      el.innerHTML = emptyState('', t.message);
      chartPlaceholder('chart-hours', 'chart-hours-note', t.message);
      return;
    }
    el.innerHTML = `
      <div class="metric metric-stack">
        <span class="metric-v">${escapeHtml(t.peakTimeWindow || '–')}</span>
        <span class="metric-u">${escapeHtml(t.completionRateAtPeak || '')} of workouts happen in this window</span>
      </div>
      ${t.suggestion ? `<p class="note">${escapeHtml(t.suggestion)}</p>` : ''}
    `;
    // Same payload, no second request: the histogram is the distribution behind
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
  el.innerHTML = `<div class="loading-state">Checking…</div>`;
  try {
    const r = await api(`/api/stats/plateau/${session.currentUser.id}?activityType=${type}`);
    if (r.status === 'insufficient_data') {
      el.innerHTML = `<p class="field-hint">${escapeHtml(r.message)}</p>`;
      return;
    }
    // The verdict is said in words; the change figure is not coloured to say it again.
    const plateaued = r.plateauDetected;
    el.innerHTML = `
      <dl class="body-figs plateau-figs">
        <div><dt>Recent average</dt><dd>${escapeHtml(String(r.recentAverageCalories))}<span class="u">kcal</span></dd></div>
        <div><dt>Previous average</dt><dd>${escapeHtml(String(r.previousAverageCalories))}<span class="u">kcal</span></dd></div>
        <div><dt>Change</dt><dd>${escapeHtml(String(r.improvementPercent))}</dd></div>
      </dl>
      ${plateaued
        ? `<p class="note note-warn"><strong>Plateau detected.</strong> ${escapeHtml(r.recommendation || '')}</p>`
        : `<p class="note">No plateau. You're progressing.</p>`}
    `;
  } catch (err) {
    el.innerHTML = `<div class="error-state">${escapeHtml(err.message)}</div>`;
  }
}

// ── Training volume ────────────────────────────────────────────────────

/**
 * Calories per day as bars, on one axis.
 *
 * Minutes used to be a line on a second, right-hand axis. Two scales on one chart can
 * be set to make any two lines look related, and here they mostly are anyway: most
 * calories are estimated from minutes. Each day's minutes and sessions are in its
 * tooltip, and the month's minutes are a figure above.
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
        'Nothing logged in the last 30 days yet. Log a workout and this fills in.');
      return;
    }
    showChartBox('chart-volume');

    const t = chartTheme();
    const bar = chartColor('--color-accent', '#C8F25A');
    const barFill = chartColor('--chart-fill', 'rgba(200, 242, 90, 0.45)');

    renderChart('chart-volume', {
      type: 'bar',
      data: {
        labels: series.map(d => shortAxisDate(d.date)),
        datasets: [{
          label: 'Calories',
          data: series.map(d => d.calories),
          backgroundColor: barFill,
          borderColor: bar,
          borderWidth: 1,
          borderRadius: 2
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: Object.assign({}, t.tooltip, {
            callbacks: {
              label: item => `${item.parsed.y} kcal`,
              afterLabel: item => {
                const d = series[item.dataIndex];
                return d.workouts ? `${d.minutes || 0} min, ${d.workouts} session${d.workouts === 1 ? '' : 's'}` : 'Rest day';
              }
            }
          })
        },
        scales: {
          x: { grid: { display: false }, ticks: Object.assign({}, t.tick, { maxTicksLimit: 10 }) },
          y: { beginAtZero: true, grid: { color: t.grid }, ticks: t.tick,
               title: Object.assign({ display: true, text: 'kcal' }, t.title) }
        }
      }
    }, 'chart-volume-note');

    const activeDays = series.filter(d => d.workouts > 0).length;
    setChartNote('chart-volume-note',
      `${activeDays} of the last ${series.length} days had a session, ${totalCalories.toLocaleString()} kcal in total.`);
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
  const accent = chartColor('--color-accent', '#C8F25A');
  const rest = chartColor('--color-mark', '#6B7280');

  renderChart('chart-hours', {
    type: 'bar',
    data: {
      labels: hours.map((_, h) => String(h).padStart(2, '0')),
      datasets: [{
        label: 'Workouts',
        data: hours,
        // The peak hour is the point of the chart, so it gets the accent colour
        // and every other bar recedes.
        backgroundColor: hours.map((_, h) => h === peak ? accent : rest),
        borderWidth: 0,
        borderRadius: 2
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: Object.assign({}, t.tooltip, {
          callbacks: {
            title: items => `${items[0].label}:00 to ${String(Number(items[0].label) + 1).padStart(2, '0')}:00`,
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
