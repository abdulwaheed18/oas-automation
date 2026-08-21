'use strict';

/* ------------------------------------------------------------------ */
/*  State                                                              */
/* ------------------------------------------------------------------ */
const state = {
    branding: null,
    settings: null,
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
    await Promise.all([loadBranding(), loadSettings()]);
    wireEvents();
});

async function loadBranding() {
    try {
        const b = await fetch('api/branding').then(r => r.json());
        state.branding = b;
        const accent = b.primaryColor || '#4f46e5';
        document.documentElement.style.setProperty('--accent', accent);
        document.documentElement.style.setProperty('--accent-dark', darken(accent, 0.72));
        document.documentElement.style.setProperty('--accent-soft', hexToRgba(accent, 0.10));
        $('appName').textContent = b.appName;
        $('tagline').textContent = b.tagline || '';
        document.title = b.appName;
        $('company').textContent = b.company || '';
        $('footerCompany').innerHTML = b.company
            ? '<span class="fbrand">' + escapeHtml(b.company) + '</span> · ' + escapeHtml(b.appName)
            : escapeHtml(b.appName);
        $('footerContact').textContent = b.supportContact ? ('Support: ' + b.supportContact) : ('© ' + new Date().getFullYear());
        $('brandMark').textContent = (b.appName || 'OA').replace(/[^A-Za-z]/g, '').substring(0, 2).toUpperCase() || 'OA';
    } catch (e) {
        console.warn('Branding load failed', e);
    }
}

async function loadSettings() {
    try {
        state.settings = await fetch('api/settings').then(r => r.json());
    } catch (e) {
        state.settings = { successCodes: '200,201,202,204', rejectCodes: '400-499', authRejectCodes: '401,403', robustnessCodes: '100-499' };
    }
}

function wireEvents() {
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
    $('btnApplyCodes').addEventListener('click', applyCodesToCases);

    document.querySelectorAll('[data-goto]').forEach(btn =>
        btn.addEventListener('click', () => showStep(parseInt(btn.dataset.goto, 10))));
}

/* ------------------------------------------------------------------ */
/*  Navigation                                                         */
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
    try { const j = await res.json(); return j.message || ('HTTP ' + res.status); }
    catch (_) { return 'HTTP ' + res.status; }
}

