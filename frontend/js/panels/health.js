import { session } from '../state.js';
import { emptyState, escapeHtml, numberOrNull, skeletonLines, skeletonStats, today, yesterday } from '../util.js';
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
  el.innerHTML = skeletonStats(3);
  try {
    const m = await api(`/api/health/metrics/${session.currentUser.id}`);
    el.innerHTML = `
      <div class="card smc"><div class="smi b">⚖️</div><div><div class="smv">${m.bmi ?? '—'}</div><div class="smk">BMI · ${m.bmiCategory || ''}</div></div></div>
      <div class="card smc"><div class="smi c">💪</div><div><div class="smv">${m.weight ?? '—'}</div><div class="smk">Weight (kg)</div></div></div>
      <div class="card smc"><div class="smi p">📏</div><div><div class="smv">${m.height ?? '—'}</div><div class="smk">Height (cm)</div></div></div>
    `;
    // The history behind these three numbers was already in the response and had
    // never been drawn (PLAN P1-3) — same payload, no second request.
    renderBodyChart(m);
  } catch (err) {
    el.innerHTML = `<div class="error-state" style="grid-column:1/-1">Couldn't load BMI data: ${escapeHtml(err.message)}</div>`;
    setChartNote('chart-body-note', `Couldn't load BMI data: ${err.message}`);
  }
}

async function loadWater() {
  const el = document.getElementById('water-content');
  el.innerHTML = skeletonLines(3);
  try {
    const today = await api(`/api/health/water/${session.currentUser.id}/today`);
    const week  = await api(`/api/health/water/${session.currentUser.id}/week`);
    const pct = Math.min(100, today.progressPercent || 0);
    const maxMl = Math.max(...week.map(d => d.totalMl || 0), today.goalMl || 1, 1);
    const bars = week.map(d => {
      const h = maxMl > 0 ? Math.round((d.totalMl / maxMl) * 100) : 0;
      return `<div class="wb" style="height:${h}%" title="${(d.totalMl||0)}ml"></div>`;
    }).join('');
    const labels = week.map(d => `<div class="wl">${new Date(d.date).toLocaleDateString('en',{weekday:'narrow'})}</div>`).join('');

    el.innerHTML = `
      <div style="display:flex;justify-content:space-between;align-items:flex-end;margin-bottom:8px">
        <div><span style="font-family:var(--fm);font-size:26px;color:var(--blue)">${((today.totalMl||0)/1000).toFixed(1)}</span><span style="font-size:13px;color:var(--muted)"> / ${((today.goalMl||0)/1000).toFixed(1)}L goal</span></div>
        <span style="font-family:var(--fm);font-size:12px;color:var(--cyan)">${pct.toFixed(0)}% today</span>
      </div>
      <div class="gb2" style="height:8px;margin-bottom:16px"><div class="gf" style="width:${pct}%"></div></div>
      <h3 class="clbl">This week</h3>
      <div class="wviz">${bars || ''}</div>
      <div class="wlbs">${labels || ''}</div>
      <div class="water-add">
        <input type="number" id="water-amount" placeholder="Amount in ml" value="250">
        <button class="btn btn-prim btn-sm" data-action="log-water">+ Add</button>
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
    toast(`💧 +${amount}ml logged!`);
    loadWater();
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
}

async function loadSleep() {
  const el = document.getElementById('sleep-content');
  el.innerHTML = skeletonLines(4);
  try {
    const analysis = await api(`/api/health/sleep/${session.currentUser.id}/analysis`);
    const history = analysis.history || [];
    if (!history.length) {
      el.innerHTML = emptyState('😴', 'No sleep logged in the last 7 nights.');
      return;
    }
    const rows = history.slice(-7).map(h => {
      const hrs = h.hoursSlept || 0;
      const pct = Math.min(100, (hrs / 9) * 100);
      const day = new Date(h.sleepDate).toLocaleDateString('en',{weekday:'short'});
      return `
        <div class="slpr">
          <div class="slpd">${day}</div>
          <div class="slpt"><div class="slpf" style="width:${pct}%"><span class="slph">${hrs}h</span></div></div>
        </div>`;
    }).join('');
    el.innerHTML = `
      <div style="display:flex;justify-content:space-between;margin-bottom:14px">
        <div><span style="font-family:var(--fm);font-size:26px;color:var(--blue)">${analysis.weekAvgHours ?? '—'}</span><span style="font-size:13px;color:var(--muted)"> hrs avg</span></div>
      </div>
      ${rows}
      ${analysis.insights && analysis.insights.length ? `<div style="margin-top:14px;background:rgba(255,184,0,.06);border:1px solid rgba(255,184,0,.16);border-radius:9px;padding:11px 14px;font-size:12px;color:var(--muted)">💡 ${escapeHtml(analysis.insights[0])}</div>` : ''}
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
    toast('😴 Sleep logged!');
    loadSleep();
    loadSleepPerformanceChart();
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Log It';
  }
}

