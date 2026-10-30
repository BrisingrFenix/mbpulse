/* mbPulse study setup wizard — talks to the local Kotlin server (Main.kt). */

let state = { schemas: [], agents: [], agentPrompt: "", repoRoot: "" };
let dataTypeSeq = 0;

const $ = (id) => document.getElementById(id);
const api = async (path, body) => {
  const res = await fetch(path, body
    ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
    : undefined);
  const json = await res.json();
  if (!res.ok || json.ok === false) throw new Error(json.error || "request failed");
  return json;
};
const setResult = (target, text, ok) => {
  const el = typeof target === "string" ? $(target) : target;
  el.textContent = text;
  // Toggle only the state classes — elements also carry lookup classes (e.g. cs-result).
  el.classList.add("result");
  el.classList.toggle("ok", ok);
  el.classList.toggle("err", !ok);
};

// ---------------------------------------------------------------- navigation
function goto(step) {
  document.querySelectorAll(".step").forEach((s) => s.classList.add("hidden"));
  $("step-" + step).classList.remove("hidden");
  document.querySelectorAll(".step-link").forEach((l) => {
    l.classList.toggle("active", l.dataset.step === String(step));
    l.classList.toggle("done", Number(l.dataset.step) < step);
  });
  window.scrollTo(0, 0);
}
document.querySelectorAll(".step-link").forEach((l) =>
  l.addEventListener("click", () => goto(Number(l.dataset.step))));
document.querySelectorAll(".next, .back").forEach((b) =>
  b.addEventListener("click", () => goto(Number(b.dataset.next))));

// ------------------------------------------------------------------- step 1
let backendMode = "new";

function applyMode() {
  // An existing backend needs verification, not reinstallation — step 2
  // swaps its copy and demotes the setup button to an optional re-run.
  $("step-2").classList.toggle("mode-existing", backendMode === "existing");
  $("btn-provision").textContent =
    backendMode === "existing" ? "Re-run setup (applies updates)" : "Run database setup";
}

document.querySelectorAll("input[name=backend]").forEach((r) =>
  r.addEventListener("change", () => {
    backendMode = r.value;
    ["new", "existing", "local"].forEach((v) =>
      $("backend-guide-" + v).classList.toggle("hidden", v !== r.value));
    if (r.value === "local") {
      $("supabase-url").value = "http://127.0.0.1:54321";
      $("db-url").value = "postgresql://postgres:postgres@127.0.0.1:54322/postgres";
    }
    applyMode();
  }));

$("btn-test").addEventListener("click", async () => {
  setResult("test-result", "connecting…", true);
  try {
    const r = await api("/api/test-connection", { dbUrl: $("db-url").value.trim() });
    setResult("test-result", "Connected — " + r.version.split(" on ")[0], true);
    $("to-2").disabled = false;
  } catch (e) {
    setResult("test-result", e.message, false);
    $("to-2").disabled = true;
  }
});

// ------------------------------------------------------------------- step 2
$("btn-verify-db").addEventListener("click", async () => {
  setResult("verify-db-result", "checking…", true);
  try {
    const r = await api("/api/verify-db", { dbUrl: $("db-url").value.trim() });
    setResult("verify-db-result", r.verdict, r.provisioned);
  } catch (e) { setResult("verify-db-result", e.message, false); }
});

$("btn-provision").addEventListener("click", async () => {
  setResult("provision-result", "running…", true);
  $("provision-files").innerHTML = "";
  try {
    const r = await api("/api/provision", { dbUrl: $("db-url").value.trim() });
    r.results.forEach((f) => {
      const li = document.createElement("li");
      li.className = f.ok ? "ok" : "err";
      li.textContent = f.file + (f.error ? " — " + f.error : "");
      $("provision-files").appendChild(li);
    });
    setResult("provision-result", r.ok ? "Database ready." : "Some scripts failed — see below.", r.ok);
  } catch (e) { setResult("provision-result", e.message, false); }
});

$("btn-keys").addEventListener("click", async () => {
  try {
    const r = await api("/api/keys", {});
    setResult("keys-result",
      (r.created ? "Keys generated in " : "Existing keys loaded from ") + r.keysDir +
      ". Includes the study-signing key: every study you create is signed with it, and the " +
      "app only accepts signed studies. It never leaves this folder — back it up with the PI key.", true);
    $("secret-value").textContent = r.secretValue;
    $("edge-commands").textContent =
      "supabase link --project-ref <your-project-ref>\n" +
      r.secretCommand + "\n" +
      "supabase functions deploy confirm-withdrawal";
  } catch (e) { setResult("keys-result", e.message, false); }
});

$("btn-copy-code").addEventListener("click", async () => {
  try {
    const r = await api("/api/function-code");
    await navigator.clipboard.writeText(r.code);
    setResult("copy-code-result", "copied ✓", true);
  } catch (e) { setResult("copy-code-result", e.message, false); }
});

$("btn-check-fn").addEventListener("click", async () => {
  setResult("check-fn-result", "checking…", true);
  try {
    const r = await api("/api/test-function", {
      supabaseUrl: $("supabase-url").value.trim(),
      anonKey: $("anon-key").value.trim(),
    });
    setResult("check-fn-result", r.verdict, r.configured);
    // On an "existing backend" the deploy steps are hidden — bring them back
    // if the function turns out to be missing/misconfigured after all.
    if (!r.configured) document.querySelector(".deploy-steps").classList.add("revealed");
  } catch (e) { setResult("check-fn-result", e.message, false); }
});

$("btn-credentials").addEventListener("click", async () => {
  try {
    const r = await api("/api/credentials", {
      supabaseUrl: $("supabase-url").value.trim(),
      anonKey: $("anon-key").value.trim(),
    });
    setResult("credentials-result", "Written to " + r.path, true);
  } catch (e) { setResult("credentials-result", e.message, false); }
});

document.querySelectorAll(".copy").forEach((b) =>
  b.addEventListener("click", () =>
    navigator.clipboard.writeText($(b.dataset.copy).textContent)));

