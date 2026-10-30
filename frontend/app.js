// PI data retrieval — fetch the study's ciphertext (logged export), decrypt it
// in the browser with the PI private key, explore and export it. Nothing
// decrypted ever leaves this page except through the export buttons.

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
let records      = [];      // { pid, schema, ms, isEvent, fields: {name: value}, units: {name: unit}, raw }
let studies      = [];
let study        = null;    // selected StudyConfig row
let privateKey   = null;
let schemaModel  = [];      // one entry per schema present, see buildSchemaModel()
const charts     = [];      // live Chart instances

const PAGE_SIZE  = 1000;    // the API's per-request row cap
const TABLE_PAGE = 200;

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
const shortId = (id) => id.substring(0, 8);
const fmtInt = (n) => n.toLocaleString();

// Distinct, colour-blind-friendly-ish series colours (dark background).
const PALETTE = ['#60a5fa', '#f87171', '#34d399', '#fbbf24', '#a78bfa', '#f472b6', '#2dd4bf', '#fb923c', '#a3e635', '#e879f9'];

// Chart.js dark defaults
Chart.defaults.color = '#94a3b8';
Chart.defaults.borderColor = '#232838';
Chart.defaults.font.family = "'Segoe UI', system-ui, sans-serif";
Chart.defaults.font.size = 12;

// ---------------------------------------------------------------------------
// UI helpers
// ---------------------------------------------------------------------------
function setStatus(message, type = 'info') {
  const el = $('status');
  el.textContent = message;
  el.className = `status ${type}`;
}
function setProgress(done, total) {
  const bar = $('progress');
  if (total == null) { bar.classList.add('hidden'); return; }
  bar.classList.remove('hidden');
  bar.querySelector('div').style.width = `${total ? Math.min(100, (done / total) * 100) : 0}%`;
}
const tick = () => new Promise((r) => setTimeout(r, 0));   // let the UI repaint

function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
  return bytes;
}
function parsePem(pem) {
  const b64 = pem.replace(/-----BEGIN [A-Z ]+-----/, '').replace(/-----END [A-Z ]+-----/, '').replace(/\s+/g, '');
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

$('pemFile').addEventListener('change', (e) => {
  const file = e.target.files[0];
  if (file) file.text().then((t) => { $('pemKey').value = t; });
});

// ---------------------------------------------------------------------------
// 1 · Studies (public definition — anon key)
// ---------------------------------------------------------------------------
async function loadStudies() {
  const url = $('supabaseUrl').value.trim().replace(/\/$/, '');
  const key = $('supabaseKey').value.trim();
  if (!url || !key) { alert('Enter the Supabase URL and anon key first.'); return; }
  try {
    const res = await fetch(
      `${url}/rest/v1/StudyConfig?select=studyId,studyName,studyVersion,isActive,dataTypes,schemaDataTypes&order=studyName`,
      { headers: { apikey: key, Authorization: `Bearer ${key}` } });
    if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
    studies = await res.json();
  } catch (e) { alert(`Failed to load studies: ${e.message}`); return; }

  const list = $('studyList');
  list.innerHTML = studies.length ? '' : '<p class="muted">No studies found.</p>';
  studies.forEach((s) => {
    const div = document.createElement('label');
    div.className = 'study-option';
    div.innerHTML = `
      <input type="radio" name="study" value="${esc(s.studyId)}">
      <div>
        <div class="study-name">${esc(s.studyName)} ${s.isActive ? '' : '<span class="tag">inactive</span>'}</div>
        <div class="study-meta">v${esc(s.studyVersion)} · ${(s.dataTypes || []).length} data types · ${esc(s.studyId)}</div>
      </div>`;
    div.querySelector('input').addEventListener('change', () => {
      document.querySelectorAll('.study-option').forEach((el) => el.classList.remove('selected'));
      div.classList.add('selected');
      study = s;
    });
    list.appendChild(div);
  });
  $('studyCard').classList.remove('hidden');
}

// ---------------------------------------------------------------------------
// Staff sign-in (password grant) → access token for the export RPC
// ---------------------------------------------------------------------------
async function signInStaff(url, key) {
  const email = $('piEmail').value.trim();
  const password = $('piPassword').value;
  if (!email || !password) throw new Error('enter the PI staff account email and password');
  const res = await fetch(`${url}/auth/v1/token?grant_type=password`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', apikey: key },
    body: JSON.stringify({ email, password }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || !body.access_token) throw new Error(body.error_description || body.msg || `HTTP ${res.status}`);
  return body.access_token;
}

// ---------------------------------------------------------------------------
// Crypto — the DEK unwrap (RSA) is cached per wrapped key: one per participant
// key rather than one per record, which makes large exports fast.
// ---------------------------------------------------------------------------
async function importPrivateKey(pem) {
  return crypto.subtle.importKey('pkcs8', parsePem(pem), { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['decrypt']);
}
const dekCache = new Map();
function dekFor(wrappedHex) {
  if (!dekCache.has(wrappedHex)) {
    dekCache.set(wrappedHex, crypto.subtle.decrypt({ name: 'RSA-OAEP' }, privateKey, hexToBytes(wrappedHex))
      .then((raw) => crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['decrypt'])));
  }
  return dekCache.get(wrappedHex);
}
async function decryptRow(row) {
  const dek = await dekFor(row.pi_wrapped_dek_hex);
  const ct = hexToBytes(row.ciphertext_hex);
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: ct.slice(0, 12) }, dek, ct.slice(12));
  return JSON.parse(new TextDecoder().decode(plain));
}