/* ------------------------------------------------------------------ */
/*  Step 1: parse                                                      */
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
    } else {
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
    p.endpoints.forEach(ep => {
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
/*  Step 3: generate, edit, execute                                    */
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
        // prefill the code settings inputs
        const s = state.settings;
        $('cfgSuccess').value = s.successCodes;
        $('cfgReject').value = s.rejectCodes;
        $('cfgAuth').value = s.authRejectCodes;
        $('cfgRobust').value = s.robustnessCodes;
        renderCases();
        showStep(3);
    } catch (e) {
        msg(2, 'Request failed: ' + e.message, 'error');
    } finally {
        overlay(false);
    }
}

function renderCases() {
    $('caseSummary').textContent =
        `${state.cases.length} test case(s) generated. Expand any case to edit the payload, headers and expected codes — each negative case changes exactly one field.`;

    const groups = {};
    state.cases.forEach((c, idx) => {
        const key = c.method + ' ' + c.endpointPath;
        (groups[key] = groups[key] || []).push(idx);
    });

    const container = $('caseGroups');
    container.innerHTML = '';
    const single = Object.keys(groups).length <= 2;
    Object.entries(groups).forEach(([key, idxs]) => {
        const det = document.createElement('details');
        det.className = 'case-group';
        det.open = single;
        const method = key.split(' ')[0];
        const path = key.substring(method.length + 1);
        const sum = document.createElement('summary');
        sum.innerHTML = `<span class="method m-${method}">${method}</span> <span class="ep-path">${escapeHtml(path)}</span> <span class="count">${idxs.length} cases</span>`;
        det.appendChild(sum);
        const rows = document.createElement('div');
        rows.className = 'case-rows';
        idxs.forEach(idx => rows.appendChild(renderCaseRow(idx)));
        det.appendChild(rows);
        container.appendChild(det);
    });
}

function renderCaseRow(idx) {
    const c = state.cases[idx];
    const wrap = document.createElement('div');
    wrap.className = 'case';

    const head = document.createElement('div');
    head.className = 'case-head';
    head.innerHTML = `
        <span class="cat cat-${c.category}">${c.category}</span>
        <span class="case-title">${escapeHtml(c.name)}</span>
        <span class="case-exp">${escapeHtml(c.expectedStatuses)}</span>
        <span class="case-toggle">▸</span>`;
    head.addEventListener('click', () => wrap.classList.toggle('open'));
    wrap.appendChild(head);

    const body = document.createElement('div');
    body.className = 'case-body';

    // description
    let html = `<div class="desc">${escapeHtml(c.description || '')}</div>`;

    // expected codes (editable)
    html += `<div class="fld"><span class="k">Expected status codes (${escapeHtml(c.expectedStatusFamily || '')})</span>
        <div class="exp-row">
            <input type="text" class="edit-exp" data-idx="${idx}" value="${escapeAttr(c.expectedStatuses)}"/>
        </div></div>`;

    // auth
    html += `<div class="fld"><span class="k">Authorization</span> ` + authFieldHtml(c, idx) + `</div>`;

    // headers (editable values)
    const hdrNames = Object.keys(c.headers || {});
    if (hdrNames.length) {
        html += `<div class="fld"><span class="k">Headers</span>`;
        hdrNames.forEach(name => {
            html += `<div class="hdr-row">
                <input type="text" value="${escapeAttr(name)}" readonly/>
                <input type="text" class="edit-hdr" data-idx="${idx}" data-name="${escapeAttr(name)}" value="${escapeAttr(c.headers[name])}"/>
            </div>`;
        });
        html += `</div>`;
    }

    // body (editable) — show for cases that carry or could carry a body
    if (c.body !== null && c.body !== undefined) {
        html += `<div class="fld"><span class="k">Request body${c.contentType ? ' (' + escapeHtml(c.contentType) + ')' : ''}</span>
            <textarea class="edit-body" data-idx="${idx}" rows="4">${escapeHtml(c.body)}</textarea></div>`;
    }

    body.innerHTML = html;
    wrap.appendChild(body);

    // bind edits
    body.querySelector('.edit-exp').addEventListener('input', e => {
        state.cases[idx].expectedStatuses = e.target.value;
        head.querySelector('.case-exp').textContent = e.target.value;
    });
    const bodyEl = body.querySelector('.edit-body');
    if (bodyEl) bodyEl.addEventListener('input', e => state.cases[idx].body = e.target.value);
    body.querySelectorAll('.edit-hdr').forEach(inp =>
        inp.addEventListener('input', e => state.cases[idx].headers[e.target.dataset.name] = e.target.value));
    const authEl = body.querySelector('.edit-auth');
    if (authEl) authEl.addEventListener('input', e => state.cases[idx].authorization = e.target.value);

    return wrap;
}

function authFieldHtml(c, idx) {
    if (c.authMode === 'VALID') return `<span class="auth-chip">Bearer &lt;token from field above&gt;</span>`;
    if (c.authMode === 'MISSING') return `<span class="auth-chip">— none sent —</span>`;
    if (c.authMode === 'NONE') return `<span class="auth-chip">endpoint not secured</span>`;
    // OVERRIDE — editable
    return `<input type="text" class="edit-auth" data-idx="${idx}" value="${escapeAttr(c.authorization || '')}"/>`;
}

function applyCodesToCases() {
    const map = {
        '2xx (accept)': $('cfgSuccess').value.trim(),
        '4xx (reject)': $('cfgReject').value.trim(),
        '401/403 (unauthorized)': $('cfgAuth').value.trim(),
        'no 5xx (handled)': $('cfgRobust').value.trim(),
    };
    let changed = 0;
    state.cases.forEach(c => {
        const v = map[c.expectedStatusFamily];
        if (v) { c.expectedStatuses = v; changed++; }
    });
    renderCases();
    msg(3, `Applied to ${changed} case(s).`, 'ok');
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
        <div class="sc total"><div class="n">${r.total}</div><div class="l">Total</div></div>
        <div class="sc pass"><div class="n">${r.passed}</div><div class="l">Passed</div></div>
        <div class="sc fail"><div class="n">${r.failed}</div><div class="l">Failed</div></div>
        <div class="sc error"><div class="n">${r.errored}</div><div class="l">Errors</div></div>`;

    const posFailed = r.results.some(x => x.category === 'POSITIVE' && x.verdict !== 'PASS');
    const banner = $('resultBanner');
    if (posFailed) {
        banner.className = 'banner warn show';
        banner.textContent = '⚠ A positive baseline request did not succeed — the base URL or bearer token may be wrong, which can make the other verdicts unreliable. Check the POSITIVE case first.';
    } else {
        banner.className = 'banner';
        banner.textContent = '';
    }
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
                    <span class="cat cat-${r.category}">${r.category}</span>
                    <span class="result-name">${escapeHtml(r.name)}</span>
                    <span class="result-status">${r.actualStatus || '—'} · ${r.latencyMs}ms</span>
                </div>
                <div class="result-detail">
                    <div class="row"><span class="k">Endpoint</span> ${r.method} ${escapeHtml(r.endpointPath)}</div>
                    <div class="row"><span class="k">Request URL</span> ${escapeHtml(r.requestUrl)}</div>
                    <div class="row"><span class="k">Expected</span> ${escapeHtml(r.expectedStatusFamily || '')}</div>
                    <div class="row"><span class="k">Actual status</span> ${r.actualStatus}</div>
                    <div class="row"><span class="k">Verdict</span> ${escapeHtml(r.message || '')}</div>
                    <div class="row"><span class="k">Response body</span></div>
                    <pre>${escapeHtml(r.responseSnippet || '(empty)')}</pre>
                </div>`;
            div.querySelector('.result-head').addEventListener('click', () => div.classList.toggle('open'));
            list.appendChild(div);
        });
    if (list.children.length === 0) list.innerHTML = '<p class="sub">No results for this filter.</p>';
}