// ------------------------------------------------------------------- step 3
function addDataType(preset = {}) {
  const n = ++dataTypeSeq;
  const div = document.createElement("div");
  div.className = "datatype";
  div.dataset.n = n;
  div.innerHTML = `
    <div class="head"><b>Data type</b>
      <button class="remove" title="Remove">remove</button></div>
    <div class="grid2">
      <label>Participant-facing name <input class="dt-name" placeholder="Heart Rate and HRV"></label>
      <label>id <small>(machine)</small> <input class="dt-id" placeholder="heart_rate"></label>
    </div>
    <label>What is collected <input class="dt-desc" placeholder="Heart rate derived from the wearable's PPG sensor."></label>
    <label>Why it is collected <input class="dt-purpose" placeholder="Monitor cardiovascular activity during sleep."></label>
    <div class="grid3">
      <label>Retention (days) <input class="dt-retention" type="number" value="56" min="1"></label>
      <label>Sampling (seconds) <small>(empty = natural)</small>
        <input class="dt-sampling" type="number" min="1" placeholder=""></label>
      <label class="checkline" style="align-self:end"><input class="dt-essential" type="checkbox"> Essential (required to join)</label>
    </div>
    <label>Measurement schemas feeding this type</label>
    <div class="schemas">
      ${state.schemas.map((s) =>
        `<label title="${s.description}"><input type="checkbox" class="dt-schema" value="${s.id}"> ${s.role === "event" ? "⚡ " : ""}${s.name}</label>`).join("")}
    </div>
    <details class="custom-schema">
      <summary>+ Custom schema <small>(a measure the library doesn't have yet)</small></summary>
      <div class="cs-import">
        <div class="cs-search-row">
          <input class="cs-q" placeholder="Search Open mHealth, e.g. blood pressure, oxygen, respiratory">
          <button class="secondary cs-search">Search</button>
          <label class="cs-file-btn">Import JSON file
            <input type="file" class="cs-file" accept=".json,application/json" hidden></label>
        </div>
        <div class="cs-results"></div>
        <span class="result cs-import-result"></span>
      </div>
      <div class="grid2">
        <label>Schema id <small>(namespace:name)</small> <input class="cs-id" placeholder="mbpulse:emg"></label>
        <label>What it measures <input class="cs-desc" placeholder="Trapezius EMG RMS per window"></label>
      </div>
      <label>LOINC code <small>(optional)</small> <input class="cs-loinc" placeholder=""></label>
      <div class="grid2">
        <label>Schema type
          <select class="cs-role">
            <option value="measurement">Measurement — summarises a time window</option>
            <option value="event">Event / trigger — a single instant (e.g. stimulus given)</option>
          </select></label>
        <label class="cs-trigger-wrap" hidden>How is the trigger produced?
          <select class="cs-trigger">
            <option value="sensor">Sensor marker channel (device button, TTL line, CSV column)</option>
            <option value="app">Button in the app (researcher/participant taps it)</option>
            <option value="protocol">The app delivers the stimulus on a schedule</option>
          </select></label>
      </div>
      <label>Body fields <small>(one per value recorded in each data point)</small></label>
      <div class="cs-fields"></div>
      <button class="secondary cs-add-field">+ Field</button>
      <div class="row"><button class="secondary cs-add">Add schema</button>
        <span class="result cs-result"></span></div>
    </details>
    <div class="schema-previews"></div>`;
  div.querySelector(".remove").addEventListener("click", () => { div.remove(); refresh(); });
  div.querySelector(".cs-add").addEventListener("click", () => addCustomSchema(div));
  div.querySelector(".cs-add-field").addEventListener("click", () => addFieldRow(div));
  div.querySelector(".cs-search").addEventListener("click", () => searchOmh(div));
  div.querySelector(".cs-q").addEventListener("keydown", (e) => {
    if (e.key === "Enter") { e.preventDefault(); searchOmh(div); }
  });
  div.querySelector(".cs-file").addEventListener("change", (e) => importJsonFile(div, e.target));
  div.querySelector(".cs-role").addEventListener("change", () => syncRole(div));
  addFieldRow(div);
  div.addEventListener("input", refresh);
  div.addEventListener("change", refresh);
  $("datatypes").appendChild(div);

  if (preset.name) div.querySelector(".dt-name").value = preset.name;
  if (preset.id) div.querySelector(".dt-id").value = preset.id;
  if (preset.essential) div.querySelector(".dt-essential").checked = true;
  if (preset.description) div.querySelector(".dt-desc").value = preset.description;
  if (preset.purpose) div.querySelector(".dt-purpose").value = preset.purpose;
  if (preset.retentionDays) div.querySelector(".dt-retention").value = preset.retentionDays;
  if (preset.samplingSeconds) div.querySelector(".dt-sampling").value = preset.samplingSeconds;
  const customSpecs = Object.fromEntries((preset.customSchemas || []).map((c) => [c.id, c]));
  (preset.schemas || []).forEach((id) => {
    const cb = [...div.querySelectorAll(".dt-schema")].find((c) => c.value === id);
    if (cb) cb.checked = true;
    else if (customSpecs[id]) addCustomChip(div, customSpecs[id]);
    else addKeptSchema(div, id);
  });
  refresh();
}

/**
 * A schema of a loaded study that the wizard does not know (a custom schema
 * seeded before the wizard stored their specs). Kept ticked so the study's
 * schema → data-type mapping (retention) is preserved on update.
 */
function addKeptSchema(div, id) {
  const chip = document.createElement("label");
  chip.className = "custom";
  chip.title = "Custom schema from the existing study — declared in the app; kept as-is";
  chip.innerHTML = `<input type="checkbox" class="dt-schema" checked> ${esc(id)} <small>(custom, kept)</small>`;
  chip.querySelector("input").value = id;
  div.querySelector(".schemas").appendChild(chip);
}
$("btn-add-type").addEventListener("click", () => addDataType());

/**
 * One body-field row: name + kind + unit. Kinds are the library's
 * `FieldKind`s — `quantity` ({value, unit}), a bare `number`, `text`, and
 * `enum` (text restricted to a fixed set of values). The brief turns the
 * custom schema into a `SchemaDefinition` the app declares; the library
 * itself is never edited.
 */
let datalistSeq = 0;

function addFieldRow(div, preset = {}) {
  const row = document.createElement("div");
  row.className = "cs-field";
  const listId = `units-${++datalistSeq}`;
  row.innerHTML = `
    <input class="cf-name" placeholder="rms_trapezius">
    <select class="cf-kind">
      <option value="quantity">value + unit</option>
      <option value="number">number</option>
      <option value="text">text</option>
      <option value="enum">one of (enum)</option>
    </select>
    <input class="cf-unit" placeholder="unit, e.g. mV" list="${listId}">
    <input class="cf-values" placeholder="values, comma-separated" hidden>
    <datalist id="${listId}"></datalist>
    <button class="chip-remove" title="Remove field">×</button>`;
  const kind = row.querySelector(".cf-kind");
  const unit = row.querySelector(".cf-unit");
  const values = row.querySelector(".cf-values");
  const syncUnit = () => {
    const isEnum = kind.value === "enum";
    unit.hidden = isEnum;
    values.hidden = !isEnum;
    unit.disabled = kind.value !== "quantity";
    if (unit.disabled) unit.value = "";
  };
  kind.addEventListener("change", syncUnit);
  row.querySelector(".chip-remove").addEventListener("click", (e) => {
    e.preventDefault(); row.remove();
  });
  if (preset.name) row.querySelector(".cf-name").value = preset.name;
  if (preset.kind) kind.value = preset.kind;
  syncUnit();
  if (preset.unit) unit.value = preset.unit;
  if (preset.values) values.value = preset.values.join(", ");
  // Allowed units from an imported schema become suggestions.
  row.querySelector("datalist").innerHTML =
    (preset.units || []).map((u) => `<option value="${esc(u)}">`).join("");
  if (preset.description || preset.required !== undefined)
    row.title = `${preset.required ? "Required" : "Optional"} in the source schema` +
      (preset.description ? ` — ${preset.description}` : "");
  if (preset.required === false) row.classList.add("optional");
  div.querySelector(".cs-fields").appendChild(row);
}

