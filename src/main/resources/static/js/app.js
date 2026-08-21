'use strict';

/* ------------------------------------------------------------------ */
/*  State                                                              */
/* ------------------------------------------------------------------ */
const state = {
    branding: null,
    sourceType: 'FILE',
    parseResult: null,
    cases: [],
    execResult: null,
};

const $ = (id) => document.getElementById(id);

/* ------------------------------------------------------------------ */
/*  Bootstrap                                                          */
/* ------------------------------------------------------------------ */
window.addEventListener('DOMContentLoaded', async () => {
    await loadBranding();
    wireEvents();
});

async function loadBranding() {
    try {
        const b = await fetch('api/branding').then(r => r.json());
        state.branding = b;
        document.documentElement.style.setProperty('--accent', b.primaryColor || '#4f46e5');
        $('appName').textContent = b.appName;
        $('tagline').textContent = b.tagline || '';
        document.title = b.appName;
        $('company').textContent = b.company ? b.company : '';
        $('footerCompany').textContent = b.company ? '© ' + new Date().getFullYear() + ' ' + b.company : '';
        $('footerContact').textContent = b.supportContact || '';
        $('brandMark').textContent = (b.appName || 'OA').replace(/[^A-Za-z]/g, '').substring(0, 2).toUpperCase() || 'OA';
    } catch (e) {
        console.warn('Branding load failed', e);
    }
}

function wireEvents() {
    // Source tabs
    document.querySelectorAll('.src-tab').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.src-tab').forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            state.sourceType = tab.dataset.src;
            ['FILE', 'CLIPBOARD', 'NEXUS'].forEach(s => $('src-' + s).classList.toggle('hidden', s !== state.sourceType));
        });
    });

    $('btnParse').addEventListener('click', doParse);
    $('btnGenerate').addEventListener('click', doGenerate);
    $('btnExecute').addEventListener('click', doExecute);
    $('btnReport').addEventListener('click', downloadReport);
    $('btnRestart').addEventListener('click', () => location.reload());
    $('selectAll').addEventListener('click', () => setAllEndpoints(true));
    $('selectNone').addEventListener('click', () => setAllEndpoints(false));
    $('resultFilter').addEventListener('change', renderResults);

    document.querySelectorAll('[data-goto]').forEach(btn =>
        btn.addEventListener('click', () => showStep(parseInt(btn.dataset.goto, 10))));
}

