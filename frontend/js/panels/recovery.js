import { session } from '../state.js';
import { emptyState, escapeHtml, skeletonRows } from '../util.js';
import { api } from '../api.js';

// ═══════════════════════════════════════════════════════════════════════
// RECOVERY
// ═══════════════════════════════════════════════════════════════════════

const MUSCLE_LABEL = {
  QUADRICEPS:'Quadriceps', HAMSTRINGS:'Hamstrings', CALVES:'Calves', CORE:'Core',
  GLUTES:'Glutes', SHOULDERS:'Shoulders', BACK:'Back', ARMS:'Arms',
  LEGS:'Legs', CHEST:'Chest', FULL_BODY:'Full Body'
};

export async function loadRecovery() {
  const el = document.getElementById('recovery-content');
  el.innerHTML = skeletonRows(6);
  try {
    const groups = await api(`/api/recovery/status/${session.currentUser.id}`);
    if (!groups.length) {
      el.innerHTML = emptyState('🛡', 'No recovery data yet — log a workout to start tracking.');
      return;
    }
    const now = Date.now();
    const withStatus = groups.map(g => {
      const lastWorked = new Date(g.lastWorkedAt).getTime();
      const hoursSince = (now - lastWorked) / 3600000;
      const restHours = g.recommendedRestHours || 48;
      const remaining = Math.max(0, restHours - hoursSince);
      let status = 'fresh';
      if (remaining > restHours * 0.5) status = 'sore';
      else if (remaining > 0) status = 'moderate';
      return { ...g, remaining, status };
    }).sort((a,b) => b.remaining - a.remaining);

    const rows = withStatus.map(g => `
      <div class="ri">
        <div class="rd ${g.status[0]}"></div>
        <div class="rmu">${MUSCLE_LABEL[g.muscleGroup] || g.muscleGroup}</div>
        <div class="rt2">${g.remaining > 0 ? Math.ceil(g.remaining) + 'h remaining' : 'Fully recovered ✓'}</div>
      </div>`).join('');

    const legendCounts = { fresh:0, moderate:0, sore:0 };
    withStatus.forEach(g => legendCounts[g.status]++);

    el.innerHTML = `
      <div class="rg">
        <div class="bmap">
          <div style="font-size:14px;color:var(--muted);text-align:center;margin-bottom:6px">
            Recovery status across ${withStatus.length} muscle groups
          </div>
          <div class="leg">
            <div class="li"><div class="ld f"></div>Recovered (${legendCounts.fresh})</div>
            <div class="li"><div class="ld m"></div>Moderate (${legendCounts.moderate})</div>
            <div class="li"><div class="ld s"></div>Needs Rest (${legendCounts.sore})</div>
          </div>
        </div>
        <div class="rl">${rows}</div>
      </div>`;
  } catch (err) {
    el.innerHTML = `<div class="error-state">Couldn't load recovery data: ${escapeHtml(err.message)}</div>`;
  }
}