// ------------------------------------------------------ schema import
/** Shows the trigger-source question only for event schemas. */
function syncRole(div) {
  div.querySelector(".cs-trigger-wrap").hidden = div.querySelector(".cs-role").value !== "event";
}

/** Fills the custom-schema form from an imported schema or preset — the researcher reviews, then adds. */
function fillCustomForm(div, schema) {
  div.querySelector(".cs-id").value = schema.id || "";
  div.querySelector(".cs-desc").value = schema.description || "";
  div.querySelector(".cs-loinc").value = "";
  div.querySelector(".cs-role").value = schema.role || "measurement";
  div.querySelector(".cs-trigger").value = schema.trigger || "sensor";
  syncRole(div);
  div.querySelector(".cs-fields").innerHTML = "";
  (schema.fields || []).forEach((f) => addFieldRow(div, f));
  // Carried through to the brief: where it came from and which version.
  div.querySelector(".custom-schema").dataset.imported = JSON.stringify({
    version: schema.version || "1.0", source: schema.source || "", deprecated: schema.deprecated || "",
  });
  const notes = [];
  if (schema.skipped?.length) notes.push(`skipped (not representable): ${schema.skipped.join(", ")}`);
  if (schema.deprecated) notes.push(`deprecated upstream: ${schema.deprecated}`);
  const missingUnit = (schema.fields || []).filter((f) => f.kind === "quantity" && !f.unit).map((f) => f.name);
  if (missingUnit.length) notes.push(`fill in the unit for: ${missingUnit.join(", ")}`);
  setResult(div.querySelector(".cs-import-result"),
    `Loaded ${schema.id} v${schema.version}. Optional fields are faded — remove any you won't record.` +
      (notes.length ? " Note: " + notes.join("; ") + "." : ""), true);
}

async function searchOmh(div) {
  const out = div.querySelector(".cs-results");
  const res = div.querySelector(".cs-import-result");
  setResult(res, "searching Open mHealth…", true);
  try {
    const r = await api("/api/omh/search", { q: div.querySelector(".cs-q").value });
    res.textContent = r.results.length ? "" : "No measurement schema matches.";
    out.innerHTML = r.results.map((s) => `
      <div class="omh-result" data-name="${esc(s.name)}">
        <code>omh:${esc(s.name)}</code>
        <select>${s.versions.map((v) => `<option>${esc(v)}</option>`).join("")}</select>
        <button class="secondary">Import</button>
      </div>`).join("");
    out.querySelectorAll(".omh-result").forEach((el) =>
      el.querySelector("button").addEventListener("click", async (e) => {
        e.preventDefault();
        setResult(res, `fetching omh:${el.dataset.name}…`, true);
        try {
          const schema = await api("/api/omh/schema",
            { name: el.dataset.name, version: el.querySelector("select").value });
          out.innerHTML = "";
          fillCustomForm(div, schema);
        } catch (err) { setResult(res, err.message, false); }
      }));
  } catch (e) { setResult(res, e.message, false); }
}

async function importJsonFile(div, input) {
  const file = input.files[0];
  input.value = "";                                  // allow re-importing the same file
  if (!file) return;
  try {
    const schema = await api("/api/schema/parse",
      { json: await file.text(), id: div.querySelector(".cs-id").value.trim() });
    fillCustomForm(div, schema);
  } catch (e) { setResult(div.querySelector(".cs-import-result"), e.message, false); }
}

/**
 * Body keys every schema shares. Any other field name is free: the library
 * reads a custom schema's body by its schema id, so names may repeat across
 * schemas (e.g. `descriptive_statistic`, or `heart_rate` in a newer OMH version).
 */
const RESERVED_FIELDS = new Set(["effective_time_frame", "extra"]);

function addCustomSchema(div) {
  const field = (cls) => div.querySelector(cls).value.trim();
  const form = div.querySelector(".custom-schema");
  const imported = form.dataset.imported ? JSON.parse(form.dataset.imported) : {};
  const spec = {
    id: field(".cs-id").toLowerCase(),
    version: imported.version || "1.0",
    source: imported.source || "",
    deprecated: imported.deprecated || "",
    description: field(".cs-desc"),
    loinc: field(".cs-loinc"),
    role: field(".cs-role"),
    fields: [...div.querySelectorAll(".cs-field")].map((r) => {
      const f = {
        name: r.querySelector(".cf-name").value.trim().toLowerCase(),
        kind: r.querySelector(".cf-kind").value,
        unit: r.querySelector(".cf-unit").value.trim(),
      };
      if (f.kind === "enum")
        f.values = [...new Set(r.querySelector(".cf-values").value.split(",").map((v) => v.trim()).filter(Boolean))];
      return f;
    }).filter((f) => f.name),
  };
  if (spec.role === "event") spec.trigger = field(".cs-trigger");
  const fail = (msg) => setResult(div.querySelector(".cs-result"), msg, false);
  if (!/^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$/.test(spec.id))
    return fail("Schema id must look like namespace:name (lowercase, digits, hyphens).");
  if (!spec.description) return fail("Say what the schema measures.");
  const builtIn = state.schemas.find((s) => s.id === spec.id);
  if (builtIn)
    return fail(`${spec.id} is built in (v${builtIn.version}) — tick it instead.`);
  if ([...document.querySelectorAll(".dt-schema")].some((c) => c.value === spec.id))
    return fail(`${spec.id} already exists — tick it instead.`);
  if (!spec.fields.length) return fail("Add at least one body field.");
  const seen = new Set();
  for (const f of spec.fields) {
    if (!/^[a-z][a-z0-9_]*$/.test(f.name))
      return fail(`Field "${f.name}" must be snake_case (lowercase, digits, underscores).`);
    if (RESERVED_FIELDS.has(f.name)) return fail(`"${f.name}" is reserved — rename the field.`);
    if (seen.has(f.name)) return fail(`Field "${f.name}" is listed twice.`);
    if (f.kind === "quantity" && !f.unit) return fail(`Field "${f.name}" needs a unit (or make it a plain number).`);
    if (f.kind === "enum" && f.values.length < 2)
      return fail(`Field "${f.name}" needs at least two values, comma-separated (or make it text).`);
    seen.add(f.name);
  }

  addCustomChip(div, spec);
  div.querySelectorAll(".custom-schema > .grid2 input, .cs-loinc").forEach((i) => (i.value = ""));
  div.querySelector(".cs-role").value = "measurement";
  syncRole(div);
  div.querySelector(".cs-fields").innerHTML = "";
  delete form.dataset.imported;
  div.querySelector(".cs-import-result").textContent = "";
  addFieldRow(div);
  setResult(div.querySelector(".cs-result"), `Added ${spec.id}.`, true);
  refresh();
}