/* ------------------------------------------------------------------ */
/*  Navigation helpers                                                 */
/* ------------------------------------------------------------------ */
function showStep(n) {
    for (let i = 1; i <= 4; i++) $('panel-' + i).classList.toggle('hidden', i !== n);
    document.querySelectorAll('.stepper .step').forEach(s => {
        const step = parseInt(s.dataset.step, 10);
        s.classList.toggle('active', step === n);
        s.classList.toggle('done', step < n);
    });
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function overlay(show, text) {
    $('overlay').classList.toggle('hidden', !show);
    if (text) $('overlayText').textContent = text;
}

function msg(step, text, kind) {
    const el = $('msg-' + step);
    el.textContent = text || '';
    el.className = 'msg' + (kind ? ' ' + kind : '');
}

async function readError(res) {
    try {
        const j = await res.json();
        return j.message || ('HTTP ' + res.status);
    } catch (_) {
        return 'HTTP ' + res.status;
    }
}

/* ------------------------------------------------------------------ */
/*  Step 1: parse spec                                                 */
/* ------------------------------------------------------------------ */
async function doParse() {
    msg(1, '');
    const apiName = $('apiName').value.trim();
    if (!apiName) { msg(1, 'API Name is required.', 'error'); return; }

    const fd = new FormData();
    fd.append('apiName', apiName);
    fd.append('apiVersion', $('apiVersion').value.trim());
    fd.append('note', $('note').value.trim());
    fd.append('sourceType', state.sourceType);

    if (state.sourceType === 'FILE') {
        const f = $('specFile').files[0];
        if (!f) { msg(1, 'Please choose a YAML/JSON file.', 'error'); return; }
        fd.append('file', f);
    } else if (state.sourceType === 'CLIPBOARD') {
        const c = $('specContent').value.trim();
        if (!c) { msg(1, 'Please paste the spec content.', 'error'); return; }
        fd.append('content', c);
    } else if (state.sourceType === 'NEXUS') {
        const u = $('nexusUrl').value.trim();
        if (!u) { msg(1, 'Please enter the Nexus ZIP URL.', 'error'); return; }
        fd.append('nexusUrl', u);
    }

    overlay(true, 'Parsing OpenAPI spec…');
    try {
        const res = await fetch('api/spec/parse', { method: 'POST', body: fd });
        if (!res.ok) { msg(1, await readError(res), 'error'); return; }
        state.parseResult = await res.json();
        renderEndpoints();
        showStep(2);
    } catch (e) {
        msg(1, 'Request failed: ' + e.message, 'error');
    } finally {
        overlay(false);
    }
}

/* ------------------------------------------------------------------ */
/*  Step 2: endpoints                                                  */
/* ------------------------------------------------------------------ */
function renderEndpoints() {
    const p = state.parseResult;
    $('specSummary').textContent =
        `${p.specTitle || p.apiName} ${p.specVersion ? '· ' + p.specVersion : ''} — ${p.endpointCount} endpoint(s) found.`;

    const list = $('endpointList');
    list.innerHTML = '';
    p.endpoints.forEach((ep, i) => {
        const row = document.createElement('label');
        row.className = 'endpoint';
        row.innerHTML = `
            <input type="checkbox" class="ep-cb" data-key="${escapeAttr(ep.method + ' ' + ep.path)}" checked/>
            <span class="method m-${ep.method}">${ep.method}</span>
            <span>
                <span class="ep-path">${escapeHtml(ep.path)}</span>
                ${ep.summary ? `<div class="ep-summary">${escapeHtml(ep.summary)}</div>` : ''}
            </span>
            <span class="badges">
                ${ep.secured ? '<span class="tag secured">SECURED</span>' : ''}
                ${ep.hasRequestBody ? '<span class="tag body">BODY</span>' : ''}
            </span>`;
        list.appendChild(row);
    });
    updateEpCount();
    list.querySelectorAll('.ep-cb').forEach(cb => cb.addEventListener('change', updateEpCount));
}

function setAllEndpoints(v) {
    document.querySelectorAll('.ep-cb').forEach(cb => cb.checked = v);
    updateEpCount();
}

function updateEpCount() {
    const total = document.querySelectorAll('.ep-cb').length;
    const sel = document.querySelectorAll('.ep-cb:checked').length;
    $('epCount').textContent = `${sel} / ${total} selected`;
}

function selectedKeys() {
    return Array.from(document.querySelectorAll('.ep-cb:checked')).map(cb => cb.dataset.key);
}

/* ------------------------------------------------------------------ */
/*  Step 3: generate + execute                                         */
/* ------------------------------------------------------------------ */
async function doGenerate() {
    msg(2, '');
    const keys = selectedKeys();
    if (keys.length === 0) { msg(2, 'Select at least one endpoint.', 'error'); return; }

    overlay(true, 'Generating test cases…');
    try {
        const res = await fetch('api/testcases/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: state.parseResult.sessionId, endpointKeys: keys }),
        });
        if (!res.ok) { msg(2, await readError(res), 'error'); return; }
        const data = await res.json();
        state.cases = data.cases;
        renderCases();
        showStep(3);
    } catch (e) {
        msg(2, 'Request failed: ' + e.message, 'error');
    } finally {
        overlay(false);
    }
}

