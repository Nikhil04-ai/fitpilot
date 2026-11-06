import { emptyState, escapeHtml, figure, localDateTimeNow, numberOrNull, shortTime, skeletonFigures, skeletonLines, skeletonRows, trimNumber } from '../util.js';
import { closeModal, confirmDialog, openModal, toast } from '../ui.js';
import { api } from '../api.js';
import { invalidatePanel } from '../nav.js';
import { chartColor, chartPlaceholder, chartTheme, renderChart, setChartNote, shortAxisDate, showChartBox } from '../charts.js';

// ═══════════════════════════════════════════════════════════════════════
// NUTRITION  (PLAN P2-1)
// ═══════════════════════════════════════════════════════════════════════


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
  rowEl.innerHTML = skeletonFigures(4);
  mealsEl.innerHTML = skeletonRows(3);
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
      : emptyState('', 'Nothing logged today.',
          '<button type="button" class="btn btn-sec btn-sm" data-action="open-modal" data-modal="modal-log-meal">Log meal</button>');

    document.getElementById('nutrition-estimate-note').textContent = s.estimateNote || '';
  } catch (err) {
    rowEl.innerHTML = `<div class="error-state">Couldn't load nutrition: ${escapeHtml(err.message)}</div>`;
    mealsEl.innerHTML = '';
    macroEl.innerHTML = '';
  }
}

function renderNutritionSummary(el, s) {
  const net = s.netCalories;
  // Deficit is the goal for most people using this, but not for everyone. The figure
  // used to be coloured by direction, which still read as a verdict; now the sign and
  // the word under it say which way it went, and the colour says nothing.
  const netText = net == null ? '–' : (net > 0 ? '+' : '') + net;
  const balance = s.balance ? s.balance.toLowerCase() : 'add your profile';

  el.innerHTML = [
    figure(s.caloriesIn ?? 0, 'Eaten (kcal)'),
    figure(s.caloriesBurnedExercise ?? 0, 'Training burn'),
    figure(s.maintenanceCalories ?? '–', 'Maintenance (est.)'),
    figure(netText, 'Net', balance.charAt(0).toUpperCase() + balance.slice(1))
  ].join('');
}

function renderNutritionMacros(el, s) {
  const p = s.proteinGrams || 0, c = s.carbsGrams || 0, f = s.fatGrams || 0;
  // Split by the calories each macro contributes, not by grams — a gram of fat carries
  // more than twice the energy of a gram of protein, so a gram-weighted bar would show
  // a fat-heavy day as a lean one.
  const pc = p * 4, cc = c * 4, fc = f * 9;
  const total = pc + cc + fc;

  if (!total) {
    el.innerHTML = emptyState('', 'No macros logged today.');
    return;
  }

  // One hue, three steps of it: a part-of-a-whole split, read by the swatch beside
  // each label, never by dyeing the words.
  const pct = v => ((v / total) * 100).toFixed(0);
  const part = (cls, grams, name, cal) =>
    `<div class="mk-part"><b>${trimNumber(grams)}<span class="u">g</span></b>`
    + `<span class="mk-name"><span class="mdot ${cls}" aria-hidden="true"></span>${name}</span>`
    + `<span class="mk-pct">${pct(cal)}% of energy</span></div>`;
  el.innerHTML = `
    <div class="mbar" role="img" aria-label="Protein ${pct(pc)}%, carbs ${pct(cc)}%, fat ${pct(fc)}% of today's energy">
      <span class="m-p" style="width:${(pc / total) * 100}%"></span>
      <span class="m-c" style="width:${(cc / total) * 100}%"></span>
      <span class="m-f" style="width:${(fc / total) * 100}%"></span>
    </div>
    <div class="mk">
      ${part('m-p', p, 'Protein', pc)}
      ${part('m-c', c, 'Carbs', cc)}
      ${part('m-f', f, 'Fat', fc)}
    </div>
    <div class="field-hint">${s.mealsLogged} meal${s.mealsLogged === 1 ? '' : 's'} logged today.</div>
  `;
}

/**
 * A figure and its unit for the meal row's columns; an empty cell when not known, so
 * columns stay aligned. The cell names itself: the column heads are hidden from
 * assistive tech and fold away when the log stacks, and "14g 62g 9g" alone says
 * nothing about which is which. The name is visually hidden until the row stacks.
 */