/**
 * The ticked chip of a custom schema, carrying its full spec (dataset.custom)
 * — seeded with the study ("customSchemas", wizard-only) so loading the study
 * for an update brings the schema back intact.
 */
function addCustomChip(div, spec) {
  const chip = document.createElement("label");
  chip.className = "custom";
  chip.title = `${spec.description} — ${(spec.fields || []).map((f) => f.name).join(", ")} — custom, declared in the app (first field = primary value)`;
  chip.innerHTML = `<input type="checkbox" class="dt-schema" checked> ${spec.role === "event" ? "⚡ " : ""}${esc(spec.id)}
    <button class="chip-remove" title="Remove custom schema">×</button>`;
  const cb = chip.querySelector("input");
  cb.value = spec.id;
  const { dataType, ...clean } = spec;   // the data type is where the chip sits
  cb.dataset.custom = JSON.stringify(clean);
  chip.querySelector(".chip-remove").addEventListener("click", (e) => {
    e.preventDefault(); chip.remove(); refresh();
  });
  div.querySelector(".schemas").appendChild(chip);
}

// ------------------------------------------------------- schema previews
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

/** Example body for a custom schema, one entry per declared field. */
const customBody = (fields) => Object.fromEntries(fields.map((f) => [f.name,
  f.kind === "quantity" ? { value: 0.0, unit: f.unit }
  : f.kind === "number" ? 0.0
  : f.kind === "enum" ? f.values[0]
  : "<text>"]));

/**
 * A full OMH data point as the library would store it (before encryption) —
 * header + example body + the effective time frame every body carries: an
 * interval for measurements, a single instant (`date_time`) for events.
 */
function exampleDataPoint(schema, body, samplingSeconds, isEvent) {
  const [namespace, name] = schema.id.split(":");
  const end = new Date(Date.UTC(2026, 0, 15, 22, 30, 30));
  const start = new Date(end - (samplingSeconds || 30) * 1000);
  const timeFrame = isEvent
    ? { date_time: end.toISOString() }
    : { time_interval: { start_date_time: start.toISOString(), end_date_time: end.toISOString() } };
  return {
    header: {
      id: "3f2a…-uuid",
      creation_date_time: end.toISOString(),
      schema_id: { namespace, name, version: schema.version || "1.0" },
      acquisition_provenance: { source_name: "<your sensor>", modality: "sensed" },
    },
    body: { ...body, effective_time_frame: timeFrame },
  };
}

/** Renders one preview card per ticked schema of a data type. */
function renderPreviews(div) {
  const checked = [...div.querySelectorAll(".dt-schema:checked")];
  const sampling = Number(div.querySelector(".dt-sampling").value) || null;
  const key = checked.map((c) => c.value).join(",") + "|" + sampling;
  const box = div.querySelector(".schema-previews");
  if (box.dataset.key === key) return;            // unchanged — keep <details> open state
  box.dataset.key = key;

  box.innerHTML = checked.map((cb) => {
    const custom = cb.dataset.custom && JSON.parse(cb.dataset.custom);
    const schema = custom
      ? { id: custom.id, version: custom.version || "1.0", name: custom.description, loinc: custom.loinc || null,
          ucum: null, category: "—" }
      : state.schemas.find((s) => s.id === cb.value);
    if (!schema) return "";
    const body = custom ? customBody(custom.fields) : schema.exampleBody;
    const isEvent = (custom || schema).role === "event";
    const cadence = isEvent ? "one point per trigger"
      : sampling ? `at most one point every ${sampling} s`
      : "one point per processor epoch (natural cadence)";
    const triggerSource = { sensor: "sensor marker channel", app: "in-app button", protocol: "scheduled by the app" };
    const enums = custom ? custom.fields.filter((f) => f.kind === "enum")
      : Object.entries(schema.enums || {}).map(([name, values]) => ({ name, values }));
    const source = custom ? triggerSource[custom.trigger] || custom.trigger : "processor marker";
    return `
      <div class="preview${custom ? " custom" : ""}">
        <div class="preview-head">
          <b>${esc(schema.name)}</b> <code>${esc(schema.id)} v${esc(schema.version || "1.0")}</code>
          ${custom ? `<span class="tag warn">custom — declared in the app</span>` : ""}
          ${custom?.source ? `<a class="tag" href="${esc(custom.source)}" target="_blank" rel="noopener">source schema ↗</a>` : ""}
          ${custom?.deprecated ? `<span class="tag warn" title="${esc(custom.deprecated)}">deprecated upstream</span>` : ""}
        </div>
        <div class="tags">
          <span class="tag">LOINC ${esc(schema.loinc || "none")}</span>
          ${custom ? `<span class="tag">${custom.fields.length} field${custom.fields.length > 1 ? "s" : ""}</span>`
                   : `<span class="tag">UCUM ${esc(schema.ucum || "coded")}</span>
                      <span class="tag">FHIR ${esc(schema.category)}</span>`}
          ${isEvent ? `<span class="tag">⚡ event — ${esc(source)}</span>` : ""}
          <span class="tag">${esc(cadence)}</span>
          ${isEvent && sampling ? `<span class="tag" title="The library never throttles events (instant time frame)">
            sampling ${sampling} s does not apply to triggers</span>` : ""}
        </div>
        ${enums.map((f) => `<small><code>${esc(f.name)}</code> is one of: ${f.values.map(esc).join(", ")}</small>`).join("")}
        <details><summary>Example data point</summary>
          <pre>${esc(JSON.stringify(exampleDataPoint(schema, body, sampling, isEvent), null, 2))}</pre>
          ${custom ? `<small>Exact shape — the app declares it as a SchemaDefinition. The first
            field is the primary value (card, chart, FHIR value); units become UCUM in FHIR export.</small>` : ""}
          <small>Encrypted on the device before upload; the server only ever sees ciphertext.</small>
        </details>
      </div>`;
  }).join("") || `<p class="muted">Tick a schema to preview the data it records.</p>`;
}