function renderCases() {
    $('caseSummary').textContent = `${state.cases.length} test case(s) generated across the selected endpoints. Each negative case changes exactly one field.`;

    // group by endpoint
    const groups = {};
    state.cases.forEach(c => {
        const key = c.method + ' ' + c.endpointPath;
        (groups[key] = groups[key] || []).push(c);
    });

    const container = $('caseGroups');
    container.innerHTML = '';
    Object.entries(groups).forEach(([key, cases]) => {
        const det = document.createElement('details');
        det.className = 'case-group';
        det.open = Object.keys(groups).length <= 2;
        const method = key.split(' ')[0];
        det.innerHTML = `
            <summary><span class="method m-${method}">${method}</span> ${escapeHtml(key.substring(method.length + 1))} <span class="count">${cases.length} cases</span></summary>
            <table>
                <thead><tr><th>Category</th><th>Test case</th><th>Field</th><th>Expected</th></tr></thead>
                <tbody>
                ${cases.map(c => `
                    <tr>
                        <td><span class="cat">${c.category}</span></td>
                        <td>${escapeHtml(c.name)}</td>
                        <td>${c.negativeField ? escapeHtml(c.negativeField) : '—'}</td>
                        <td>${escapeHtml(c.expectedStatusFamily || '')}</td>
                    </tr>`).join('')}
                </tbody>
            </table>`;
        container.appendChild(det);
    });
}

async function doExecute() {
    msg(3, '');
    const target = $('targetBaseUrl').value.trim();
    if (!target) { msg(3, 'Target base URL is required.', 'error'); return; }

    overlay(true, `Executing ${state.cases.length} test case(s)…`);
    try {
        const res = await fetch('api/execute', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                sessionId: state.parseResult.sessionId,
                targetBaseUrl: target,
                bearerToken: $('bearerToken').value.trim(),
                cases: state.cases,
            }),
        });
        if (!res.ok) { msg(3, await readError(res), 'error'); return; }
        state.execResult = await res.json();
        renderSummary();
        renderResults();
        showStep(4);
    } catch (e) {
        msg(3, 'Request failed: ' + e.message, 'error');
    } finally {
        overlay(false);
    }
}

/* ------------------------------------------------------------------ */
/*  Step 4: results                                                    */
/* ------------------------------------------------------------------ */
function renderSummary() {
    const r = state.execResult;
    $('summaryCards').innerHTML = `
        <div class="sc"><div class="n">${r.total}</div><div class="l">Total</div></div>
        <div class="sc pass"><div class="n">${r.passed}</div><div class="l">Passed</div></div>
        <div class="sc fail"><div class="n">${r.failed}</div><div class="l">Failed</div></div>
        <div class="sc error"><div class="n">${r.errored}</div><div class="l">Errors</div></div>`;
}

function renderResults() {
    const filter = $('resultFilter').value;
    const list = $('resultList');
    list.innerHTML = '';
    state.execResult.results
        .filter(r => filter === 'ALL' || r.verdict === filter)
        .forEach(r => {
            const div = document.createElement('div');
            div.className = 'result ' + r.verdict;
            div.innerHTML = `
                <div class="result-head">
                    <span class="verdict ${r.verdict}">${r.verdict}</span>
                    <span class="cat">${r.category}</span>
                    <span class="result-name">${escapeHtml(r.name)}</span>
                    <span class="result-status">${r.actualStatus || '—'} · ${r.latencyMs}ms</span>
                </div>
                <div class="result-detail">
                    <div class="row"><span class="k">Endpoint</span> ${r.method} ${escapeHtml(r.endpointPath)}</div>
                    <div class="row"><span class="k">Request URL</span> ${escapeHtml(r.requestUrl)}</div>
                    <div class="row"><span class="k">Expected</span> ${escapeHtml(r.expectedStatusFamily || '')} — ${escapeHtml(r.expectedOutcome || '')}</div>
                    <div class="row"><span class="k">Actual status</span> ${r.actualStatus}</div>
                    <div class="row"><span class="k">Verdict</span> ${escapeHtml(r.message || '')}</div>
                    <div class="row"><span class="k">Response</span></div>
                    <pre>${escapeHtml(r.responseSnippet || '(empty)')}</pre>
                </div>`;
            div.querySelector('.result-head').addEventListener('click', () => div.classList.toggle('open'));
            list.appendChild(div);
        });
    if (list.children.length === 0) list.innerHTML = '<p class="sub">No results for this filter.</p>';
}