/** OMH body → flat fields ({value, unit} unwrapped, units kept separately). */
function flattenBody(body) {
  const fields = {}, units = {};
  for (const [k, v] of Object.entries(body)) {
    if (k === 'effective_time_frame') continue;
    if (v && typeof v === 'object' && 'value' in v) { fields[k] = v.value; if (v.unit) units[k] = v.unit; }
    else if (v === null || typeof v !== 'object') fields[k] = v;
    else fields[k] = JSON.stringify(v);
  }
  return { fields, units };
}

// ---------------------------------------------------------------------------
// 4 · Fetch (paged, one logged export) & decrypt
// ---------------------------------------------------------------------------
async function fetchAndDecrypt() {
  const url = $('supabaseUrl').value.trim().replace(/\/$/, '');
  const key = $('supabaseKey').value.trim();
  const pem = $('pemKey').value.trim();
  if (!url || !key || !pem) return setStatus('Fill in the connection details and the private key.', 'error');
  if (!study) return setStatus('Load the studies and select one first.', 'error');

  $('fetchBtn').disabled = true;
  try {
    setStatus('Importing private key…');
    try { privateKey = await importPrivateKey(pem); dekCache.clear(); }
    catch (e) { throw new Error(`Invalid private key (PKCS#8 PEM expected): ${e.message}`); }

    setStatus('Signing in…');
    const token = await signInStaff(url, key).catch((e) => { throw new Error(`Sign-in failed: ${e.message}`); });
    await loadQuestionnaires(url, key);

    // export_study_records logs ONE export (participants are notified) and
    // serves it in pages of at most 1000 rows.
    const rows = [];
    let exportId = null, afterId = 0, total = null;
    for (;;) {
      const res = await fetch(`${url}/rest/v1/rpc/export_study_records`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', apikey: key, Authorization: `Bearer ${token}` },
        body: JSON.stringify({ p_study_id: study.studyId, p_note: 'PI web tool export',
          p_export_id: exportId, p_after_id: afterId, p_limit: PAGE_SIZE }),
      });
      if (!res.ok) throw new Error(`Fetch failed — ${res.status}: ${await res.text()}`);
      const page = await res.json();
      if (!page.length) break;
      exportId = page[0].export_id;
      total = page[0].total_count;
      afterId = page[page.length - 1].record_id;
      rows.push(...page);
      setStatus(`Fetching… ${fmtInt(rows.length)} of ${fmtInt(total)} records`);
      setProgress(rows.length, total * 2);              // fetch = first half of the bar
      if (page.length < PAGE_SIZE) break;
    }

    records = [];
    let failed = 0;
    const BATCH = 250;
    for (let i = 0; i < rows.length; i += BATCH) {
      const batch = rows.slice(i, i + BATCH);
      const out = await Promise.allSettled(batch.map(decryptRow));
      out.forEach((r, j) => {
        const row = batch[j];
        if (r.status !== 'fulfilled' || !r.value) { failed++; return; }
        const omh = r.value;
        const body = omh.body ?? omh;
        const tf = body.effective_time_frame || {};
        const { fields, units } = flattenBody(body);
        records.push({
          pid: row.participant_pseudo_id, schema: row.schema_name, ms: Number(row.epoch_start_ms),
          isEvent: !!tf.date_time && !tf.time_interval, fields, units, raw: omh,
        });
      });
      setStatus(`Decrypting… ${fmtInt(Math.min(i + BATCH, rows.length))} of ${fmtInt(rows.length)}`);
      setProgress(rows.length + i + batch.length, rows.length * 2);
      await tick();
    }
    records.sort((a, b) => a.ms - b.ms);
    setProgress(null);

    renderAll(rows.length, failed);
    setStatus(
      `Done — ${fmtInt(records.length)} records decrypted` +
      (failed ? `, ${fmtInt(failed)} failed (wrapped for another PI key, or corrupted).` : '.') +
      ' The export was logged; participants are notified in the app.',
      failed ? 'warn' : 'success');
  } catch (e) {
    setProgress(null);
    setStatus(e.message, 'error');
  } finally {
    $('fetchBtn').disabled = false;
  }
}

