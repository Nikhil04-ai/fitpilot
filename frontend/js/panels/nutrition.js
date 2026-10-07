import { emptyState, escapeHtml, localDateTimeNow, numberOrNull, shortTime, skeletonLines, skeletonStats, trimNumber } from '../util.js';
import { closeModal, confirmDialog, openModal, toast } from '../ui.js';
import { api } from '../api.js';
import { invalidatePanel } from '../nav.js';
import { chartColor, chartPlaceholder, chartTheme, renderChart, setChartNote, shortAxisDate, showChartBox } from '../charts.js';

// ═══════════════════════════════════════════════════════════════════════
// NUTRITION  (PLAN P2-1)
// ═══════════════════════════════════════════════════════════════════════

const MEAL_ICON = {
  BREAKFAST: '🌅', LUNCH: '🥗', DINNER: '🍽', SNACK: '🍎', OTHER: '➕'
};
const MEAL_DOT_COLOR = {
  BREAKFAST: 'var(--gold)', LUNCH: 'var(--cyan)', DINNER: 'var(--blue)',
  SNACK: '#A855F7', OTHER: 'var(--blue)'
};

/**
 * Today's meals, keyed by id.
 *
 * The edit modal needs a meal's current numbers to prefill, and the row can't carry
 * them through a data-* attribute — free text there is an injection surface waiting
 * for one missed escapeHtml() (AGENT.md §8). Only the id goes on the button; the
 * handler looks the rest up here.
 */
let nutritionMealsById = {};

export async function loadNutrition() {
  await Promise.all([loadNutritionToday(), loadNutritionChart()]);
}

async function loadNutritionToday() {
  const rowEl = document.getElementById('nutrition-summary-row');
  const mealsEl = document.getElementById('nutrition-meals');
  const macroEl = document.getElementById('nutrition-macros');
  rowEl.innerHTML = skeletonStats(4);
  mealsEl.innerHTML = skeletonLines(3);
  macroEl.innerHTML = skeletonLines(3);

  try {
    const s = await api('/api/nutrition/summary');

    renderNutritionSummary(rowEl, s);
    renderNutritionMacros(macroEl, s);

    const meals = s.meals || [];
    nutritionMealsById = {};
    meals.forEach(m => { nutritionMealsById[m.id] = m; });
    mealsEl.innerHTML = meals.length
      ? meals.map(mealRow).join('')
      : emptyState('🍽', 'Nothing logged today — add your first meal.');

    document.getElementById('nutrition-estimate-note').textContent = s.estimateNote || '';
  } catch (err) {
    rowEl.innerHTML = `<div class="error-state" style="grid-column:1/-1">Couldn't load nutrition: ${escapeHtml(err.message)}</div>`;
    mealsEl.innerHTML = '';
    macroEl.innerHTML = '';
  }
}

function renderNutritionSummary(el, s) {
  const net = s.netCalories;
  // Deficit is the goal for most people using this, but not for everyone, so the
  // colour marks direction rather than approval: cyan under, gold over.
  const netColor = net == null ? 'var(--muted)' : net < 0 ? 'var(--cyan)' : 'var(--gold)';
  const netText = net == null ? '—' : (net > 0 ? '+' : '') + net;
  const balance = s.balance ? s.balance.toLowerCase() : 'add your profile';

  el.innerHTML = `
    <div class="card smc"><div class="smi g">🍽</div><div><div class="smv">${s.caloriesIn ?? 0}</div><div class="smk">Eaten (kcal)</div></div></div>
    <div class="card smc"><div class="smi c">🔥</div><div><div class="smv">${s.caloriesBurnedExercise ?? 0}</div><div class="smk">Training burn</div></div></div>
    <div class="card smc"><div class="smi b">🫀</div><div><div class="smv">${s.maintenanceCalories ?? '—'}</div><div class="smk">Maintenance (est.)</div></div></div>
    <div class="card smc"><div class="smi p">⚖️</div><div><div class="smv" style="color:${netColor}">${netText}</div><div class="smk">Net · ${escapeHtml(balance)}</div></div></div>
  `;
}