/** Reads the form into {study, dataTypeSchemas, customSchemas}; disables schemas taken elsewhere. */
function collect() {
  const types = [];
  const dataTypeSchemas = {};
  const customSchemas = [];
  const taken = {};
  document.querySelectorAll(".datatype").forEach((div) => {
    const id = div.querySelector(".dt-id").value.trim();
    const checked = [...div.querySelectorAll(".dt-schema:checked")];
    const schemas = checked.map((c) => c.value);
    checked.filter((c) => c.dataset.custom).forEach((c) =>
      customSchemas.push({ ...JSON.parse(c.dataset.custom), dataType: id }));
    schemas.forEach((s) => (taken[s] = div.dataset.n));
    const t = {
      id,
      name: div.querySelector(".dt-name").value.trim(),
      description: div.querySelector(".dt-desc").value.trim(),
      purpose: div.querySelector(".dt-purpose").value.trim(),
      retentionDays: Number(div.querySelector(".dt-retention").value || 0),
      isEssential: div.querySelector(".dt-essential").checked,
    };
    const sampling = div.querySelector(".dt-sampling").value;
    if (sampling) t.samplingSeconds = Number(sampling);
    types.push(t);
    if (id) dataTypeSchemas[id] = schemas;
  });
  // A schema can feed only one data type — grey it out everywhere else.
  document.querySelectorAll(".datatype").forEach((div) => {
    div.querySelectorAll(".dt-schema").forEach((cb) => {
      cb.disabled = taken[cb.value] !== undefined && taken[cb.value] !== div.dataset.n;
    });
    renderPreviews(div);
  });
  const study = {
    studyName: $("study-name").value.trim(),
    studyVersion: Number($("study-version").value || 1),
    dataTypes: types,
  };
  // A new study opens for enrolment; an update leaves the enrolment status as
  // it is (seed_study keeps it when omitted) — use "Close enrolment" for that.
  if (studyMode !== "update") study.isActive = true;
  const joinCode = $("join-code").value.trim();
  if (joinCode) study.joinCode = joinCode;
  return { study, dataTypeSchemas, customSchemas };
}

function refresh() {
  const { study } = collect();
  const preview = JSON.parse(JSON.stringify(study));
  if (preview.joinCode) preview.joinCode = "•••";
  $("study-preview").textContent = JSON.stringify(preview, null, 2);
}
["study-name", "study-version", "join-code"].forEach((id) =>
  $(id).addEventListener("input", refresh));

/** The form as seed_study() wants it, with the server-side schema → data-type map. */
function studyForSeed() {
  const { study, dataTypeSchemas, customSchemas } = collect();
  // Stored with the study for this wizard only (reloaded on update); apps never read it.
  study.customSchemas = customSchemas;
  // Server-side copy of schemaToConsentType — lets retention.sql apply each
  // data type's retentionDays to the right records.
  study.schemaDataTypes = Object.fromEntries(Object.entries(dataTypeSchemas)
    .flatMap(([typeId, schemas]) => schemas.map((s) => [s, typeId])));
  return study;
}

$("btn-seed").addEventListener("click", async () => {
  const updating = studyMode === "update";
  if (updating && !updateTarget) return setResult("seed-result", "Load the study to update first.", false);
  setResult("seed-result", updating ? "updating…" : "creating…", true);
  try {
    const r = await api("/api/seed", {
      dbUrl: $("db-url").value.trim(),
      study: studyForSeed(),
      piEmail: $("pi-email").value.trim(),
      ...(updating ? {
        updateStudyId: updateTarget.studyId,
        oldPiPrivateKeyPem: oldPiKeyPem || undefined,
        removeOtherStaff: String($("remove-other-staff").checked),
      } : {}),
    });
    setResult("seed-result",
      `Study ${r.result.action} — id ${r.result.studyId} (${r.result.dataTypes} data types).` +
      (r.handover ? " Handed over to the new PI key." : "") +
      (r.removedStaff ? ` Removed ${r.removedStaff} previous staff link(s).` : "") +
      (r.staff ? ` ${r.staff}` : " No PI staff account linked yet — exports are refused until one is."), true);
    if (updating) loadStudies();   // refresh issuedAt / version in the picker
    setQuestionnaireStudy(r.result.studyId, r.result.studyName);
  } catch (e) { setResult("seed-result", e.message, false); }
});

// ------------------------------------------------- step 3: update a study
let studyMode = "new";
let studies = [];
let updateTarget = null;
let oldPiKeyPem = null;   // in memory only — never stored or sent anywhere but the local wizard

document.querySelectorAll("input[name=study-mode]").forEach((r) =>
  r.addEventListener("change", () => {
    studyMode = r.value;
    $("update-box").classList.toggle("hidden", studyMode !== "update");
    $("btn-seed").textContent = studyMode === "update" ? "Update study on the backend" : "Create study on the backend";
  }));

async function loadStudies() {
  try {
    const r = await api("/api/studies", { dbUrl: $("db-url").value.trim() });
    studies = r.studies;
    const keep = $("study-picker").value;
    $("study-picker").innerHTML = studies.length
      ? studies.map((s) => `<option value="${esc(s.studyId)}">${esc(s.studyName)} — v${esc(s.studyVersion)}` +
          ` (${s.participants} participant${s.participants === 1 ? "" : "s"}` +
          `${s.endedAt ? ", ended" : s.isActive ? "" : ", enrolment closed"})</option>`).join("")
      : `<option value="">No studies on this backend</option>`;
    if (keep) $("study-picker").value = keep;
    $("btn-load-study").disabled = !studies.length;
  } catch (e) { setResult("update-info", e.message, false); }
}
$("btn-load-studies").addEventListener("click", loadStudies);