/* ------------------------------------------------------------------ */
/*  Report download                                                    */
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
.card{border:1px solid #e5e7eb;border-radius:10px;padding:14px 22px;text-align:center;min-width:90px;}
.card .n{font-size:24px;font-weight:700;} .pass .n{color:#15a34a;} .fail .n{color:#dc2626;} .error .n{color:#d97706;}
table{width:100%;border-collapse:collapse;font-size:13px;margin-top:10px;}
th,td{border:1px solid #e5e7eb;padding:7px 10px;text-align:left;vertical-align:top;}
th{background:#f5f6fa;}
tr.FAIL td:nth-child(2){color:#dc2626;font-weight:700;}
tr.PASS td:nth-child(2){color:#15a34a;font-weight:700;}
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

function darken(hex, factor) {
    const c = parseHex(hex);
    if (!c) return hex;
    return `rgb(${Math.round(c.r * factor)}, ${Math.round(c.g * factor)}, ${Math.round(c.b * factor)})`;
}
function hexToRgba(hex, alpha) {
    const c = parseHex(hex);
    if (!c) return `rgba(79,70,229,${alpha})`;
    return `rgba(${c.r}, ${c.g}, ${c.b}, ${alpha})`;
}
function parseHex(hex) {
    if (!hex) return null;
    let h = hex.replace('#', '').trim();
    if (h.length === 3) h = h.split('').map(x => x + x).join('');
    if (h.length !== 6) return null;
    return { r: parseInt(h.substr(0, 2), 16), g: parseInt(h.substr(2, 2), 16), b: parseInt(h.substr(4, 2), 16) };
}