function renderNutritionMacros(el, s) {
  const p = s.proteinGrams || 0, c = s.carbsGrams || 0, f = s.fatGrams || 0;
  // Split by the calories each macro contributes, not by grams — a gram of fat carries
  // more than twice the energy of a gram of protein, so a gram-weighted bar would show
  // a fat-heavy day as a lean one.
  const pc = p * 4, cc = c * 4, fc = f * 9;
  const total = pc + cc + fc;

  if (!total) {
    el.innerHTML = emptyState('🥗', 'No macros logged today.');
    return;
  }

  const pct = v => ((v / total) * 100).toFixed(0);
  el.innerHTML = `
    <div class="mbar">
      <span style="width:${(pc / total) * 100}%;background:var(--cyan)" title="Protein"></span>
      <span style="width:${(cc / total) * 100}%;background:var(--blue)" title="Carbs"></span>
      <span style="width:${(fc / total) * 100}%;background:var(--gold)" title="Fat"></span>
    </div>
    <div class="mk">
      <div><b>${trimNumber(p)}g</b><span class="mdot" style="background:var(--cyan)"></span>Protein · ${pct(pc)}%</div>
      <div><b>${trimNumber(c)}g</b><span class="mdot" style="background:var(--blue)"></span>Carbs · ${pct(cc)}%</div>
      <div><b>${trimNumber(f)}g</b><span class="mdot" style="background:var(--gold)"></span>Fat · ${pct(fc)}%</div>
    </div>
    <div class="field-hint">${s.mealsLogged} meal${s.mealsLogged === 1 ? '' : 's'} logged today.</div>
  `;
}

function mealRow(m) {
  const icon = MEAL_ICON[m.mealType] || '🍽';
  const dot = MEAL_DOT_COLOR[m.mealType] || 'var(--blue)';
  const label = (m.mealType || 'OTHER').toLowerCase();
  const title = m.description || label.charAt(0).toUpperCase() + label.slice(1);

  const macros = [
    m.proteinGrams != null ? `${trimNumber(m.proteinGrams)}p` : '',
    m.carbsGrams != null ? `${trimNumber(m.carbsGrams)}c` : '',
    m.fatGrams != null ? `${trimNumber(m.fatGrams)}f` : ''
  ].filter(Boolean).join(' · ');

  // The badge that keeps this honest — see .est in index.html.
  const estimated = m.aiEstimated
    ? '<span class="est" title="Estimated from your description, not measured. Edit to correct it.">~ est</span>'
    : '';

  const when = m.loggedAt ? shortTime(m.loggedAt) : '';
  const sub = [when ? `<span>${escapeHtml(when)}</span>` : '',
               macros ? `<span class="ai-chip">${escapeHtml(macros)}</span>` : '',
               estimated].filter(Boolean).join('');

  return `
    <div class="ai">
      <div class="ad" style="background:${dot};box-shadow:0 0 6px ${dot}"></div>
      <div class="ai-body">
        <div class="an">${icon} ${escapeHtml(title)}</div>
        <div class="ai-sub">${sub}</div>
      </div>
      <div class="am">${m.calories != null ? m.calories + ' kcal' : ''}</div>
      <div class="ai-acts">
        <button title="Edit" data-action="edit-meal" data-id="${m.id}">✎</button>
        <button title="Delete" class="danger" data-action="delete-meal" data-id="${m.id}">✕</button>
      </div>
    </div>`;
}

// ── Logging ────────────────────────────────────────────────────────────

export function switchMealTab(which) {
  const manual = which === 'manual';
  document.getElementById('meal-tab-manual').classList.toggle('on', manual);
  document.getElementById('meal-tab-natural').classList.toggle('on', !manual);
  document.getElementById('form-log-meal').style.display = manual ? '' : 'none';
  document.getElementById('form-log-meal-natural').style.display = manual ? 'none' : '';
}