$("btn-load-study").addEventListener("click", () => {
  const s = studies.find((x) => x.studyId === $("study-picker").value);
  if (!s) return;
  updateTarget = s;
  $("study-name").value = s.studyName;
  // Suggest the next version — the update must raise it.
  $("study-version").value = (Math.floor(Number(s.studyVersion) || 0) + 1).toFixed(1);
  $("join-code").value = "";
  const schemasByType = {};
  Object.entries(s.schemaDataTypes || {}).forEach(([schema, typeId]) =>
    (schemasByType[typeId] = schemasByType[typeId] || []).push(schema));
  $("datatypes").innerHTML = "";
  (s.dataTypes || []).forEach((t) => addDataType({
    id: t.id, name: t.name, description: t.description, purpose: t.purpose,
    retentionDays: t.retentionDays, samplingSeconds: t.samplingSeconds,
    essential: t.isEssential, schemas: schemasByType[t.id] || [],
    customSchemas: (s.customSchemas || []).filter((c) => c.dataType === t.id),
  }));
  setQuestionnaireStudy(s.studyId, s.studyName);

  const sig = { valid: "signed with this wizard's study-signing key ✓",
    "other-key": "NOT verifiable with this wizard's study-signing key — see Check update",
    unsigned: "unsigned (apps that pin a signing key refuse it)" }[s.signature] || s.signature;
  setResult("update-info",
    `Loaded "${s.studyName}" v${s.studyVersion}, issued ${s.issuedAt || "—"}. Definition ${sig}. ` +
    (s.piKeyIsLocal ? "PI key: this wizard's ✓" : "PI key: a different one — handover needed."),
    s.signature !== "other-key");
  $("handover-box").classList.toggle("hidden", s.piKeyIsLocal);
  $("btn-check-update").disabled = !!s.endedAt;
  $("update-changes").innerHTML = "";
  setResult("check-update-result", "", true);
  showLifecycle(s);
  if (s.endedAt) setResult("update-info", `"${s.studyName}" ended on ${String(s.endedAt).slice(0, 16)} — it can no longer be changed.`, false);
});

// ------------------------------------- step 3: enrolment and end of study
function showLifecycle(s) {
  $("lifecycle-box").classList.remove("hidden");
  $("btn-toggle-enrolment").textContent = s.isActive ? "Close enrolment" : "Reopen enrolment";
  $("btn-toggle-enrolment").disabled = !!s.endedAt;
  $("btn-end-study").disabled = !!s.endedAt;
  setResult("lifecycle-result", s.endedAt ? `Ended ${String(s.endedAt).slice(0, 16)}.`
    : s.isActive ? "Enrolment is open." : "Enrolment is closed.", true);
}

async function reloadTarget() {
  await loadStudies();
  updateTarget = studies.find((x) => x.studyId === updateTarget.studyId) || updateTarget;
  showLifecycle(updateTarget);
  $("btn-check-update").disabled = !!updateTarget.endedAt;
}

$("btn-toggle-enrolment").addEventListener("click", async () => {
  if (!updateTarget) return;
  try {
    await api("/api/study/enrolment", { dbUrl: $("db-url").value.trim(), studyId: updateTarget.studyId,
      open: String(!updateTarget.isActive) });
    await reloadTarget();
  } catch (e) { setResult("lifecycle-result", e.message, false); }
});

$("btn-end-study").addEventListener("click", async () => {
  if (!updateTarget) return;
  const typed = prompt(`End "${updateTarget.studyName}" for good? ${updateTarget.participants} enrolled ` +
    `participant(s) stop collecting. This cannot be undone.\n\nType the study name to confirm:`);
  if (typed === null) return;
  if (typed.trim() !== updateTarget.studyName) return setResult("lifecycle-result", "Name did not match — nothing changed.", false);
  try {
    const r = await api("/api/study/end", { dbUrl: $("db-url").value.trim(), studyId: updateTarget.studyId });
    await reloadTarget();
    setResult("lifecycle-result", `Study ended at ${String(r.result.endedAt).slice(0, 16)} ` +
      `(${r.result.participants} participant(s)).`, true);
  } catch (e) { setResult("lifecycle-result", e.message, false); }
});

$("old-pi-key").addEventListener("change", async (e) => {
  const file = e.target.files[0];
  oldPiKeyPem = file ? await file.text() : null;
});

$("btn-check-update").addEventListener("click", async () => {
  if (!updateTarget) return;
  setResult("check-update-result", "checking…", true);
  $("update-changes").innerHTML = "";
  try {
    const r = await api("/api/check-update", {
      dbUrl: $("db-url").value.trim(), studyId: updateTarget.studyId,
      study: studyForSeed(), oldPiPrivateKeyPem: oldPiKeyPem || undefined,
    });
    setResult("check-update-result",
      "Ready to update" + (r.handover ? " — the current PI key was proven; the study moves to your PI key." : "."), true);
    $("update-changes").innerHTML = (r.changes.length ? r.changes : ["No consent-relevant changes."])
      .map((c) => `<li>${esc(c)}</li>`).join("");
  } catch (e) { setResult("check-update-result", e.message, false); }
});

// ---------------------------------------------- step 3: questionnaires
// Published per study, separately from the study definition (no re-consent):
// see the explanation in index.html and backend/questionnaires.sql.
let qStudyId = null;
let qPublished = [];

function setQuestionnaireStudy(studyId, studyName) {
  qStudyId = studyId;
  $("q-study").textContent = `Questionnaires of "${studyName}" (${studyId}).`;
  $("btn-q-new").disabled = false;
  $("btn-q-reload").disabled = false;
  $("q-editor").classList.add("hidden");
  loadQuestionnaires();
}

