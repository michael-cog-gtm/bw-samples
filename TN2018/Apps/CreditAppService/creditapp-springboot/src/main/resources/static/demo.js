'use strict';

const $ = id => document.getElementById(id);
const modes = ['ONLINE', 'DOWN', 'SLOW'];
const modeLabel = {ONLINE: 'Online', DOWN: 'Down', SLOW: 'Slow'};
const modeHelp = {ONLINE: 'Ready to respond.', DOWN: 'Connection refused: this bureau is not listening.', SLOW: 'Delayed response: the credit app will time out.'};
let busy = false;
let selectedSsn = null;

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

async function request(path, body, allowError = false) {
  const response = await fetch(path, body === undefined ? {} : {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
  });
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : null; }
  catch { throw new Error('The service returned an unreadable response. Please try again.'); }
  if (!response.ok && !allowError) throw new Error(data?.error || `Request could not be completed (HTTP ${response.status}).`);
  return {data, status: response.status, traceId: response.headers.get('X-Credit-Trace-Id')};
}

function notice(message = '') {
  $('notice').textContent = message;
  $('notice').hidden = !message;
}

function setBusy(value) {
  busy = value;
  document.querySelectorAll('.action, #manual-form input').forEach(node => { node.disabled = value; });
  $('manual-form').setAttribute('aria-busy', String(value));
}

function friendly(reason) {
  if (reason === 'timeout') return 'timed out — the bureau took too long to respond';
  if (reason === 'connection refused') return 'connection refused — the bureau is unavailable';
  if (reason?.startsWith('customer not found')) return 'customer not found — no usable credit record was found';
  return reason || 'an unexpected error occurred';
}

function renderCustomers(rows) {
  const cards = rows.map(row => ({FirstName: row.firstname, LastName: row.lastname,
    DOB: row.dateofBirth, SSN: row.ssn, tag: row.ssn === 'SYN-EMPTY' ? 'Synthetic · empty rating' : 'Sample customer'}));
  cards.push({FirstName: 'Unknown', LastName: 'Customer', SSN: '999-00-0000', tag: 'No record on file'});
  $('customers').replaceChildren();
  for (const customer of cards) {
    const button = element('button', 'customer-card action');
    button.type = 'button';
    button.dataset.ssn = customer.SSN;
    button.setAttribute('aria-pressed', String(customer.SSN === selectedSsn));
    button.append(element('strong', '', customer.SSN === '999-00-0000' ? 'Unknown customer' : `${customer.FirstName} ${customer.LastName}`),
      element('span', 'ssn', customer.SSN), element('span', 'card-tag', customer.tag), element('span', 'arrow', '↗'));
    button.addEventListener('click', () => {
      const {tag, ...input} = customer;
      runCheck(input, `${customer.FirstName} ${customer.LastName}`);
    });
    button.disabled = busy;
    $('customers').append(button);
  }
}

function renderConnections(states) {
  $('connections').replaceChildren();
  for (const bureau of ['EQUIFAX', 'EXPERIAN']) {
    const state = states[bureau];
    const name = bureau === 'EQUIFAX' ? 'Equifax' : 'Experian';
    const section = element('div', 'bureau-control');
    const heading = element('div', 'bureau-label');
    heading.append(element('strong', '', name), element('span', `pill ${state.mode.toLowerCase()}`, modeLabel[state.mode]));
    const buttons = element('div', 'segmented');
    buttons.setAttribute('role', 'group');
    buttons.setAttribute('aria-label', `${name} connection`);
    for (const mode of modes) {
      const button = element('button', 'action', modeLabel[mode]);
      button.type = 'button';
      button.disabled = busy;
      button.setAttribute('aria-pressed', String(mode === state.mode));
      button.dataset.bureau = bureau;
      button.dataset.mode = mode;
      button.addEventListener('click', () => control(async () => {
        await request(`/api/demo/bureaus/${bureau}`, {mode});
        renderConnections((await request('/api/demo/bureaus')).data);
      }));
      buttons.append(button);
    }
    section.append(heading, buttons, element('p', 'connection-help', modeHelp[state.mode]));
    $('connections').append(section);
  }
}