export async function handleLogMeal(e) {
  e.preventDefault();
  const btn = document.getElementById('log-meal-btn');
  btn.disabled = true; btn.textContent = 'Logging…';

  const payload = {
    description: document.getElementById('meal-description').value || null,
    mealType: document.getElementById('meal-type').value,
    calories: numberOrNull('meal-calories'),
    proteinGrams: numberOrNull('meal-protein'),
    carbsGrams: numberOrNull('meal-carbs'),
    fatGrams: numberOrNull('meal-fat'),
    // Never toISOString() — Jackson drops the trailing Z into a LocalDateTime and
    // every meal would be stored UTC-shifted with no error anywhere (AGENT.md §8).
    loggedAt: localDateTimeNow()
  };

  try {
    const res = await api('/api/nutrition/meals', { method: 'POST', body: JSON.stringify(payload) });
    closeModal('modal-log-meal');
    document.getElementById('form-log-meal').reset();
    handleMealLoggedResponse(res, '🍽 Meal logged!');
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Log It 🍽';
  }
}

export async function handleLogMealNatural(e) {
  e.preventDefault();
  const btn = document.getElementById('log-meal-natural-btn');
  btn.disabled = true; btn.textContent = 'Estimating…';

  const description = document.getElementById('meal-natural-description').value;
  try {
    const res = await api('/api/nutrition/meals/natural', {
      method: 'POST', body: JSON.stringify({ description })
    });
    closeModal('modal-log-meal');
    document.getElementById('form-log-meal-natural').reset();
    handleMealLoggedResponse(res, `🍽 Logged ~${res.calories} kcal — estimated, tap ✎ to correct.`);
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Estimate & Log 💬';
  }
}

/**
 * Shared aftermath of logging a meal, either way in.
 *
 * Goal messages are surfaced here rather than only on the Goals panel: going over a
 * calorie budget is worth knowing at the moment you log the meal that did it, not the
 * next time you happen to open a different screen.
 */
function handleMealLoggedResponse(res, message) {
  toast(message);
  (res.goalAchievements || []).forEach((m, i) => setTimeout(() => toast('🎯 ' + m), (i + 1) * 900));
  // Goal progress is read straight off the meal log, so those panels are stale the
  // moment this lands.
  invalidatePanel('goals');
  invalidatePanel('overview');
  loadNutrition();
}

// ── Editing ────────────────────────────────────────────────────────────

export function openEditMeal(id) {
  const m = nutritionMealsById[id];
  if (!m) { toast('That meal is no longer on screen — reload the panel.', true); return; }

  document.getElementById('edit-meal-id').value = id;
  document.getElementById('edit-meal-type').value = m.mealType || 'OTHER';
  document.getElementById('edit-meal-description').value = m.description || '';
  document.getElementById('edit-meal-calories').value = m.calories ?? '';
  document.getElementById('edit-meal-protein').value = m.proteinGrams ?? '';
  document.getElementById('edit-meal-carbs').value = m.carbsGrams ?? '';
  document.getElementById('edit-meal-fat').value = m.fatGrams ?? '';
  document.getElementById('edit-meal-note').textContent = m.aiEstimated
    ? 'These numbers were estimated from your description. Correcting any of them marks the meal as yours.'
    : '';
  openModal('modal-edit-meal');
}