async function loadQuestionnaires() {
  if (!qStudyId) return;
  try {
    const r = await api("/api/questionnaires", { dbUrl: $("db-url").value.trim(), studyId: qStudyId });
    qPublished = r.questionnaires;
    setResult("q-list-result", "", true);
    $("q-list").innerHTML = qPublished.length ? qPublished.map((q, i) => `
      <div class="q-row">
        <b>${esc(q.definition.title)}</b> <small>${esc(q.questionnaireId)}</small>
        <span class="tag">v${esc(q.version)}</span>
        <span class="tag">data type ${esc(q.dataType)}</span>
        ${q.isActive ? "" : `<span class="tag">retired</span>`}
        ${q.signatureValid ? "" : `<span class="tag" title="Apps pinning this wizard's key refuse it — publish a new version">⚠ not signed with this wizard's key</span>`}
        <small>${q.definition.items.length} question(s) · published ${esc(String(q.publishedAt).slice(0, 16))}</small>
        <span class="row">
          <button class="secondary q-edit" data-i="${i}">Edit</button>
          <button class="secondary q-active" data-i="${i}">${q.isActive ? "Retire" : "Restore"}</button>
        </span>
      </div>`).join("") : `<p class="muted">No questionnaires yet.</p>`;
    document.querySelectorAll(".q-edit").forEach((b) =>
      b.addEventListener("click", () => openQuestionnaireEditor(qPublished[b.dataset.i].definition)));
    document.querySelectorAll(".q-active").forEach((b) => b.addEventListener("click", async () => {
      const q = qPublished[b.dataset.i];
      try {
        await api("/api/questionnaire/active", { dbUrl: $("db-url").value.trim(), studyId: qStudyId,
          questionnaireId: q.questionnaireId, active: String(!q.isActive) });
        loadQuestionnaires();
      } catch (e) { setResult("q-list-result", e.message, false); }
    }));
  } catch (e) { setResult("q-list-result", e.message, false); }
}
$("btn-q-reload").addEventListener("click", loadQuestionnaires);

/** Opens the editor — empty, or with a published definition (the next version). */
function openQuestionnaireEditor(def = null) {
  const types = collect().study.dataTypes.filter((t) => t.id);
  $("q-datatype").innerHTML = types.map((t) =>
    `<option value="${esc(t.id)}">${esc(t.name || t.id)} (${esc(t.id)})</option>`).join("");
  $("q-id").value = def?.questionnaireId || "";
  $("q-id").readOnly = !!def;
  if (def) {
    if (!types.some((t) => t.id === def.dataType))
      $("q-datatype").insertAdjacentHTML("beforeend", `<option value="${esc(def.dataType)}">${esc(def.dataType)} (not in the form)</option>`);
    $("q-datatype").value = def.dataType;
  }
  $("q-title").value = def?.title || "";
  $("q-description").value = def?.description || "";
  $("q-intro").value = def?.intro || "";
  $("q-items").innerHTML = "";
  (def?.items || [{ type: "scale", min: 0, max: 10, required: true }]).forEach(addQuestionRow);
  setResult("q-result", def ? `Editing — publishing creates version ${def.version + 1}.` : "", true);
  $("q-editor").classList.remove("hidden");
  refreshQuestionnairePreview();
  $("q-editor").scrollIntoView({ behavior: "smooth", block: "start" });
}
$("btn-q-new").addEventListener("click", () => openQuestionnaireEditor());
$("btn-q-cancel").addEventListener("click", () => $("q-editor").classList.add("hidden"));

const slug = (s) => s.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "")
  .replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");

function addQuestionRow(item = {}) {
  const row = document.createElement("div");
  row.className = "q-item";   // not "datatype": collect() reads every .datatype as a study data type
  row.innerHTML = `
    <div class="head"><b>Question</b>
      <span><button class="secondary qi-up" title="Move up">↑</button>
        <button class="secondary qi-down" title="Move down">↓</button>
        <button class="remove qi-remove">remove</button></span></div>
    <div class="grid3">
      <label>Type <select class="qi-type">
        <option value="scale">Scale (pick a number)</option>
        <option value="number">Number (typed)</option>
        <option value="choice">One choice</option>
        <option value="multichoice">Multiple choice</option>
        <option value="text">Free text</option></select></label>
      <label>Id <small>(snake_case, keep it across versions)</small> <input class="qi-id" placeholder="wellbeing"></label>
      <label>Short label <small>(charts, export)</small> <input class="qi-label" placeholder="Wellbeing"></label>
    </div>
    <label>Question <input class="qi-text" placeholder="Overall, how do you feel today?"></label>
    <label class="checkline"><input type="checkbox" class="qi-required"> Required</label>
    <div class="grid3 qi-range">
      <label>Minimum <input class="qi-min" type="number" step="any"></label>
      <label>Maximum <input class="qi-max" type="number" step="any"></label>
      <label class="qi-unit-wrap">Unit <small>(optional)</small> <input class="qi-unit" placeholder="hours"></label>
    </div>
    <div class="grid2 qi-scale-labels">
      <label>Label at the minimum <input class="qi-minlabel" placeholder="very bad"></label>
      <label>Label at the maximum <input class="qi-maxlabel" placeholder="excellent"></label>
    </div>
    <label class="qi-options-wrap">Options <small>(one per line: <code>value = Label</code>, or just the label)</small>
      <textarea class="qi-options" rows="4" placeholder="very_bad = Very bad&#10;bad = Bad&#10;neutral = Neutral"></textarea></label>
    <label class="qi-maxlength-wrap">Maximum length <input class="qi-maxlength" type="number" min="1" max="5000" placeholder="500"></label>`;
  const q = (c) => row.querySelector(c);
  q(".qi-type").value = item.type || "scale";
  q(".qi-id").value = item.id || "";
  q(".qi-label").value = item.label || "";
  q(".qi-text").value = item.text || "";
  q(".qi-required").checked = !!item.required;
  q(".qi-min").value = item.min ?? "";
  q(".qi-max").value = item.max ?? "";
  q(".qi-unit").value = item.unit || "";
  q(".qi-minlabel").value = item.minLabel || "";
  q(".qi-maxlabel").value = item.maxLabel || "";
  q(".qi-options").value = (item.options || []).map((o) => `${o.value} = ${o.label}`).join("\n");
  q(".qi-maxlength").value = item.maxLength ?? "";
  const sync = () => {
    const t = q(".qi-type").value;
    q(".qi-range").hidden = !(t === "scale" || t === "number");
    q(".qi-unit-wrap").hidden = t !== "number";
    q(".qi-scale-labels").hidden = t !== "scale";
    q(".qi-options-wrap").hidden = !(t === "choice" || t === "multichoice");
    q(".qi-maxlength-wrap").hidden = t !== "text";
  };
  q(".qi-type").addEventListener("change", sync);
  // Suggest an id from the question text until one is typed.
  q(".qi-text").addEventListener("input", () => {
    if (!q(".qi-id").dataset.touched && !q(".qi-id").readOnly) q(".qi-id").value = slug(q(".qi-text").value).slice(0, 40);
  });
  q(".qi-id").addEventListener("input", () => (q(".qi-id").dataset.touched = "1"));
  if (item.id) q(".qi-id").dataset.touched = "1";
  q(".qi-remove").addEventListener("click", () => { row.remove(); refreshQuestionnairePreview(); });
  q(".qi-up").addEventListener("click", () => { row.previousElementSibling?.before(row); refreshQuestionnairePreview(); });
  q(".qi-down").addEventListener("click", () => { row.nextElementSibling?.after(row); refreshQuestionnairePreview(); });
  row.addEventListener("input", refreshQuestionnairePreview);
  row.addEventListener("change", refreshQuestionnairePreview);
  sync();
  $("q-items").appendChild(row);
}
$("btn-q-add-item").addEventListener("click", () => { addQuestionRow({ type: "choice" }); refreshQuestionnairePreview(); });
["q-id", "q-title", "q-description", "q-intro", "q-datatype"].forEach((id) =>
  $(id).addEventListener("input", refreshQuestionnairePreview));

/** The editor as the wizard sends it (the server adds format, study, version, issuedAt and signs). */
function collectQuestionnaire() {
  const num = (v) => (v === "" ? undefined : Number(v));
  return {
    questionnaireId: $("q-id").value.trim(),
    dataType: $("q-datatype").value,
    title: $("q-title").value.trim(),
    description: $("q-description").value.trim(),
    intro: $("q-intro").value.trim(),
    items: [...document.querySelectorAll(".q-item")].map((row) => {
      const q = (c) => row.querySelector(c);
      const type = q(".qi-type").value;
      const item = { id: q(".qi-id").value.trim(), type, text: q(".qi-text").value.trim(),
        required: q(".qi-required").checked, label: q(".qi-label").value.trim() };
      if (type === "scale" || type === "number") { item.min = num(q(".qi-min").value); item.max = num(q(".qi-max").value); }
      if (type === "scale") { item.minLabel = q(".qi-minlabel").value.trim(); item.maxLabel = q(".qi-maxlabel").value.trim(); }
      if (type === "number") item.unit = q(".qi-unit").value.trim();
      if (type === "choice" || type === "multichoice") {
        item.options = q(".qi-options").value.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => {
          const [v, ...rest] = l.split("=");
          return rest.length ? { value: v.trim(), label: rest.join("=").trim() } : { value: slug(l), label: l };
        });
      }
      if (type === "text") item.maxLength = num(q(".qi-maxlength").value);
      return item;
    }),
  };
}

function refreshQuestionnairePreview() {
  $("q-preview").textContent = JSON.stringify(collectQuestionnaire(), null, 2);
}

$("btn-q-publish").addEventListener("click", async () => {
  if (!qStudyId) return setResult("q-result", "Create or load the study first.", false);
  setResult("q-result", "signing & publishing…", true);
  try {
    const r = await api("/api/questionnaire/publish", {
      dbUrl: $("db-url").value.trim(), studyId: qStudyId, questionnaire: collectQuestionnaire(),
    });
    setResult("q-result", `Questionnaire ${r.result.questionnaireId} ${r.result.action} — version ${r.result.version}. ` +
      "Phones pick it up the next time the app opens.", true);
    $("q-id").readOnly = true;
    loadQuestionnaires();
  } catch (e) { setResult("q-result", e.message, false); }
});

// ------------------------------------------------------------------- step 4
$("btn-brief").addEventListener("click", async () => {
  setResult("brief-result", "writing…", true);
  try {
    const { study, dataTypeSchemas, customSchemas } = collect();
    const r = await api("/api/brief", {
      study,
      app: {
        appName: $("app-name").value.trim(),
        packageId: $("package-id").value.trim(),
        appFolder: $("app-folder").value.trim(),
        sensorDescription: $("sensor-desc").value.trim(),
        screenNotes: $("screen-notes").value.trim(),
        healthConnect: $("hc-notes").value.trim(),
        collectionMode: document.querySelector("input[name=collection-mode]:checked").value,
        collectionScreenInRelease: $("collection-screen-release").checked,
        dataTypeSchemas,
        customSchemas,
      },
    });
    setResult("brief-result", r.missing.length
      ? `Brief written. The app's local.properties (${r.localProperties}) lacks ${r.missing.join(", ")} — ` +
        "set the backend credentials in step 1, then write the brief again."
      : `Brief written. The app's SDK path and backend credentials are in ${r.localProperties} (never commit it); ` +
        `its check file is ${r.handoff} (commit it — the app's test checks against it).`,
      !r.missing.length);
    $("agent-box").classList.remove("hidden");
    $("brief-path").textContent = r.path;
    fillAgentSelect();
  } catch (e) { setResult("brief-result", e.message, false); }
});

