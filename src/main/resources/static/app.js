// Pricing exceptions UI. Plain JavaScript, no build step.
// Rules: every value from the server or the user is rendered with textContent, never as HTML.
// Every call sends X-User-Id. Commands that change a request send If-Match with the ETag the
// detail panel last saw; a 412 shows "This request changed — reload".
(function () {
  'use strict';

  const USER_KEY = 'pricing-exceptions.user';
  const state = {
    users: [],
    applications: [],
    user: null,          // {id, name, role, approvalLimitBps}
    requests: [],
    selected: null,      // the request shown in the detail panel
    etag: null,          // ETag of the selected request as last loaded
    createKey: null,     // Idempotency-Key for the current create form
    supersedeKey: null,  // Idempotency-Key for the selected request, per (id, version)
    supersedeKeyFor: '', // "<id>@<version>" the supersede key belongs to
    busy: false,         // a command is in flight: buttons are disabled
  };

  const $ = (id) => document.getElementById(id);

  // Idempotency-Keys. crypto.randomUUID() exists only in secure contexts (https or localhost);
  // elsewhere build a version-4 UUID from getRandomValues so the UI still works.
  function newKey() {
    if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
    return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
  }

  // ---------- tiny DOM helpers (textContent only) ----------

  function el(tag, attrs, children) {
    const node = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (k === 'text') node.textContent = v;
        else if (k === 'class') node.className = v;
        else if (k === 'hidden') node.hidden = Boolean(v);
        else if (k.startsWith('on')) {
          if (typeof v !== 'function') throw new TypeError('event handlers must be functions: ' + k);
          node.addEventListener(k.slice(2), v);
        }
        else if (v !== null && v !== undefined) node.setAttribute(k, String(v));
      }
    }
    (children || []).forEach((c) => node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c));
    return node;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function showError(message) {
    $('error').textContent = message || '';
  }

  function shortId(id) {
    return id ? id.slice(0, 8) : '';
  }

  function formatInstant(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
  }

  // ---------- HTTP ----------

  class ApiError extends Error {
    constructor(status, problem) {
      super(problem && problem.detail ? problem.detail : 'HTTP ' + status);
      this.status = status;
      this.code = problem && problem.code ? problem.code : '';
      this.problem = problem || {};
    }
  }

  async function api(method, path, options) {
    const opts = options || {};
    const headers = { 'Accept': 'application/json, application/problem+json' };
    if (state.user) headers['X-User-Id'] = state.user.id;
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
    if (opts.ifMatch) headers['If-Match'] = opts.ifMatch;
    if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;

    const response = await fetch(path, {
      method,
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    });
    const text = await response.text();
    let json = null;
    if (text) {
      try { json = JSON.parse(text); } catch (e) { json = null; }
    }
    if (!response.ok) {
      throw new ApiError(response.status, json);
    }
    return { status: response.status, body: json, etag: response.headers.get('ETag'), headers: response.headers };
  }

  function describe(error) {
    if (error instanceof ApiError) {
      if (error.status === 412) return 'This request changed — reload';
      const parts = [String(error.status)];
      if (error.code) parts.push(error.code);
      let text = parts.join(' ') + ': ' + error.message;
      if (Array.isArray(error.problem.errors)) {
        text += '\n' + error.problem.errors.map((e) => '- ' + e.field + ': ' + e.message).join('\n');
      }
      return text;
    }
    return 'Network error: ' + (error && error.message ? error.message : String(error));
  }

  function setBusy(busy) {
    state.busy = busy;
    document.querySelectorAll('button').forEach((b) => { b.disabled = busy; });
  }

  async function run(action) {
    if (state.busy) return;
    showError('');
    setBusy(true);
    try {
      await action();
    } catch (error) {
      showError(describe(error));
      if (error instanceof ApiError && error.status === 412 && state.selected) {
        renderDetail(state.selected, { stale: true });
      }
    } finally {
      setBusy(false);
    }
  }

  // A key is kept after a network error or 5xx so that a retry replays safely. After a 4xx the
  // user will change the request, and the same key with a different body would be refused.
  function needsFreshKey(error) {
    return error instanceof ApiError && error.status >= 400 && error.status < 500
      && error.code !== 'IDEMPOTENCY_IN_PROGRESS';
  }

  // ---------- users ----------

  async function loadUsers() {
    const saved = localStorage.getItem(USER_KEY);
    // /api/users itself needs a known user: try the saved one, then the first seeded RM.
    state.user = { id: saved || 'rm-anna' };
    try {
      state.users = (await api('GET', '/api/users')).body;
    } catch (error) {
      state.user = { id: 'rm-anna' };
      state.users = (await api('GET', '/api/users')).body;
    }
    const select = $('user-select');
    clear(select);
    state.users.forEach((u) => {
      select.appendChild(el('option', { value: u.id, text: u.name + ' (' + u.id + ', ' + u.role + ')' }));
    });
    const chosen = state.users.find((u) => u.id === state.user.id) || state.users[0];
    select.value = chosen.id;
    setUser(chosen);
  }

  function setUser(user) {
    state.user = user;
    localStorage.setItem(USER_KEY, user.id);
    const limit = user.role === 'REVIEWER' ? ', approval limit ' + user.approvalLimitBps + ' bps' : '';
    $('user-info').textContent = user.role + limit;
    $('create-section').hidden = user.role !== 'RM';
    $('lookup-section').hidden = user.role !== 'MORTGAGE_PROCESS';
    $('lookup-result').textContent = '';
    if (user.role === 'RM') {
      try { resetCreateForm(); } catch (error) { showError('Create form unavailable: ' + error.message); }
    }
  }

  // ---------- applications ----------

  async function loadApplications() {
    state.applications = (await api('GET', '/api/applications')).body;
    fillApplicationSelect($('create-application'), (a) => a.status === 'OPEN');
    fillApplicationSelect($('lookup-application'), () => true);
  }

  function fillApplicationSelect(select, filter) {
    clear(select);
    state.applications.filter(filter).forEach((a) => {
      select.appendChild(el('option', {
        value: a.id,
        text: a.id + ' · ' + a.customerRef + ' · ' + a.standardRateBps + ' bps · ' + a.status + ' · ' + a.ownerRmId,
      }));
    });
  }

  // ---------- list ----------

  async function loadList() {
    const status = $('status-filter').value;
    const path = '/api/pricing-exceptions' + (status ? '?status=' + encodeURIComponent(status) : '');
    state.requests = (await api('GET', path)).body;
    renderList();
  }

  function renderList() {
    const tbody = $('request-list');
    clear(tbody);
    if (!Array.isArray(state.requests) || state.requests.length === 0) {
      tbody.appendChild(el('tr', null, [el('td', { colspan: 5, class: 'muted', text: 'No requests.' })]));
      return;
    }
    state.requests.forEach((r) => {
      const open = () => run(() => select(r.id));
      const row = el('tr', {
        class: 'selectable' + (state.selected && state.selected.id === r.id ? ' selected' : ''),
        tabindex: 0,                         // keyboard reachable; no button role, Pico would style the row as a button
        'aria-label': 'Open request ' + r.id,
        onclick: open,
        onkeydown: (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); open(); } },
      }, [
        el('td', { text: r.applicationId }),
        el('td', { text: r.discountBps + ' bps' }),
        el('td', { class: 'status-' + r.status, text: r.status }),
        el('td', { text: r.requestedBy }),
        el('td', { text: formatInstant(r.createdAt) }),
      ]);
      tbody.appendChild(row);
    });
  }

  // ---------- detail ----------

  async function select(id) {
    const result = await api('GET', '/api/pricing-exceptions/' + encodeURIComponent(id));
    state.selected = result.body;
    state.etag = result.etag;
    const keyFor = result.body.id + '@' + result.body.version;
    if (state.supersedeKeyFor !== keyFor) {
      state.supersedeKey = newKey();
      state.supersedeKeyFor = keyFor;
    }
    renderDetail(state.selected, {});
    renderList();
  }

  function canDecide(r) {
    return state.user.role === 'REVIEWER' && r.status === 'PENDING' && r.requestedBy !== state.user.id;
  }

  function isOwner(r) {
    return state.user.role === 'RM' && r.requestedBy === state.user.id;
  }

  function renderDetail(r, options) {
    const box = $('detail');
    clear(box);

    const heading = el('h3', null, [
      el('span', { class: 'status-' + r.status, text: r.status }),
      ' · ',
      el('code', { class: 'id', text: r.id }),
    ]);
    box.appendChild(heading);

    if (options.stale) {
      box.appendChild(el('p', null, [
        el('strong', { text: 'This request changed — reload' }),
        ' ',
        el('button', { type: 'button', text: 'Reload', onclick: () => run(() => select(r.id)) }),
      ]));
    }

    const facts = [
      ['Application', r.applicationId],
      ['Requested by', r.requestedBy],
      ['Discount', r.discountBps + ' bps'],
      ['Reason', r.reason],
      ['Created', formatInstant(r.createdAt)],
      ['Version (ETag)', state.etag || String(r.version)],
    ];
    if (r.decidedBy) facts.push(['Decided by', r.decidedBy + ' at ' + formatInstant(r.decidedAt)]);
    if (r.decisionComment) facts.push(['Comment', r.decisionComment]);
    if (r.approvedDiscountBps !== null && r.approvedDiscountBps !== undefined) facts.push(['Approved discount', r.approvedDiscountBps + ' bps']);
    if (r.supersedesId) facts.push(['Supersedes', linkTo(r.supersedesId)]);
    if (r.supersededById) facts.push(['Superseded by', linkTo(r.supersededById)]);

    const dl = el('dl');
    facts.forEach(([k, v]) => {
      dl.appendChild(el('dt', { text: k }));
      dl.appendChild(el('dd', null, [v instanceof Node ? v : document.createTextNode(String(v ?? ''))]));
    });
    box.appendChild(dl);

    box.appendChild(el('h4', { text: 'History' }));
    const table = el('table', null, [
      el('thead', null, [el('tr', null, ['Event', 'Who', 'When', 'From', 'To', 'Comment'].map((h) => el('th', { text: h })))]),
      el('tbody', null, (r.events || []).map((e) => el('tr', null, [
        el('td', { text: e.type }),
        el('td', { text: e.actorId }),
        el('td', { text: formatInstant(e.occurredAt) }),
        el('td', { text: e.fromStatus || '' }),
        el('td', { text: e.toStatus }),
        el('td', { text: e.comment || '' }),
      ]))),
    ]);
    box.appendChild(table);

    if (!options.stale) renderActions(box, r);
  }

  function linkTo(id) {
    return el('a', { href: '#', text: id, onclick: (ev) => { ev.preventDefault(); run(() => select(id)); } });
  }

  function renderActions(box, r) {
    if (r.status !== 'PENDING') return;

    if (canDecide(r)) {
      const comment = el('input', { type: 'text', maxlength: 500, placeholder: 'Comment (optional)', 'aria-label': 'Decision comment' });
      const limit = state.user.approvalLimitBps;
      const overLimit = r.discountBps > limit;
      const approve = el('button', { type: 'button', text: 'Approve', onclick: () => run(() => decide(r, 'APPROVED', comment.value)) });
      if (overLimit) approve.title = r.discountBps + ' bps is above your limit of ' + limit + ' bps; the server will refuse.';
      const decline = el('button', { type: 'button', class: 'secondary', text: 'Decline', onclick: () => run(() => decide(r, 'DECLINED', comment.value)) });
      box.appendChild(el('h4', { text: 'Decision' }));
      box.appendChild(el('div', { class: 'actions' }, [comment, approve, decline]));
      if (overLimit) box.appendChild(el('p', { class: 'muted', text: 'Note: ' + r.discountBps + ' bps is above your approval limit (' + limit + ' bps).' }));
    }

    if (isOwner(r)) {
      box.appendChild(el('h4', { text: 'Owner actions' }));
      box.appendChild(el('div', { class: 'actions' }, [
        el('button', { type: 'button', class: 'secondary', text: 'Withdraw', onclick: () => run(() => withdraw(r)) }),
      ]));

      const discount = el('input', { type: 'number', min: 1, max: 100, step: 1, required: '', placeholder: 'New discount (bps)', 'aria-label': 'New discount in basis points' });
      const reason = el('input', { type: 'text', maxlength: 500, required: '', placeholder: 'New reason', 'aria-label': 'New reason' });
      const form = el('form', {
        onsubmit: (ev) => { ev.preventDefault(); run(() => supersede(r, Number(discount.value), reason.value)); },
      }, [
        el('h4', { text: 'Supersede (replace with new terms)' }),
        el('div', { class: 'actions' }, [discount, reason, el('button', { type: 'submit', text: 'Supersede' })]),
        el('p', { class: 'muted' }, ['Idempotency-Key: ', el('code', { id: 'supersede-key', text: state.supersedeKey })]),
      ]);
      box.appendChild(form);
    }
  }

  // ---------- commands ----------

  async function decide(r, outcome, comment) {
    const result = await api('POST', '/api/pricing-exceptions/' + encodeURIComponent(r.id) + '/decision', {
      ifMatch: state.etag,
      body: { outcome, comment: comment || null },
    });
    await afterChange(result.body.id);
  }

  async function withdraw(r) {
    const result = await api('POST', '/api/pricing-exceptions/' + encodeURIComponent(r.id) + '/withdrawal', {
      ifMatch: state.etag,
    });
    await afterChange(result.body.id);
  }

  async function supersede(r, discountBps, reason) {
    let result;
    try {
      result = await api('POST', '/api/pricing-exceptions/' + encodeURIComponent(r.id) + '/supersessions', {
        ifMatch: state.etag,
        idempotencyKey: state.supersedeKey,
        body: { discountBps, reason },
      });
    } catch (error) {
      if (needsFreshKey(error)) {
        state.supersedeKey = newKey();
        state.supersedeKeyFor = '';
        const shown = $('supersede-key');
        if (shown) shown.textContent = state.supersedeKey; // F3: keep the displayed key truthful
      }
      throw error;
    }
    await afterChange(result.body.id); // the new request
  }

  async function afterChange(idToShow) {
    await loadList();
    await select(idToShow);
  }

  // ---------- create form ----------

  function newCreateKey() {
    state.createKey = newKey();
    $('create-key').textContent = state.createKey;
  }

  function resetCreateForm() {
    newCreateKey();
    $('create-form').reset();
  }

  async function create(ev) {
    ev.preventDefault();
    await run(async () => {
      let result;
      try {
        result = await api('POST', '/api/pricing-exceptions', {
          idempotencyKey: state.createKey,
          body: {
            applicationId: $('create-application').value,
            discountBps: Number($('create-discount').value),
            reason: $('create-reason').value,
          },
        });
      } catch (error) {
        if (needsFreshKey(error)) newCreateKey(); // the user will correct the form: fresh key
        throw error;
      }
      resetCreateForm();
      await afterChange(result.body.id);
    });
  }

  // ---------- mortgage process lookup ----------

  async function lookup(ev) {
    ev.preventDefault();
    const out = $('lookup-result');
    out.textContent = '';
    await run(async () => {
      const applicationId = $('lookup-application').value;
      const result = await api('GET', '/api/applications/' + encodeURIComponent(applicationId) + '/approved-discount');
      const d = result.body;
      out.textContent = d.applicationId + ': approved discount ' + d.discountBps + ' bps (request ' + shortId(d.requestId)
        + ', decided by ' + d.decidedBy + ' at ' + formatInstant(d.decidedAt) + ')';
    });
  }

  // ---------- wiring ----------

  async function reloadAll() {
    const failures = [];
    try { await loadList(); } catch (error) { failures.push(error); }
    try { await loadApplications(); } catch (error) { failures.push(error); }
    if (failures.length > 0) throw failures[0];
    if (state.selected) {
      try {
        await select(state.selected.id);
      } catch (error) {
        state.selected = null;
        state.etag = null;
        clear($('detail'));
        $('detail').appendChild(el('p', { class: 'muted', text: 'Select a request.' }));
        renderList();
        throw error; // run() shows it
      }
    }
  }

  document.addEventListener('DOMContentLoaded', () => {
    $('user-select').addEventListener('change', (ev) => {
      const user = state.users.find((u) => u.id === ev.target.value);
      const previous = state.user;
      if (!user) return;
      run(async () => {
        setUser(user);
        try {
          await reloadAll();
        } catch (error) {
          setUser(previous); // keep the screen consistent with the user shown
          $('user-select').value = previous.id;
          throw error;
        }
      });
    });
    $('status-filter').addEventListener('change', () => run(loadList));
    $('refresh').addEventListener('click', () => run(reloadAll));
    $('create-form').addEventListener('submit', create);
    $('lookup-form').addEventListener('submit', lookup);

    run(async () => {
      try {
        await loadUsers();
      } catch (error) {
        showError(describe(error));
        if (!state.user) state.user = { id: 'rm-anna' };
      }
      await reloadAll();
    });
  });
})();