/* ------------------------------------------------------------------ */
/*  Report download (self-contained HTML)                              */
/* ------------------------------------------------------------------ */
function downloadReport() {
    const p = state.parseResult, r = state.execResult, b = state.branding || {};
    const rows = r.results.map(x => `
        <tr class="${x.verdict}">
            <td>${x.id}</td><td>${x.verdict}</td><td>${x.category}</td>
            <td>${escapeHtml(x.method + ' ' + x.endpointPath)}</td>
            <td>${escapeHtml(x.name)}</td>
            <td>${escapeHtml(x.expectedStatusFamily || '')}</td>
            <td>${x.actualStatus}</td>
            <td>${escapeHtml(x.message || '')}</td>
        </tr>`).join('');

    const html = `<!DOCTYPE html><html><head><meta charset="utf-8"/>
<title>OAS Test Report — ${escapeHtml(p.apiName)}</title>
<style>
body{font-family:Segoe UI,Arial,sans-serif;margin:32px;color:#1f2430;}
h1{margin-bottom:4px;} .muted{color:#6b7280;}
.meta{background:#f5f6fa;border:1px solid #e5e7eb;border-radius:10px;padding:16px 20px;margin:18px 0;}
.meta div{margin:3px 0;}
.cards{display:flex;gap:12px;margin:16px 0;}
.card{border:1px solid #e5e7eb;border-radius:10px;padding:14px 22px;text-align:center;}
.card .n{font-size:24px;font-weight:700;} .pass .n{color:#16a34a;} .fail .n{color:#dc2626;} .error .n{color:#d97706;}
table{width:100%;border-collapse:collapse;font-size:13px;margin-top:10px;}
th,td{border:1px solid #e5e7eb;padding:7px 10px;text-align:left;vertical-align:top;}
th{background:#f5f6fa;}
tr.FAIL td:nth-child(2){color:#dc2626;font-weight:700;}
tr.PASS td:nth-child(2){color:#16a34a;font-weight:700;}
tr.ERROR td:nth-child(2){color:#d97706;font-weight:700;}
.note{white-space:pre-wrap;}
</style></head><body>
<h1>${escapeHtml(b.appName || 'OAS Automation Test Suite')} — Report</h1>
<div class="muted">${escapeHtml(b.company || '')} · Generated ${new Date().toLocaleString()}</div>

<div class="meta">
    <div><b>API Name:</b> ${escapeHtml(p.apiName)}</div>
    <div><b>API Version:</b> ${escapeHtml(p.apiVersion || '—')}</div>
    <div><b>Spec Title / Version:</b> ${escapeHtml((p.specTitle || '—') + ' / ' + (p.specVersion || '—'))}</div>
    <div><b>Target Base URL:</b> ${escapeHtml(r.targetBaseUrl)}</div>
    <div><b>Executed:</b> ${new Date(r.executedAtEpochMs).toLocaleString()}</div>
</div>

<div class="cards">
    <div class="card"><div class="n">${r.total}</div>Total</div>
    <div class="card pass"><div class="n">${r.passed}</div>Passed</div>
    <div class="card fail"><div class="n">${r.failed}</div>Failed</div>
    <div class="card error"><div class="n">${r.errored}</div>Errors</div>
</div>

<h3>Notes</h3>
<div class="meta note">${escapeHtml(p.note || '(none)')}</div>

<h3>Detailed results</h3>
<table>
<thead><tr><th>ID</th><th>Verdict</th><th>Category</th><th>Endpoint</th><th>Test case</th><th>Expected</th><th>Actual</th><th>Details</th></tr></thead>
<tbody>${rows}</tbody>
</table>
</body></html>`;

    const blob = new Blob([html], { type: 'text/html' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `oas-report-${(p.apiName || 'api').replace(/\W+/g, '_')}-${Date.now()}.html`;
    document.body.appendChild(a);
    a.click();
    a.remove();
}

/* ------------------------------------------------------------------ */
/*  Utils                                                              */
/* ------------------------------------------------------------------ */
function escapeHtml(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function escapeAttr(s) { return escapeHtml(s); }