// ---------------------------------------------------------------------------
// Schema model — derived from the data and the study definition, so any
// schema (built-in or custom) gets charts, labels and CSV columns.
// ---------------------------------------------------------------------------
function dataTypeFor(schema) {
  // Questionnaire answers ("questionnaire:<id>") belong to the data type their
  // questionnaire names (backend/questionnaires.sql).
  const typeId = (study?.schemaDataTypes || {})[schema] ?? questionnaireFor(schema)?.dataType;
  return (study?.dataTypes || []).find((t) => t.id === typeId);
}

let questionnaires = [];   // published questionnaires of the selected study (public definitions)

function questionnaireFor(schema) {
  return questionnaires.find((q) => `questionnaire:${q.questionnaireId}` === schema);
}

async function loadQuestionnaires(url, key) {
  try {
    const res = await fetch(`${url}/rest/v1/rpc/get_study_questionnaires`, {
      method: 'POST',
      headers: { apikey: key, Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ p_study_id: study.studyId }),
    });
    questionnaires = res.ok ? await res.json() : [];
  } catch { questionnaires = []; }
}

function buildSchemaModel() {
  const bySchema = new Map();
  for (const r of records) {
    if (!bySchema.has(r.schema)) bySchema.set(r.schema, []);
    bySchema.get(r.schema).push(r);
  }
  return [...bySchema.entries()].map(([schema, recs]) => {
    const kinds = {}, units = {}, categories = {};
    for (const r of recs) {
      for (const [k, v] of Object.entries(r.fields)) {
        if (v === null || v === undefined || v === '') continue;
        const kind = typeof v === 'number' ? 'number' : 'text';
        kinds[k] = kinds[k] && kinds[k] !== kind ? 'text' : kind;
        if (r.units[k]) units[k] = r.units[k];
        if (kind === 'text') (categories[k] ||= new Set()).add(String(v));
      }
    }
    const numeric = Object.keys(kinds).filter((k) => kinds[k] === 'number');
    // Text fields with few distinct values are categories (e.g. sleep stage);
    // free text is only shown in the table.
    const categorical = Object.keys(kinds).filter((k) => kinds[k] === 'text' && categories[k].size <= 12)
      .map((k) => ({ name: k, values: [...categories[k]].sort() }));
    const dt = dataTypeFor(schema);
    let qTitle = null;
    try { qTitle = JSON.parse(questionnaireFor(schema)?.definition || 'null')?.title; } catch { /* unreadable: use the data type */ }
    return {
      schema, records: recs, numeric, categorical, units,
      isEvent: recs.every((r) => r.isEvent),
      title: qTitle || (dt ? dt.name : schema),
      subtitle: dt ? schema : 'not mapped to a data type of this study',
      participants: new Set(recs.map((r) => r.pid)).size,
    };
  }).sort((a, b) => a.title.localeCompare(b.title));
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------
function renderAll(total, failed) {
  schemaModel = buildSchemaModel();
  const participants = [...new Set(records.map((r) => r.pid))];

  $('statTotal').textContent = fmtInt(total);
  $('statDecrypted').textContent = fmtInt(records.length);
  $('statFailed').textContent = fmtInt(failed);
  $('statParticipants').textContent = fmtInt(participants.length);
  $('statSchemas').textContent = fmtInt(schemaModel.length);
  $('statSpan').textContent = records.length
    ? `${fmtDay(records[0].ms)} – ${fmtDay(records[records.length - 1].ms)}` : '—';
  $('studyTitle').textContent = `${study.studyName} — v${study.studyVersion}`;

  // Participant + schema pickers (charts, table)
  const pOpts = participants.map((p) => `<option value="${esc(p)}">${esc(shortId(p))}</option>`).join('');
  $('chartParticipant').innerHTML = pOpts + (participants.length > 1 ? '<option value="*">All participants (overlay)</option>' : '');
  $('tableParticipant').innerHTML = '<option value="">All participants</option>' + pOpts;
  $('tableSchema').innerHTML = '<option value="">All schemas</option>' +
    schemaModel.map((m) => `<option value="${esc(m.schema)}">${esc(m.title)} (${esc(m.schema)})</option>`).join('');
  $('heatParticipant').innerHTML = '<option value="">All participants</option>' + pOpts;

  ['statsContainer', 'heatmapContainer', 'chartsContainer', 'tableContainer'].forEach((id) => $(id).classList.remove('hidden'));
  $('exportCsvBtn').disabled = $('exportJsonBtn').disabled = !records.length;

  renderHeatmap();
  renderCharts();
  tablePage = 0;
  renderTable();
}

// ---- time axis ----------------------------------------------------------
const fmtDay = (ms) => new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
const fmtDateTime = (ms) => new Date(ms).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' });

function timeTicks(span) {
  return (value) => {
    const d = new Date(value);
    if (span <= 36 * 3600e3) return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
    if (span <= 10 * 86400e3) return d.toLocaleString(undefined, { weekday: 'short', hour: '2-digit' });
    return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
  };
}

/** Points sorted by time; a null is inserted across gaps so lines don't bridge missing data. */
function withGaps(points) {
  if (points.length < 3) return points;
  const deltas = [];
  for (let i = 1; i < points.length; i++) deltas.push(points[i].x - points[i - 1].x);
  const median = deltas.slice().sort((a, b) => a - b)[Math.floor(deltas.length / 2)];
  const limit = Math.max(median * 4, 10 * 60e3);
  const out = [points[0]];
  for (let i = 1; i < points.length; i++) {
    if (points[i].x - points[i - 1].x > limit) out.push({ x: points[i - 1].x + 1, y: null });
    out.push(points[i]);
  }
  return out;
}

function rangeFilter() {
  const v = $('chartRange').value;
  if (v === 'all' || !records.length) return () => true;
  const end = records[records.length - 1].ms;
  const start = end - Number(v) * 3600e3;
  return (r) => r.ms >= start;
}

function baseOptions(span, yOpts = {}) {
  return {
    animation: false,
    parsing: false,
    normalized: true,
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: 'nearest', axis: 'x', intersect: false },
    scales: {
      x: { type: 'linear', ticks: { callback: timeTicks(span), maxTicksLimit: 8, maxRotation: 0 }, grid: { color: '#1b2030' } },
      y: { grid: { color: '#1b2030' }, ...yOpts },
    },
    plugins: {
      legend: { position: 'bottom', labels: { boxWidth: 12, usePointStyle: true, filter: (item) => !item.text.startsWith('__') } },
      tooltip: {
        filter: (item) => !item.dataset.label.startsWith('__'),
        callbacks: {
          title: (items) => items.length ? fmtDateTime(items[0].parsed.x) : '',
          afterLabel: (item) => item.raw?.n > 1 ? `mean of ${item.raw.n} points` : '',
        },
      },
      zoom: {
        zoom: { drag: { enabled: true, backgroundColor: 'rgba(96,165,250,0.15)' }, mode: 'x',
                onZoomComplete: ({ chart }) => chart.$rebuild?.() },
        pan: { enabled: true, mode: 'x', modifierKey: 'shift', onPanComplete: ({ chart }) => chart.$rebuild?.() },
      },
    },
  };
}