export async function handleEditMeal(e) {
  e.preventDefault();
  const btn = document.getElementById('edit-meal-btn');
  btn.disabled = true; btn.textContent = 'Saving…';

  const id = document.getElementById('edit-meal-id').value;
  const payload = {
    description: document.getElementById('edit-meal-description').value.trim() || null,
    mealType: document.getElementById('edit-meal-type').value,
    calories: numberOrNull('edit-meal-calories'),
    proteinGrams: numberOrNull('edit-meal-protein'),
    carbsGrams: numberOrNull('edit-meal-carbs'),
    fatGrams: numberOrNull('edit-meal-fat')
  };

  // The update is partial — null means "keep it" — so a field the user emptied has
  // to be named explicitly, or saving silently restores the old value.
  const before = nutritionMealsById[id] || {};
  payload.clear = ['description', 'calories', 'proteinGrams', 'carbsGrams', 'fatGrams']
    .filter(f => payload[f] == null && before[f] != null && before[f] !== '');

  try {
    const res = await api(`/api/nutrition/meals/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
    closeModal('modal-edit-meal');
    handleMealLoggedResponse(res, '✅ Meal updated');
  } catch (err) {
    toast('❌ ' + err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Save Changes';
  }
}

export async function deleteMeal(id) {
  const ok = await confirmDialog({
    title: 'Delete meal?',
    message: 'Today’s totals and any calorie budget will be recalculated.',
    confirmLabel: 'Delete'
  });
  if (!ok) return;
  try {
    await api(`/api/nutrition/meals/${id}`, { method: 'DELETE' });
    toast('🗑 Meal removed');
    invalidatePanel('goals');
    invalidatePanel('overview');
    loadNutrition();
  } catch (err) {
    toast('❌ ' + err.message, true);
  }
}

// ── Energy balance chart ───────────────────────────────────────────────

/**
 * Intake as bars against the day's total spend as a line.
 *
 * Read it by where the bar sits relative to the line: below is a deficit, above is a
 * surplus. Plotting net alone would have been one number per day with no sense of how
 * big either side was, and would have hidden that the line is partly an estimate.
 *
 * Unlike the training-volume chart, a day with nothing logged is left blank rather
 * than drawn as zero. The two look identical and mean opposite things: a rest day
 * genuinely burned no training calories, whereas a day with no meals almost certainly
 * had meals that simply weren't recorded — and drawing that as a 1,900 kcal deficit
 * would invent the most dramatic day on the chart out of missing data.
 */
async function loadNutritionChart() {
  try {
    const series = await api('/api/nutrition/daily?days=30');
    const logged = series.filter(d => (d.caloriesIn || 0) > 0);

    if (!logged.length) {
      chartPlaceholder('chart-nutrition', 'chart-nutrition-note',
        'Nothing logged in the last 30 days yet — log a meal and this fills in.');
      return;
    }
    showChartBox('chart-nutrition');

    const t = chartTheme();
    const blue = chartColor('--blue', '#0EA5FF');
    const gold = chartColor('--gold', '#FFB800');
    const maintenanceRow = series.find(d => d.maintenanceCalories != null);
    const maintenance = maintenanceRow ? maintenanceRow.maintenanceCalories : null;

    const spend = series.map(d =>
      d.maintenanceCalories == null ? null : d.maintenanceCalories + (d.caloriesBurnedExercise || 0));

    const drawn = renderChart('chart-nutrition', {
      data: {
        labels: series.map(d => shortAxisDate(d.date)),
        datasets: [
          {
            type: 'bar',
            label: 'Eaten',
            data: series.map(d => (d.caloriesIn || 0) > 0 ? d.caloriesIn : null),
            backgroundColor: 'rgba(14,165,255,.45)',
            borderColor: blue,
            borderWidth: 1,
            borderRadius: 3
          },
          {
            type: 'line',
            label: 'Burned (maintenance + training)',
            data: spend,
            borderColor: gold,
            backgroundColor: gold,
            borderWidth: 2,
            pointRadius: 0,
            // No spline: the spend line steps up on the days you trained, and smoothing
            // would round those steps into a gentle trend that never happened.
            tension: 0
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: { legend: t.legend, tooltip: t.tooltip },
        scales: {
          x: { grid: { color: t.grid }, ticks: t.tick },
          y: {
            beginAtZero: true,
            grid: { color: t.grid },
            ticks: t.tick,
            title: { display: true, text: 'kcal', ...t.title }
          }
        }
      }
    }, 'chart-nutrition-note');

    if (!drawn) return;

    const skipped = series.length - logged.length;
    setChartNote('chart-nutrition-note',
      (maintenance == null
        ? 'Add your weight, height and age in Profile and the burn line appears. '
        : `Burn line = estimated maintenance (${maintenance} kcal, sex-neutral Mifflin-St Jeor, treat as ±100) plus that day's training. `)
      + 'Bars below the line are a deficit, above it a surplus. '
      + (skipped ? `${skipped} of the last 30 days had no meals logged and are left blank rather than drawn as a zero-calorie day.` : ''));
  } catch (err) {
    chartPlaceholder('chart-nutrition', 'chart-nutrition-note',
      `Couldn't load the energy balance chart: ${err.message}`);
  }
}
