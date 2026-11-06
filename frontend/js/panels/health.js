import { session } from '../state.js';
import { emptyState, escapeHtml, figure, numberOrNull, skeletonFigures, skeletonLines, today, yesterday } from '../util.js';
import { closeModal, openModal, toast } from '../ui.js';
import { api } from '../api.js';
import { chartColor, chartPlaceholder, chartTheme, renderChart, setChartNote, shortAxisDate, showChartBox, timestampAxisLabels } from '../charts.js';

// ═══════════════════════════════════════════════════════════════════════
// HEALTH — BMI, Water, Sleep
// ═══════════════════════════════════════════════════════════════════════

export async function loadHealth() {
  await Promise.all([loadBmiRow(), loadWater(), loadSleep(), loadSleepPerformanceChart()]);
}

async function loadBmiRow() {
  const el = document.getElementById('health-bmi-row');
  el.innerHTML = skeletonFigures(3);
  try {
    const m = await api(`/api/health/metrics/${session.currentUser.id}`);
    el.innerHTML = [
      figure(m.bmi ?? '–', 'BMI', m.bmiCategory || ''),
      figure(m.weight ?? '–', 'Weight (kg)'),
      figure(m.height ?? '–', 'Height (cm)')
    ].join('');
    // The history behind these three numbers was already in the response and had
    // never been drawn (PLAN P1-3): same payload, no second request.
    renderBodyChart(m);
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load BMI data: ${escapeHtml(err.message)}</div>`;
    setChartNote('chart-body-note', `Couldn't load BMI data: ${err.message}`);
  }
}

/** Litres to one decimal place, from millilitres. */
const litres = ml => ((ml || 0) / 1000).toFixed(1);

async function loadWater() {
  const el = document.getElementById('water-content');
  el.innerHTML = skeletonLines(3);
  try {
    // Independent of each other; awaiting them in series doubled the wait.
    const [today, week] = await Promise.all([
      api(`/api/health/water/${session.currentUser.id}/today`),
      api(`/api/health/water/${session.currentUser.id}/week`)
    ]);
    const pct = Math.min(100, today.progressPercent || 0);
    const maxMl = Math.max(...week.map(d => d.totalMl || 0), today.goalMl || 1, 1);
    const day = (d, style) => new Date(d.date).toLocaleDateString('en', { weekday: style });
    const bars = week.map(d => {
      const h = maxMl > 0 ? Math.round(((d.totalMl || 0) / maxMl) * 100) : 0;
      return `<div class="wb" style="height:${h}%" title="${day(d, 'short')}: ${litres(d.totalMl)} L"></div>`;
    }).join('');
    const labels = week.map(d => `<div class="wl">${day(d, 'narrow')}</div>`).join('');
    // The bars are drawn, so the week is also said: a screen reader gets every day.
    const weekText = 'This week: ' + week.map(d => `${day(d, 'short')} ${litres(d.totalMl)} L`).join(', ');

    el.innerHTML = `
      <div class="metric">
        <span class="metric-v">${litres(today.totalMl)}</span>
        <span class="metric-u">of ${litres(today.goalMl)} L goal</span>
        <span class="metric-end">${pct.toFixed(0)}% today</span>
      </div>
      <div class="gb2 gb-lg"><div class="gf ${pct >= 100 ? 'done' : ''}" style="width:${pct}%"></div></div>
      <h4 class="mini-ttl">This week</h4>
      <div class="wviz" role="img" aria-label="${escapeHtml(weekText)}">${bars}</div>
      <div class="wlbs" aria-hidden="true">${labels}</div>
      <div class="water-add">
        <label class="visually-hidden" for="water-amount">Water to add, in ml</label>
        <input type="number" id="water-amount" min="1" max="5000" inputmode="numeric" placeholder="Amount in ml" value="250">
        <button type="button" class="btn btn-sec btn-sm" data-action="log-water">Add water</button>
      </div>
    `;
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load hydration data: ${escapeHtml(err.message)}</div>`;
  }
}

export async function logWater() {
  const amount = parseFloat(document.getElementById('water-amount').value);
  if (!amount || amount <= 0) { toast('Enter a valid amount', true); return; }
  try {
    await api(`/api/health/water/${session.currentUser.id}`, {
      method: 'POST', body: JSON.stringify({ amountMl: amount })
    });
    toast(`${amount} ml of water logged`);
    loadWater();
  } catch (err) {
    toast(err.message, true);
  }
}

async function loadSleep() {
  const el = document.getElementById('sleep-content');
  el.innerHTML = skeletonLines(4);
  try {
    const analysis = await api(`/api/health/sleep/${session.currentUser.id}/analysis`);
    const history = analysis.history || [];
    if (!history.length) {
      el.innerHTML = emptyState('', 'No sleep logged in the last 7 nights.',
        '<button type="button" class="btn btn-sec btn-sm" data-action="open-log-sleep">Log sleep</button>');
      return;
    }
    // The hours sit beside the bar, not inside it, where a short night clipped them.
    const rows = history.slice(-7).map(h => {
      const hrs = h.hoursSlept || 0;
      const pct = Math.min(100, (hrs / 9) * 100);
      const day = new Date(h.sleepDate).toLocaleDateString('en', { weekday: 'short' });
      return `
        <div class="slpr" role="listitem" aria-label="${day}: ${hrs} hours">
          <div class="slpd" aria-hidden="true">${day}</div>
          <div class="slpt" aria-hidden="true"><div class="slpf" style="width:${pct}%"></div></div>
          <div class="slph" aria-hidden="true">${hrs}h</div>
        </div>`;
    }).join('');
    el.innerHTML = `
      <div class="metric">
        <span class="metric-v">${analysis.weekAvgHours ?? '–'}</span>
        <span class="metric-u">hours a night, average</span>
      </div>
      <div class="slpbars" role="list">${rows}</div>
      ${analysis.insights && analysis.insights.length ? `<p class="note">${escapeHtml(analysis.insights[0])}</p>` : ''}
    `;
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load sleep data: ${escapeHtml(err.message)}</div>`;
  }
}