// ── Weight / BMI trend ─────────────────────────────────────────────────

/**
 * Weight and BMI over time, from the body-metrics history that /api/health/metrics
 * was already returning and nothing ever drew.
 *
 * History rows are only written when someone saves their metrics, so two points is
 * the minimum worth plotting — a single point is a dot, not a trend.
 */
function renderBodyChart(metrics) {
  const history = ((metrics && metrics.history) || []).filter(h => h.weight || h.bmi);
  if (history.length < 2) {
    chartPlaceholder('chart-body', 'chart-body-note',
      'Update your weight at least twice to see a trend here.');
    return;
  }
  showChartBox('chart-body');

  const t = chartTheme();
  const blue = chartColor('--blue', '#0EA5FF');
  const gold = chartColor('--gold', '#FFB800');

  renderChart('chart-body', {
    type: 'line',
    data: {
      labels: timestampAxisLabels(history.map(h => h.loggedAt)),
      datasets: [
        {
          label: 'Weight (kg)',
          data: history.map(h => h.weight),
          borderColor: blue,
          backgroundColor: 'rgba(14,165,255,.12)',
          borderWidth: 2, pointRadius: 3, tension: 0.25, fill: true, yAxisID: 'y'
        },
        {
          label: 'BMI',
          data: history.map(h => h.bmi),
          borderColor: gold,
          backgroundColor: gold,
          borderWidth: 2, pointRadius: 3, tension: 0.25, yAxisID: 'y1'
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: { legend: t.legend, tooltip: t.tooltip },
      scales: {
        x:  { grid: { display: false }, ticks: Object.assign({}, t.tick, { maxTicksLimit: 8 }) },
        // Deliberately not beginAtZero: a weight axis starting at 0 squashes every
        // real change into a flat line at the top of the chart.
        y:  { position: 'left',  grid: { color: t.grid }, ticks: t.tick,
              title: Object.assign({ display: true, text: 'kg' }, t.title) },
        y1: { position: 'right', grid: { display: false }, ticks: t.tick,
              title: Object.assign({ display: true, text: 'BMI' }, t.title) }
      }
    }
  }, 'chart-body-note');

  const first = history[0], last = history[history.length - 1];
  const delta = (last.weight != null && first.weight != null) ? (last.weight - first.weight) : null;
  setChartNote('chart-body-note', delta == null
    ? `${history.length} measurements recorded.`
    : `${delta >= 0 ? '+' : ''}${delta.toFixed(1)} kg across ${history.length} measurements. Now ${last.bmi ?? '—'} BMI (${metrics.bmiCategory || '—'}).`);
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
            backgroundColor: chartColor('--cyan', '#00FFD1'),
            pointRadius: 5, pointHoverRadius: 7
          },
          {
            label: 'Rest day',
            data: rested.map(p => ({ x: p.hoursSlept, y: 0, d: p.workoutDate })),
            backgroundColor: 'rgba(136,153,170,.45)',
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