// Agent handoff: known CLIs get a ready command (+ launch when on PATH);
// "Other" shows the bare prompt for any agent (IDE agent, chat, …).
function fillAgentSelect() {
  const sel = $("agent-select");
  if (!sel.options.length) {
    state.agents.forEach((a) =>
      sel.add(new Option(a.name + (a.available ? " (installed)" : ""), a.id)));
    sel.add(new Option("Other agent — copy the prompt", "other"));
    const installed = state.agents.find((a) => a.available);
    if (installed) sel.value = installed.id;
  }
  showAgent();
}

function showAgent() {
  const agent = state.agents.find((a) => a.id === $("agent-select").value);
  $("agent-cmd").textContent = agent ? agent.command : state.agentPrompt;
  $("btn-copy-agent").textContent = agent ? "Copy command" : "Copy prompt";
  $("btn-launch").textContent = agent ? "Launch " + agent.name : "Launch";
  $("btn-launch").classList.toggle("hidden", !(agent && agent.available));
  setResult("launch-result", "", true);
}

$("agent-select").addEventListener("change", showAgent);

// Sessions are started on the collection screen, so it is always kept there.
document.querySelectorAll("input[name=collection-mode]").forEach((r) =>
  r.addEventListener("change", () => {
    const session = r.value === "SESSION" && r.checked;
    $("collection-screen-release").disabled = session;
    if (session) $("collection-screen-release").checked = false;
  }));

$("btn-launch").addEventListener("click", async () => {
  try {
    const agent = state.agents.find((a) => a.id === $("agent-select").value);
    await api("/api/launch-agent", { agent: agent.id });
    setResult("launch-result", agent.name + " opened in a new terminal.", true);
  } catch (e) { setResult("launch-result", e.message, false); }
});

// ----------------------------------------------------------- screenshots
// Guide screenshots are optional drop-ins (see public/img/shots/README.md).
// A missing file becomes a dashed placeholder telling the maintainer what to
// capture and where to put it — participants of the wizard just see no gap
// once the real PNGs are in place.
function initScreenshots() {
  document.querySelectorAll("img.screenshot").forEach((img) => {
    const replace = () => {
      const ph = document.createElement("div");
      ph.className = "shot-placeholder";
      ph.innerHTML = `<b>Screenshot slot</b> — drop a PNG at <code>setup-wizard/public/${
        img.getAttribute("src")}</code><br><small>${img.dataset.hint || ""}</small>`;
      img.replaceWith(ph);
    };
    if (img.complete && img.naturalWidth === 0) replace();   // failed before JS loaded
    else img.addEventListener("error", replace);
  });
}

// -------------------------------------------------------------------- init
(async () => {
  initScreenshots();
  state = await api("/api/state");
  $("repo-root").textContent = state.repoRoot;
  if (state.supabaseUrl) $("supabase-url").value = state.supabaseUrl;
  // A sensible starter data type researchers can edit rather than face a blank page.
  addDataType({ id: "heart_rate", name: "Heart Rate", essential: true, schemas: ["omh:heart-rate"] });
})();