function renderCharts() {
  charts.splice(0).forEach((c) => c.destroy());
  const grid = $('chartGrid');
  grid.innerHTML = '';
  const sel = $('chartParticipant').value;
  const pids = sel === '*' ? [...new Set(records.map((r) => r.pid))] : [sel];
  const inRange = rangeFilter();

  schemaModel.forEach((m) => {
    const recs = m.records.filter((r) => pids.includes(r.pid) && inRange(r));
    const panels = [];
    if (m.isEvent) panels.push({ kind: 'event' });
    else {
      if (m.numeric.length) panels.push({ kind: 'numeric' });
      m.categorical.forEach((c) => panels.push({ kind: 'category', field: c }));
    }
    panels.forEach((panel) => {
      const wrap = document.createElement('div');
      wrap.className = 'chart-wrap';
      const unitList = [...new Set(m.numeric.map((f) => m.units[f]).filter(Boolean))];
      const what = panel.kind === 'category' ? ` · ${panel.field.name}`
        : panel.kind === 'event' ? ' · events' : unitList.length ? ` (${unitList.join(', ')})` : '';
      wrap.innerHTML = `
        <div class="chart-head">
          <div><div class="chart-title">${esc(m.title)}${esc(what)}</div>
               <div class="chart-sub">${esc(m.subtitle)} · ${fmtInt(recs.length)} points</div></div>
          <button class="btn-small" title="Reset zoom">Reset zoom</button>
        </div>
        <div class="chart-box"></div>`;
      grid.appendChild(wrap);
      if (!recs.length) { wrap.querySelector('.chart-box').innerHTML = '<p class="muted">No data in this range.</p>'; return; }
      const canvas = document.createElement('canvas');
      wrap.querySelector('.chart-box').appendChild(canvas);
      const span = recs[recs.length - 1].ms - recs[0].ms;
      const chart = new Chart(canvas, chartConfig(m, panel, recs, pids, span));
      // Re-bin for the visible range after zoom / pan / reset, so zooming in
      // reveals detail down to the raw points.
      chart.$rebuild = () => {
        const x = chart.scales.x;
        chart.data.datasets = buildSeries(m, panel, recs, pids, x.min, x.max);
        chart.update('none');
      };
      wrap.querySelector('.btn-small').addEventListener('click', () => {
        chart.resetZoom('none');
        chart.data.datasets = buildSeries(m, panel, recs, pids, recs[0].ms, recs[recs.length - 1].ms);
        chart.update('none');
      });
      charts.push(chart);
    });
  });
  if (!schemaModel.length) grid.innerHTML = '<p class="muted">No decrypted data.</p>';
}