function cell(value, unit, name) {
  return value != null
    ? `<span><span class="k">${name} </span>${trimNumber(value)}<span class="u">${unit}</span></span>`
    : '<span></span>';
}

/**
 * One meal in today's log, the same shape as a session in the training log: when,
 * what, its macros and energy in columns, its actions. The meal type is named in the
 * display face, so the per-type emoji and coloured dot it used to carry are gone.
 */
export function mealRow(m) {
  const type = (m.mealType || 'OTHER').toLowerCase();
  const when = m.loggedAt ? shortTime(m.loggedAt) : '';
  const named = [type, when].filter(Boolean).join(' at ');

  // The badge that keeps this honest: a parsed number is a guess at a portion, and
  // must not look like one read off a packet (see .est in css/app.css).
  const estimated = m.aiEstimated
    ? '<span class="est" title="Estimated from your description, not measured. Edit to correct it.">Estimate</span>'
    : '';

  return `
    <div class="ai logrow meal">
      <div class="ai-when">${escapeHtml(when)}</div>
      <div class="ai-body">
        <div class="an">${escapeHtml(type.replace('_', ' '))}${estimated}</div>
        ${m.description ? `<div class="ai-note">${escapeHtml(m.description)}</div>` : ''}
      </div>
      <div class="am">${cell(m.proteinGrams, 'g', 'Protein')}${cell(m.carbsGrams, 'g', 'Carbs')}${cell(m.fatGrams, 'g', 'Fat')}${cell(m.calories, 'kcal', 'Energy')}</div>
      <div class="ai-acts">
        <button type="button" data-action="edit-meal" data-id="${m.id}" aria-label="Edit ${escapeHtml(named)}">Edit</button>
        <button type="button" class="danger" data-action="delete-meal" data-id="${m.id}" aria-label="Delete ${escapeHtml(named)}">Delete</button>
      </div>
    </div>`;
}

// ── Logging ────────────────────────────────────────────────────────────

export function switchMealTab(which) {
  const manual = which === 'manual';
  for (const [id, on] of [['meal-tab-manual', manual], ['meal-tab-natural', !manual]]) {
    const tab = document.getElementById(id);
    tab.classList.toggle('on', on);
    tab.setAttribute('aria-selected', String(on));
  }
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
    handleMealLoggedResponse(res, 'Meal logged');
  } catch (err) {
    toast(err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Log meal';
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
    handleMealLoggedResponse(res, `Logged about ${res.calories} kcal. It's an estimate: use Edit to correct it.`);
  } catch (err) {
    toast(err.message, true);
  } finally {
    btn.disabled = false; btn.textContent = 'Estimate and log';
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
  (res.goalAchievements || []).forEach((m, i) => setTimeout(() => toast(m), (i + 1) * 900));
  // Goal progress is read straight off the meal log, so those panels are stale the
  // moment this lands.
  invalidatePanel('goals');
  invalidatePanel('overview');
  loadNutrition();
}

// ── Editing ────────────────────────────────────────────────────────────

export function openEditMeal(id) {
  const m = nutritionMealsById[id];
  if (!m) { toast('That meal is no longer on screen. Reload the panel.', true); return; }

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
    handleMealLoggedResponse(res, 'Meal updated');
  } catch (err) {
    toast(err.message, true);
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
    toast('Meal removed');
    invalidatePanel('goals');
    invalidatePanel('overview');
    loadNutrition();
  } catch (err) {
    toast(err.message, true);
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
        'Nothing logged in the last 30 days yet. Log a meal and this fills in.');
      return;
    }
    showChartBox('chart-nutrition');

    // Intake is the series that argues, in the lime; the burn line is its reference,
    // in ink. Two hues for two series would have read as two equal claims.
    const t = chartTheme();
    const intake = chartColor('--color-accent', '#C8F25A');
    const intakeFill = chartColor('--chart-fill', 'rgba(200, 242, 90, 0.45)');
    const spendLine = chartColor('--color-ink-2', '#C9CED6');
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
            backgroundColor: intakeFill,
            borderColor: intake,
            borderWidth: 1,
            borderRadius: 2
          },
          {
            type: 'line',
            label: 'Burned (maintenance + training)',
            data: spend,
            borderColor: spendLine,
            backgroundColor: spendLine,
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