function renderResult(result, name) {
  $('http-status').textContent = `HTTP ${result.status} · ${result.status === 200 ? 'Success' : 'Not completed'}`;
  $('http-status').className = `pill ${result.status === 200 ? 'success' : 'error'}`;
  $('raw-detail').hidden = false;
  $('raw-response').textContent = JSON.stringify(result.data, null, 2);
  const success = result.status === 200;
  $('result-message').className = `result-message ${success ? 'success' : 'failure'}`;
  $('result-message').textContent = success ? `Check complete for ${name}. Both bureaus responded.` :
    `Check not completed. ${(result.data?.failures || []).map(f => `${f.bureau}: ${friendly(f.reason)}.`).join(' ') || result.data?.error || 'Please try again.'} No combined credit result is available.`;
  $('scores').replaceChildren();
  $('scores').hidden = !success;
  if (!success) return;
  for (const bureau of ['Equifax', 'Experian']) {
    const data = result.data[`${bureau}Response`] || {};
    const card = element('div', 'score-card');
    card.append(element('h3', '', bureau), element('span', 'score', data.FICOScore ?? '—'),
      element('span', 'rating', data.Rating ?? 'Rating not supplied'),
      element('span', 'inquiries', data.NoOfInquiries === undefined ? 'Inquiries not supplied' : `${data.NoOfInquiries} inquiries`));
    $('scores').append(card);
  }
}

function renderTrace(trace) {
  $('calls').replaceChildren();
  $('trace-caption').textContent = `Request ${trace.id} · ${new Date(trace.startedAt).toLocaleString()}`;
  for (const call of trace.calls) {
    const card = element('article', 'call-card');
    const heading = element('div', 'call-heading');
    const status = element('div');
    status.append(element('span', `pill ${call.error ? 'error' : 'online'}`, call.status === null ? 'No HTTP response' : `HTTP ${call.status}`),
      element('span', 'quiet', `${call.latencyMs} ms`));
    heading.append(element('h3', '', call.bureau), status);
    card.append(heading, element('code', 'call-url', `POST ${call.url}`),
      element('pre', '', JSON.stringify(call.requestPayload, null, 2)));
    if (call.error) card.append(element('p', 'call-error', friendly(call.error)));
    $('calls').append(card);
  }
}

function renderRecords(before, after) {
  const table = element('table');
  const thead = element('thead');
  const header = element('tr');
  ['Customer', 'Social Security no.', 'Date of birth', 'Score', 'Rating', 'Inquiries · before → after'].forEach(label => header.append(element('th', '', label)));
  header.querySelectorAll('th').forEach(th => { th.scope = 'col'; });
  thead.append(header);
  const tbody = element('tbody');
  after.forEach((row, index) => {
    const previous = before.find(old => old.ssn === row.ssn) || before[index] || {};
    const tr = element('tr');
    tr.append(element('td', '', `${row.firstname} ${row.lastname}`), element('td', '', row.ssn));
    for (const field of ['dateofBirth', 'ficoscore', 'rating', 'numofpulls']) {
      const oldValue = previous[field] ?? '—';
      const newValue = row[field] ?? '—';
      const changed = oldValue !== newValue;
      const cell = element('td', changed ? 'changed' : '');
      if (changed) {
        cell.append(element('span', 'old-value', oldValue), element('span', '', '→ '), element('span', 'new-value', newValue));
        cell.setAttribute('aria-label', `${field} changed from ${oldValue} to ${newValue}`);
      } else {
        cell.textContent = newValue === '' ? '(empty)' : (field === 'numofpulls' ? `${oldValue} → ${newValue}` : newValue);
      }
      tr.append(cell);
    }
    tbody.append(tr);
  });
  table.append(thead, tbody);
  $('record-comparison').replaceChildren(table);
}