const BINS = 300;

/**
 * Time-bins sorted points over [min, max] when there are too many to read:
 * mean (or, for categories, the most frequent value) per bin plus the bin's
 * min/max. Empty stretches become gaps. Raw points when few, or when the
 * "Raw points" resolution is chosen.
 */
function binPoints(points, min, max, categorical) {
  const view = points.filter((p) => p.x >= min && p.x <= max);
  if ($('chartRes').value === 'raw' || view.length <= BINS * 2) return { line: withGaps(view), binned: false };
  const width = (max - min) / BINS || 1;
  const bins = new Map();
  for (const p of view) {
    const i = Math.min(BINS - 1, Math.floor((p.x - min) / width));
    let b = bins.get(i);
    if (!b) bins.set(i, (b = { sum: 0, n: 0, lo: Infinity, hi: -Infinity, counts: {} }));
    b.sum += p.y; b.n++; b.lo = Math.min(b.lo, p.y); b.hi = Math.max(b.hi, p.y);
    b.counts[p.y] = (b.counts[p.y] || 0) + 1;
  }
  const line = [], lo = [], hi = [];
  let prev = null;
  [...bins.keys()].sort((a, b) => a - b).forEach((i) => {
    const b = bins.get(i), x = min + (i + 0.5) * width;
    if (prev !== null && i - prev > 2) { line.push({ x: x - width, y: null }); lo.push({ x: x - width, y: null }); hi.push({ x: x - width, y: null }); }
    const y = categorical
      ? Number(Object.entries(b.counts).sort((a, c) => c[1] - a[1])[0][0])
      : b.sum / b.n;
    line.push({ x, y, n: b.n }); lo.push({ x, y: b.lo }); hi.push({ x, y: b.hi });
    prev = i;
  });
  return { line, lo, hi, binned: true };
}