// ── Logging a night ────────────────────────────────────────────────────
// Until this form only the API could write sleep, so the card above and the
// sleep-vs-performance chart were always empty for someone using the app (PLAN P5-11).

export function openLogSleep() {
  const date = document.getElementById('sleep-date');
  date.max = today();
  if (!date.value) date.value = yesterday();
  openModal('modal-log-sleep');
}

/** "2026-09-24" plus n days. UTC throughout, so a DST change can't move the date. */
function addDays(isoDate, n) {
  const d = new Date(`${isoDate}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

/**
 * The form asks for clock times; the API wants LocalDateTimes. `sleepDate` is the
 * evening the person went to bed, so a bedtime from noon on is that evening and an
 * earlier one is past midnight. Waking is the first time the clock reads `wake` after
 * going to bed (or the morning after `sleepDate`, with no bedtime). Either time may be
 * blank, and comes back null.
 */
export function sleepTimes(sleepDate, bed, wake) {
  const bedDay = bed ? (bed >= '12:00' ? sleepDate : addDays(sleepDate, 1)) : null;
  const wakeDay = !wake ? null
    : !bed ? addDays(sleepDate, 1)
    : wake > bed ? bedDay : addDays(bedDay, 1);
  return {
    bedtime:  bed  ? `${bedDay}T${bed}:00`   : null,
    wakeTime: wake ? `${wakeDay}T${wake}:00` : null
  };
}

/** Hours between two LocalDateTimes to one decimal place, read as wall-clock time. */
export function hoursBetween(from, to) {
  const ms = Date.parse(`${to}Z`) - Date.parse(`${from}Z`);
  return Math.round(ms / 360000) / 10;
}

/** Bedtime or wake time changed: fill in the hours they add up to. */
export function fillSleepHours() {
  const { bedtime, wakeTime } = sleepTimes(
    document.getElementById('sleep-date').value || yesterday(),
    document.getElementById('sleep-bedtime').value,
    document.getElementById('sleep-waketime').value);
  if (bedtime && wakeTime) document.getElementById('sleep-hours').value = String(hoursBetween(bedtime, wakeTime));
}

export async function handleLogSleep(e) {
  e.preventDefault();
  const btn = document.getElementById('log-sleep-btn');
  const sleepDate = document.getElementById('sleep-date').value || yesterday();
  const { bedtime, wakeTime } = sleepTimes(sleepDate,
    document.getElementById('sleep-bedtime').value,
    document.getElementById('sleep-waketime').value);
  const payload = {
    sleepDate,
    hoursSlept: numberOrNull('sleep-hours'),
    qualityRating: parseInt(document.getElementById('sleep-quality').value) || null,
    bedtime,
    wakeTime,
    notes: document.getElementById('sleep-notes').value.trim() || null
  };
  btn.disabled = true; btn.textContent = 'Logging…';
  try {
    await api(`/api/health/sleep/${session.currentUser.id}`, { method: 'POST', body: JSON.stringify(payload) });
    closeModal('modal-log-sleep');
    document.getElementById('form-log-sleep').reset();
    toast('Sleep logged');
    loadSleep();
    loadSleepPerformanceChart();
  } catch (err) {
    toast(err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Log sleep';
  }
}

// ── Weight / BMI trend ─────────────────────────────────────────────────

/**
 * Weight over time, from the body-metrics history that /api/health/metrics was
 * already returning and nothing ever drew.
 *
 * One line on one axis. BMI used to ride on a second axis; but with height fixed it
 * is weight times a constant, so the two lines always moved together and the second
 * scale only invited reading a relationship into that. Each point's BMI is in its
 * tooltip, and the current one is in the note.
 *
 * History rows are only written when someone saves their metrics, so two points is
 * the minimum worth plotting: a single point is a dot, not a trend.
 */
function renderBodyChart(metrics) {
  const history = ((metrics && metrics.history) || []).filter(h => h.weight);
  if (history.length < 2) {
    chartPlaceholder('chart-body', 'chart-body-note',
      'Update your weight at least twice to see a trend here.');
    return;
  }
  showChartBox('chart-body');

  const t = chartTheme();
  const line = chartColor('--color-accent', '#C8F25A');

  renderChart('chart-body', {
    type: 'line',
    data: {
      labels: timestampAxisLabels(history.map(h => h.loggedAt)),
      datasets: [{
        label: 'Weight (kg)',
        data: history.map(h => h.weight),
        borderColor: line,
        backgroundColor: line,
        borderWidth: 2, pointRadius: 3, tension: 0
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
            label: item => `${item.raw} kg`,
            afterLabel: item => history[item.dataIndex].bmi != null ? `BMI ${history[item.dataIndex].bmi}` : ''
          }
        })
      },
      scales: {
        x: { grid: { display: false }, ticks: Object.assign({}, t.tick, { maxTicksLimit: 8 }) },
        // Deliberately not beginAtZero: a weight axis starting at 0 squashes every
        // real change into a flat line at the top of the chart. The note says so.
        y: { grid: { color: t.grid }, ticks: t.tick,
             title: Object.assign({ display: true, text: 'kg' }, t.title) }
      }
    }
  }, 'chart-body-note');

  const first = history[0], last = history[history.length - 1];
  const delta = last.weight - first.weight;
  const bmiNow = last.bmi != null ? ` Now ${last.bmi} BMI (${metrics.bmiCategory || 'no category'}).` : '';
  setChartNote('chart-body-note',
    `${delta >= 0 ? '+' : ''}${delta.toFixed(1)} kg across ${history.length} measurements.${bmiNow} `
    + 'The axis starts near your weight, not at zero, so small changes are visible.');
}

// ── Sleep vs next-day output ───────────────────────────────────────────

/**
 * Each logged night plotted against what was burned the following day.
 *
 * Rest days are drawn muted at zero so they read as "didn't train" rather than
 * "trained badly" — the server leaves them out of the correlation for the same
 * reason, and the note under the chart is the server's own summary.
 */
async function loadSleepPerformanceChart() {
  try {
    const data = await api(`/api/health/sleep/${session.currentUser.id}/performance?days=30`);
    const points = data.points || [];

    if (!points.length) {
      chartPlaceholder('chart-sleepperf', 'chart-sleepperf-note',
        'Log a few nights of sleep to see how they line up with your training.');
      return;
    }
    showChartBox('chart-sleepperf');

    const t = chartTheme();
    const trained = points.filter(p => p.trained);
    const rested  = points.filter(p => !p.trained);

    renderChart('chart-sleepperf', {
      type: 'scatter',
      data: {
        datasets: [
          {
            label: 'Trained next day',
            data: trained.map(p => ({ x: p.hoursSlept, y: p.calories, d: p.workoutDate })),
            backgroundColor: chartColor('--color-accent', '#C8F25A'),
            pointRadius: 5, pointHoverRadius: 7
          },
          {
            label: 'Rest day',
            data: rested.map(p => ({ x: p.hoursSlept, y: 0, d: p.workoutDate })),
            backgroundColor: chartColor('--color-mark', '#6B7280'),
            pointRadius: 4, pointHoverRadius: 6
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: t.legend,
          tooltip: Object.assign({}, t.tooltip, {
            callbacks: {
              label: item => `${item.raw.x}h sleep → ${item.raw.y} kcal on ${shortAxisDate(item.raw.d)}`
            }
          })
        },
        scales: {
          x: { beginAtZero: false, grid: { color: t.grid }, ticks: t.tick,
               title: Object.assign({ display: true, text: 'hours slept' }, t.title) },
          y: { beginAtZero: true, grid: { color: t.grid }, ticks: t.tick,
               title: Object.assign({ display: true, text: 'kcal next day' }, t.title) }
        }
      }
    }, 'chart-sleepperf-note');

    setChartNote('chart-sleepperf-note', data.summary || '');
  } catch (err) {
    setChartNote('chart-sleepperf-note', `Couldn't load sleep-vs-performance data: ${err.message}`);
  }
}