async function runCheck(input, name) {
  if (busy) return;
  setBusy(true);
  notice();
  selectedSsn = input.SSN;
  document.querySelectorAll('.customer-card').forEach(card => card.setAttribute('aria-pressed', String(card.dataset.ssn === selectedSsn)));
  $('http-status').textContent = 'Checking both bureaus…';
  $('http-status').className = 'pill neutral';
  $('result-message').className = 'result-message';
  $('result-message').textContent = `Checking ${name}…`;
  $('scores').hidden = true;
  $('raw-detail').hidden = true;
  $('calls').replaceChildren();
  $('trace-caption').textContent = 'Waiting for this request to finish…';
  $('record-comparison').replaceChildren(element('p', 'quiet empty-records', 'Taking record snapshots…'));
  let before = null;
  try {
    try { before = (await request('/api/demo/rows')).data; }
    catch (error) { notice(`Before snapshot unavailable. ${error.message}`); }
    const result = await request('/creditdetails', input, true);
    renderResult(result, name);
    const followups = await Promise.allSettled([
      result.traceId ? request(`/api/traces/${encodeURIComponent(result.traceId)}`) : Promise.reject(new Error('No request trace was returned.')),
      request('/api/demo/rows')
    ]);
    if (followups[0].status === 'fulfilled') renderTrace(followups[0].value.data);
    else $('trace-caption').textContent = `Trace unavailable: ${followups[0].reason.message}`;
    if (before && followups[1].status === 'fulfilled') renderRecords(before, followups[1].value.data);
    else $('record-comparison').replaceChildren(element('p', 'quiet empty-records', 'Record comparison unavailable. Check the local demo connections and try again.'));
  } catch (error) {
    notice(error.message);
    $('http-status').textContent = 'Service unavailable';
    $('http-status').className = 'pill error';
    $('result-message').className = 'result-message failure';
    $('result-message').textContent = 'The credit app could not complete the request. Please try again.';
    $('trace-caption').textContent = 'No trace available for this attempt.';
    $('record-comparison').replaceChildren(element('p', 'quiet empty-records', 'No comparison available for this attempt.'));
  } finally { setBusy(false); }
}

async function control(action) {
  if (busy) return;
  setBusy(true);
  notice();
  try { await action(); }
  catch (error) { notice(error.message); }
  finally { setBusy(false); }
}

async function refresh() {
  const [rows, states] = await Promise.allSettled([request('/api/demo/rows'), request('/api/demo/bureaus')]);
  if (rows.status === 'fulfilled') renderCustomers(rows.value.data);
  else {
    $('customers').replaceChildren(element('p', 'quiet', 'Sample customers unavailable. Start the local stand-ins, then choose Refresh.'));
    notice(rows.reason.message);
  }
  if (states.status === 'fulfilled') renderConnections(states.value.data);
  else {
    $('connections').replaceChildren(element('p', 'quiet', 'Connection status unavailable.'));
    notice(states.reason.message);
  }
}

$('manual-form').addEventListener('submit', event => {
  event.preventDefault();
  const input = Object.fromEntries([...new FormData(event.target)].filter(([, value]) => value !== ''));
  runCheck(input, [input.FirstName, input.LastName].filter(Boolean).join(' ') || 'the entered details');
});
$('refresh').addEventListener('click', () => control(refresh));
$('reset').addEventListener('click', () => control(async () => {
  await request('/api/demo/reset', {});
  selectedSsn = null;
  await refresh();
  $('http-status').textContent = 'Demo reset';
  $('http-status').className = 'pill neutral';
  $('result-message').className = 'result-message';
  $('result-message').textContent = 'All demo records restored. Both bureaus are online. Choose a customer to start again.';
  $('scores').hidden = true;
  $('raw-detail').hidden = true;
  $('calls').replaceChildren();
  $('trace-caption').textContent = 'The actual outbound requests will appear here after a check.';
  $('record-comparison').replaceChildren(element('p', 'quiet empty-records', 'Run a check to compare the records.'));
}));
control(refresh);