/** The datasets of one chart for the time range [min, max]. */
function buildSeries(m, panel, recs, pids, min, max) {
  const overlay = pids.length > 1;
  const series = [];
  pids.forEach((pid, pi) => {
    const mine = recs.filter((r) => r.pid === pid);
    if (!mine.length) return;
    const tag = overlay ? `${shortId(pid)} · ` : '';
    const add = (label, points, color, dash, categorical) => {
      const s = binPoints(points, min, max, categorical);
      const dense = s.line.length > 400;
      series.push({
        label, data: s.line, borderColor: color, backgroundColor: color,
        borderWidth: 1.6, pointRadius: dense ? 0 : 2, pointHoverRadius: 4,
        borderDash: dash ? [5, 4] : [], tension: categorical ? 0 : 0.25, stepped: categorical, spanGaps: false,
      });
      if (s.binned && !categorical) {   // min–max band behind the mean (hidden from the legend)
        series.push({ label: `__lo`, data: s.lo, borderWidth: 0, pointRadius: 0, backgroundColor: 'transparent', spanGaps: false });
        series.push({ label: `__hi`, data: s.hi, borderWidth: 0, pointRadius: 0, fill: '-1', backgroundColor: color + '26', spanGaps: false });
      }
    };
    if (panel.kind === 'numeric') {
      m.numeric.forEach((f, fi) => add(
        `${tag}${f}${m.units[f] ? ` (${m.units[f]})` : ''}`,
        mine.filter((r) => typeof r.fields[f] === 'number').map((r) => ({ x: r.ms, y: r.fields[f] })),
        PALETTE[(overlay ? pi : fi) % PALETTE.length], overlay && fi > 0, false));
    } else if (panel.kind === 'category') {
      const values = panel.field.values;
      add(`${tag}${panel.field.name}`,
        mine.filter((r) => r.fields[panel.field.name] != null)
          .map((r) => ({ x: r.ms, y: values.indexOf(String(r.fields[panel.field.name])) })),
        PALETTE[pi % PALETTE.length], false, true);
    } else {
      const color = PALETTE[pi % PALETTE.length];
      const primary = m.numeric[0];
      series.push({
        label: `${tag}events`,
        type: 'scatter',
        data: mine.filter((r) => r.ms >= min && r.ms <= max)
          .map((r) => ({ x: r.ms, y: primary ? r.fields[primary] ?? 0 : pi + 1, r })),
        borderColor: color, backgroundColor: color, pointRadius: 4, showLine: false,
      });
    }
  });
  return series;
}

function chartConfig(m, panel, recs, pids, span) {
  const series = buildSeries(m, panel, recs, pids, recs[0].ms, recs[recs.length - 1].ms);
  const options = panel.kind === 'category'
    ? baseOptions(span, {
        min: -0.5, max: panel.field.values.length - 0.5,
        // One tick per category, on the category (not at the padded bounds).
        afterBuildTicks: (axis) => { axis.ticks = panel.field.values.map((_, i) => ({ value: i })); },
        ticks: { callback: (v) => panel.field.values[v] ?? '' },
      })
    : baseOptions(span, panel.kind === 'event' && !m.numeric.length ? { display: false } : {});
  if (panel.kind === 'event') {
    options.plugins.tooltip.callbacks.label = (item) => {
      const r = item.raw.r;
      return Object.entries(r.fields).map(([k, v]) => `${k}: ${v}`).join(' · ');
    };
  }
  return { type: panel.kind === 'event' ? 'scatter' : 'line', data: { datasets: series }, options };
}

['chartParticipant', 'chartRange', 'chartRes'].forEach((id) => $(id).addEventListener('change', renderCharts));

// ---------------------------------------------------------------------------
// Heatmap — records per day (all schemas), GitHub-style
// ---------------------------------------------------------------------------
const HEAT = ['#161b22', '#0e4429', '#006d32', '#26a641', '#39d353'];
const dayKey = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`; };

function renderHeatmap() {
  const pid = $('heatParticipant').value;
  const recs = pid ? records.filter((r) => r.pid === pid) : records;
  const wrap = $('heatmapWrap');
  if (!recs.length) { wrap.innerHTML = '<p class="muted">No data.</p>'; return; }

  const counts = {};
  recs.forEach((r) => { const k = dayKey(r.ms); counts[k] = (counts[k] || 0) + 1; });
  const max = Math.max(...Object.values(counts));

  const first = new Date(recs[0].ms); first.setHours(0, 0, 0, 0);
  const last = new Date(recs[recs.length - 1].ms); last.setHours(0, 0, 0, 0);
  const start = new Date(first); start.setDate(start.getDate() - start.getDay());
  const end = new Date(last); end.setDate(end.getDate() + (6 - end.getDay()));

  let html = '<div class="heat-grid">';
  let lastMonth = -1;
  for (let w = new Date(start); w <= end; w.setDate(w.getDate() + 7)) {
    const month = w.getMonth() !== lastMonth ? w.toLocaleDateString(undefined, { month: 'short' }) : '';
    lastMonth = w.getMonth();
    html += `<div class="heat-week"><div class="heat-month">${month}</div>`;
    for (let d = 0; d < 7; d++) {
      const day = new Date(w); day.setDate(w.getDate() + d);
      const n = counts[dayKey(day.getTime())] || 0;
      const inRange = day >= first && day <= last;
      const level = n ? Math.min(4, Math.ceil((n / max) * 4)) : 0;
      html += `<div class="heat-cell" style="background:${inRange ? HEAT[level] : '#10131b'}"
        title="${day.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}: ${fmtInt(n)} records"></div>`;
    }
    html += '</div>';
  }
  wrap.innerHTML = html + '</div>';
}
$('heatParticipant').addEventListener('change', renderHeatmap);

// ---------------------------------------------------------------------------
// Records table — filtered, paged
// ---------------------------------------------------------------------------
let tablePage = 0;
function tableRows() {
  const pid = $('tableParticipant').value, schema = $('tableSchema').value;
  return records.filter((r) => (!pid || r.pid === pid) && (!schema || r.schema === schema));
}
function summarise(r) {
  return Object.entries(r.fields).map(([k, v]) => {
    const val = typeof v === 'number' ? (Number.isInteger(v) ? v : v.toFixed(3)) : v;
    return `<span class="kv"><b>${esc(k)}</b> ${esc(val)}${r.units[k] ? ' ' + esc(r.units[k]) : ''}</span>`;
  }).join(' ');
}
function renderTable() {
  const rows = tableRows();
  const pages = Math.max(1, Math.ceil(rows.length / TABLE_PAGE));
  tablePage = Math.min(tablePage, pages - 1);
  const slice = rows.slice(tablePage * TABLE_PAGE, (tablePage + 1) * TABLE_PAGE);
  $('tableBody').innerHTML = slice.map((r) => `
    <tr>
      <td class="mono">${esc(shortId(r.pid))}</td>
      <td><span class="schema-badge">${esc(r.schema)}</span></td>
      <td class="nowrap">${esc(fmtDateTime(r.ms))}</td>
      <td>${summarise(r)}</td>
    </tr>`).join('');
  $('tableInfo').textContent = rows.length
    ? `${fmtInt(tablePage * TABLE_PAGE + 1)}–${fmtInt(tablePage * TABLE_PAGE + slice.length)} of ${fmtInt(rows.length)}`
    : 'No records';
  $('tablePrev').disabled = tablePage === 0;
  $('tableNext').disabled = tablePage >= pages - 1;
}
['tableParticipant', 'tableSchema'].forEach((id) => $(id).addEventListener('change', () => { tablePage = 0; renderTable(); }));
$('tablePrev').addEventListener('click', () => { tablePage--; renderTable(); });
$('tableNext').addEventListener('click', () => { tablePage++; renderTable(); });

// ---------------------------------------------------------------------------
// Export — one CSV row per participant × epoch, a column per schema field
// ---------------------------------------------------------------------------
const schemaPrefix = (schema) => schema.split(':').pop().replace(/[^a-z0-9]+/gi, '_');
function csvCell(v) {
  if (v === null || v === undefined) return '';
  const s = String(v);
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}
function exportCsv() {
  if (!records.length) return;
  const rows = new Map();
  const columns = new Set();
  for (const r of records) {
    const key = `${r.pid}__${r.ms}`;
    if (!rows.has(key)) rows.set(key, { participant_pseudo_id: r.pid, epoch_start_ms: r.ms, epoch_start_iso: new Date(r.ms).toISOString() });
    const row = rows.get(key), prefix = schemaPrefix(r.schema);
    for (const [k, v] of Object.entries(r.fields)) { row[`${prefix}__${k}`] = v; columns.add(`${prefix}__${k}`); }
    for (const [k, u] of Object.entries(r.units)) { row[`${prefix}__${k}_unit`] = u; columns.add(`${prefix}__${k}_unit`); }
  }
  const header = ['participant_pseudo_id', 'epoch_start_ms', 'epoch_start_iso', ...[...columns].sort()];
  const lines = [header.join(',')];
  [...rows.values()].sort((a, b) => a.participant_pseudo_id.localeCompare(b.participant_pseudo_id) || a.epoch_start_ms - b.epoch_start_ms)
    .forEach((row) => lines.push(header.map((h) => csvCell(row[h])).join(',')));
  download(lines.join('\n'), `${fileStem()}.csv`, 'text/csv');
}
function exportJson() {
  if (!records.length) return;
  download(JSON.stringify(records.map((r) => ({ participant_pseudo_id: r.pid, schema: r.schema, data_point: r.raw })), null, 2),
    `${fileStem()}.json`, 'application/json');
}
const fileStem = () => `${(study?.studyName || 'study').replace(/[^a-z0-9]+/gi, '_').toLowerCase()}_v${study?.studyVersion ?? ''}_${new Date().toISOString().slice(0, 10)}`;
function download(content, filename, type) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([content], { type }));
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}
