'use strict';

// ---- token & api ---------------------------------------------------------
const TOKEN_KEY = 'dougle-gui-token';
(function captureToken() {
  const match = location.hash.match(/token=([A-Za-z0-9_-]+)/);
  if (match) {
    try { sessionStorage.setItem(TOKEN_KEY, match[1]); } catch (_) { /* private mode */ }
    window.__dougleToken = match[1];
    history.replaceState(null, '', location.pathname + '#/');
  }
})();
function token() {
  if (window.__dougleToken) return window.__dougleToken;
  try { return sessionStorage.getItem(TOKEN_KEY) || ''; } catch (_) { return ''; }
}

const ERRORS = {
  invalid_token: '이 GUI 는 접속 토큰을 요구합니다. 터미널에 출력된 주소(#token=…)로 다시 열어 주세요.',
  json_required: '요청 형식이 올바르지 않습니다.',
  another_run_active: '다른 작업이 실행 중입니다. 끝난 뒤 다시 시도해 주세요.',
  claude_not_found: 'Claude CLI 를 찾지 못했습니다. dougle-gui 를 --claude-bin 옵션과 함께 실행해 주세요.',
  claude_start_failed: 'Claude CLI 를 시작하지 못했습니다.',
  empty_input: '내용을 입력해 주세요.',
  empty_repo: '저장소 주소를 입력해 주세요.',
  empty_scope: '공유할 지식 패키지를 입력해 주세요.',
  unsupported_repository: 'GitHub 저장소 주소(https://github.com/조직/저장소)를 입력해 주세요.',
  repository_not_accessible: '저장소를 찾을 수 없거나 접근 권한이 없습니다. 주소와 gh 로그인 계정(gh auth status)을 확인해 주세요.',
  gh_not_found: 'GitHub CLI(gh)를 찾지 못했습니다. gh 를 설치하고 로그인해 주세요.',
  gh_not_logged_in: 'gh 로그인이 필요합니다. 터미널에서 gh auth login 을 실행해 주세요.',
  gh_timeout: 'GitHub 응답이 늦어 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  gh_failed: 'GitHub 저장소를 읽지 못했습니다.',
  input_too_long: '입력이 너무 깁니다.',
  preparation_not_finished: '게시 준비가 아직 끝나지 않았습니다.',
  already_confirmed: '이미 게시를 진행한 준비 결과입니다.',
  not_found: '찾을 수 없습니다.',
};
function message(code) { return ERRORS[code] || `요청을 처리하지 못했습니다 (${code}).`; }

async function api(path, options = {}) {
  const init = { headers: {} };
  if (token()) init.headers['X-Dougle-Token'] = token();
  if (options.body !== undefined) {
    init.method = 'POST';
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(options.body);
  } else if (options.method) {
    init.method = options.method;
  }
  const response = await fetch(path, init);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || String(response.status));
  return data;
}

// ---- tiny DOM helpers ----------------------------------------------------
const view = document.getElementById('view');
function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function h(html) { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; }
function setView(html) { view.innerHTML = html; view.focus({ preventScroll: true }); }
function errorBox(err) { return `<div class="notice bad">${esc(message(err.message || err))}</div>`; }

// Labels and plain-language meanings shared by chips (tooltips) and the legend.
const FRESH = {
  current_at_observation: ['확인됨', 'ok', '원본을 읽고 정리한 시점에 맞았던 내용입니다. 그 뒤 원본이 바뀌었을 수 있어 실시간 정답을 보장하지는 않습니다.'],
  needs_recheck: ['재확인 필요', 'warn', '팀 지식에서 가져왔거나 원본이 바뀐 것으로 보여, 이 PC에서 원본을 다시 확인해야 하는 내용입니다. 관련 질문을 하면 그때 확인합니다.'],
  unavailable: ['원본 접근 불가', 'bad', '원본을 읽을 수 없어 현재 근거로 쓰지 않는 내용입니다. 기록은 남겨 둡니다.'],
  superseded: ['대체됨', '', '정정이나 새로 확인한 내용으로 대체된 이전 내용입니다. 이력으로만 남습니다.'],
  unknown: ['상태 기록 없음', '', '노트에 확인 상태가 적혀 있지 않습니다.'],
};
const PROVENANCE = {
  observed: ['원본 확인', 'ok', '코드, 문서, DB 등 원본을 직접 읽고 확인한 내용입니다.'],
  reported: ['전달받음', 'warn', '사람이 알려 준 내용입니다. 대화, Slack 메시지, 사용자 정정처럼 원본 동작으로 직접 확인하지 않은 진술입니다.'],
  inferred: ['추론', '', '여러 단서를 근거로 해석한 내용입니다. 원본에 그대로 적혀 있지는 않습니다.'],
  unknown: ['근거 종류 없음', '', '노트에 근거 종류가 적혀 있지 않습니다.'],
};
const STATE = {
  read: ['읽음', 'ok', '요청한 범위를 모두 읽었습니다.'],
  partial: ['일부 읽음', 'warn', '요청한 범위 중 일부만 읽었습니다. 읽지 않은 부분은 소스 상세에 적혀 있습니다.'],
  pending_access: ['접근 대기', 'bad', '등록은 했지만 로그인, 권한, 연결 도구가 없어 아직 읽지 못했습니다.'],
  unavailable: ['접근 불가', 'bad', '원본이 삭제됐거나 더 이상 읽을 수 없습니다.'],
  disconnected: ['연결 해제', '', '사용자가 연결을 끊었습니다. 이 소스에 기댄 내용은 현재 근거로 쓰지 않습니다.'],
};
const MARKS = {
  conflict: ['충돌, 미해결', 'bad', '내용에 서로 다른 근거나 미해결 질문이 있다는 표시가 적혀 있습니다. 본문에서 무엇이 충돌하는지 확인해 주세요. 글자로 찾아 표시하므로 이미 해결된 충돌도 걸릴 수 있습니다.'],
  local: ['내 PC에서 연결', 'ok', '이 PC에서 원본을 직접 연결해 만든 노트입니다. 공유하기로 올리기 전에는 내 PC에만 있습니다.'],
  imported: ['팀 지식에서 가져옴', 'accent', '팀 지식 저장소에서 가져온 노트입니다. 원래 작성자가 확인한 시각과 근거를 그대로 보존합니다.'],
};
function chip([label, cls, tip], suffix = '') {
  return `<span class="chip ${cls}" title="${esc(tip)}">${esc(label)}${suffix}</span>`;
}
function freshChip(value, count) {
  return chip(FRESH[value] || [value, '', ''], count !== undefined ? ' ' + count : '');
}
function provenanceChip(value) {
  const kinds = String(value || 'unknown').split(/\s*\/\s*/);
  return kinds.map(k => chip(PROVENANCE[k] || [k, '', ''])).join('');
}
function stateChip(value) { return chip(STATE[value] || [value, '', '']); }
function legendRows(table) {
  return Object.values(table).map(([label, cls, tip]) => `<tr><td>${chip([label, cls, tip])}</td><td>${esc(tip)}</td></tr>`).join('');
}
function legend(kind) {
  const parts = kind === 'source'
    ? [['소스 상태', STATE], ['출처', { local: MARKS.local, imported: MARKS.imported }]]
    : [['확인 상태', FRESH], ['근거 종류', PROVENANCE], ['출처', { local: MARKS.local, imported: MARKS.imported }], ['표시', { conflict: MARKS.conflict }]];
  const intro = kind === 'source'
    ? '소스는 지식의 근거가 되는 원본(코드 저장소, 문서, DB, Slack 채널 등)의 기록입니다. 상태는 그 원본을 얼마나 읽었는지를 뜻합니다.'
    : '토픽은 업무 개념별 노트이고, claim 은 토픽 안의 개별 사실입니다. claim 마다 확인 상태와 근거 종류가 붙고, 토픽 카드의 숫자는 그 토픽에 속한 claim 수입니다.';
  return `<details class="legend panel"><summary>상태 안내</summary><p class="hint">${intro}</p>
    ${parts.map(([title, table]) => `<h3>${title}</h3><table class="list">${legendRows(table)}</table>`).join('')}</details>`;
}

// ---- markdown (safe subset) ---------------------------------------------
function linkTarget(href) {
  if (/^https?:\/\//i.test(href)) return { href, external: true };
  const clean = href.split('#')[0];
  let m = clean.match(/(?:^|\/)sources\/([A-Za-z0-9._-]+)\.md$/);
  if (m) return { href: `#/source/${encodeURIComponent(m[1])}` };
  m = clean.match(/(?:^|\/)(?:knowledge|topics)\/([A-Za-z0-9._-]+)\.md$/) || clean.match(/^([A-Za-z0-9._-]+)\.md$/);
  if (m) return { href: `#/topic/${encodeURIComponent(m[1])}` };
  return null;
}
function inline(text) {
  const codes = [];
  let out = esc(text).replace(/`([^`]+)`/g, (_, c) => { codes.push(c); return `\u0000${codes.length - 1}\u0000`; });
  out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (all, label, href) => {
    const target = linkTarget(href.replace(/&amp;/g, '&'));
    if (!target) return label;
    return target.external
      ? `<a href="${esc(target.href)}" target="_blank" rel="noopener noreferrer">${label}</a>`
      : `<a href="${target.href}">${label}</a>`;
  });
  out = out.replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g, (all, pre, url) => `${pre}<a href="${url}" target="_blank" rel="noopener noreferrer">${url}</a>`);
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  return out.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${codes[Number(i)]}</code>`);
}
function markdown(src) {
  const lines = String(src || '').replace(/\r\n/g, '\n').split('\n');
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (/^```/.test(line)) {
      const body = [];
      i++;
      while (i < lines.length && !/^```/.test(lines[i])) body.push(lines[i++]);
      i++;
      out.push(`<pre><code>${esc(body.join('\n'))}</code></pre>`);
      continue;
    }
    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading) { const n = Math.min(heading[1].length + 1, 4); out.push(`<h${n}>${inline(heading[2])}</h${n}>`); i++; continue; }
    if (/^\s*\|.*\|\s*$/.test(line)) {
      const rows = [];
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) rows.push(lines[i++]);
      const cells = r => r.trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());
      const body = rows.filter(r => !/^\s*\|[\s:|-]+\|\s*$/.test(r));
      const [head, ...rest] = body;
      out.push('<table><thead><tr>' + cells(head).map(c => `<th>${inline(c)}</th>`).join('') + '</tr></thead><tbody>' +
        rest.map(r => '<tr>' + cells(r).map(c => `<td>${inline(c)}</td>`).join('') + '</tr>').join('') + '</tbody></table>');
      continue;
    }
    if (/^\s*([-*]|\d+\.)\s+/.test(line)) {
      const ordered = /^\s*\d+\./.test(line);
      const items = [];
      while (i < lines.length && /^\s*([-*]|\d+\.)\s+/.test(lines[i])) {
        let item = lines[i++].replace(/^\s*([-*]|\d+\.)\s+/, '');
        while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*]|\d+\.)\s+/.test(lines[i])) item += ' ' + lines[i++].trim();
        items.push(`<li>${inline(item)}</li>`);
      }
      out.push(ordered ? `<ol>${items.join('')}</ol>` : `<ul>${items.join('')}</ul>`);
      continue;
    }
    if (/^>\s?/.test(line)) {
      const quote = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) quote.push(lines[i++].replace(/^>\s?/, ''));
      out.push(`<blockquote>${inline(quote.join(' '))}</blockquote>`);
      continue;
    }
    if (!line.trim()) { i++; continue; }
    const para = [];
    while (i < lines.length && lines[i].trim() && !/^(#{1,6}\s|```|\s*\||\s*([-*]|\d+\.)\s|>)/.test(lines[i])) para.push(lines[i++]);
    if (!para.length) para.push(lines[i++]);
    out.push(`<p>${para.map(inline).join('<br>')}</p>`);
  }
  return `<div class="md">${out.join('')}</div>`;
}

// ---- run rendering (shared by ask / connect / publish / runs) ------------
const KIND_LABEL = { ask: '질문', connect: '연결', fetch: '가져오기', publish: '공유' };
const STATUS_LABEL = { running: '실행 중', done: '완료', failed: '실패', cancelled: '취소됨' };
let activeSources = [];
function closeStreams() { activeSources.forEach(s => s.close()); activeSources = []; }

function renderRun(container, runId, { onFinish, showPrompt = true } = {}) {
  const box = h(`<div class="run"><div class="head"><div class="title"></div><div class="row"><span class="state"></span><button class="danger cancel" hidden>취소</button></div></div>
    <div class="progress"></div>
    <details class="log" open><summary>진행 과정</summary><div class="steps"></div></details><div class="approvals"></div><div class="denials"></div><div class="answer"></div></div>`);
  container.appendChild(box);
  const steps = box.querySelector('.steps');
  const answer = box.querySelector('.answer');
  const state = box.querySelector('.state');
  const cancel = box.querySelector('.cancel');
  const approvals = box.querySelector('.approvals');
  const cards = {};
  function approvalCard(event) {
    const card = h(`<div class="approval"><div><strong>권한이 필요한 작업입니다.</strong> 허용하면 이어서 진행하고, 거부하면 이 명령은 건너뜁니다.</div>
      <div class="row" style="margin:6px 0"><code>${esc(event.tool)}</code></div><pre>${esc(event.detail)}</pre>
      <div class="row actions"><button class="primary" data-d="allow">허용</button><button data-d="allow_always">같은 요청은 계속 허용</button><button class="danger" data-d="deny">거부</button></div></div>`);
    card.querySelectorAll('[data-d]').forEach(button => button.addEventListener('click', async () => {
      card.querySelectorAll('[data-d]').forEach(b => { b.disabled = true; });
      try { await api(`/api/runs/${runId}/approvals/${event.id}`, { body: { decision: button.dataset.d } }); }
      catch (e) { card.insertAdjacentHTML('beforeend', errorBox(e)); card.querySelectorAll('[data-d]').forEach(b => { b.disabled = false; }); }
    }));
    card.dataset.tool = event.tool;
    card.dataset.detail = event.detail;
    approvals.appendChild(card);
    cards[event.id] = card;
  }
  // A decided card is replaced by one line in the step log.
  function approvalDone(event) {
    const card = cards[event.id];
    const tool = card ? card.dataset.tool : '';
    const detail = card ? card.dataset.detail : '';
    if (card) card.remove();
    delete cards[event.id];
    const result = event.reason === 'timeout' ? '거부 (10분 동안 응답 없음)'
      : event.reason === 'run_ended' ? '거부 (작업 종료)'
      : { allow: '허용', allow_always: '허용 (같은 요청 계속 허용)', deny: '거부' }[event.decision] || event.decision;
    steps.appendChild(h(`<div class="${event.decision === 'deny' ? 'skip' : ''}" title="${esc(detail)}">- 승인: ${esc(tool)} ${esc(detail.slice(0, 120))} → ${esc(result)}</div>`));
    steps.scrollTop = steps.scrollHeight;
  }
  let texts = [];
  cancel.addEventListener('click', async () => { cancel.disabled = true; try { await api(`/api/runs/${runId}/cancel`, { body: {} }); } catch (e) { answer.insertAdjacentHTML('beforeend', errorBox(e)); } });

  // Progress line: running (elapsed, last activity, waiting for approval) or finished (duration).
  const progress = box.querySelector('.progress');
  const timing = { created: Date.now() / 1000, finished: null, last: Date.now() / 1000, status: 'running' };
  let ticker = null;
  function duration(seconds) {
    const s = Math.max(0, Math.round(seconds));
    return s < 60 ? `${s}초` : `${Math.floor(s / 60)}분 ${s % 60}초`;
  }
  function drawProgress() {
    const now = Date.now() / 1000;
    const waiting = Object.keys(cards).length > 0;
    if (timing.status === 'running') {
      const parts = [`${duration(now - timing.created)} 경과`, `마지막 활동 ${duration(now - timing.last)} 전`];
      progress.className = 'progress running';
      progress.innerHTML = waiting ? `<strong>승인을 기다리는 중입니다.</strong> 아래 카드에서 허용이나 거부를 눌러 주세요. (${parts.join(', ')})`
        : `<strong>진행 중입니다.</strong> ${parts.join(', ')}`;
    } else {
      const took = timing.finished ? `, ${duration(timing.finished - timing.created)} 걸림` : '';
      const label = { done: '완료했습니다', failed: '실패했습니다', cancelled: '취소했습니다' }[timing.status] || timing.status;
      progress.className = `progress ${timing.status}`;
      progress.innerHTML = `<strong>${esc(label)}</strong>${took}`;
    }
  }
  function setStatus(status) {
    timing.status = status;
    state.innerHTML = status === 'running' ? '<span class="spinner"></span> 실행 중' : esc(STATUS_LABEL[status] || status);
    cancel.hidden = status !== 'running';
    if (status === 'running' && !ticker) ticker = setInterval(() => { if (!box.isConnected) { clearInterval(ticker); return; } drawProgress(); }, 1000);
    if (status !== 'running' && ticker) { clearInterval(ticker); ticker = null; }
    drawProgress();
  }
  api(`/api/runs/${runId}`).then(run => {
    box.querySelector('.title').innerHTML = showPrompt ? `<strong>${esc(KIND_LABEL[run.kind] || run.kind)}${run.phase === 'prepare' ? ' 준비' : run.phase === 'confirm' ? ' 게시' : ''}</strong>: ${esc(run.title)}` : '';
    timing.created = run.created;
    timing.finished = run.finished;
    timing.last = run.last_activity || run.created;
    setStatus(run.status);
  }).catch(() => {});

  const source = new EventSource(`/api/runs/${runId}/events` + (token() ? `?token=${encodeURIComponent(token())}` : ''));
  activeSources.push(source);
  source.onmessage = ev => {
    const event = JSON.parse(ev.data);
    timing.last = Date.now() / 1000;
    if (event.kind === 'tool') steps.appendChild(h(`<div>- ${esc(event.name)} ${esc(event.detail || '')}</div>`));
    else if (event.kind === 'tool_error') {
      const skipped = /permission|denied|can't be checked|requires approval|approve only/i.test(event.text);
      steps.appendChild(skipped
        ? h(`<div class="skip" title="${esc(event.text)}">- 권한 밖이라 건너뜀 (다른 방법으로 다시 시도합니다)</div>`)
        : h(`<div class="err">- 도구 오류: ${esc(event.text)}</div>`));
    }
    else if (event.kind === 'stderr') steps.appendChild(h(`<div class="err">- ${esc(event.text)}</div>`));
    else if (event.kind === 'approval') { approvalCard(event); drawProgress(); refreshActive(); }
    else if (event.kind === 'approval_done') { approvalDone(event); drawProgress(); refreshActive(); }
    else if (event.kind === 'approval_auto') steps.appendChild(h(`<div class="skip">- 자동 허용(이전에 계속 허용함): ${esc(event.tool)} ${esc(event.detail.slice(0, 160))}</div>`));
    else if (event.kind === 'text') { texts.push(event.text); answer.innerHTML = markdown(texts[texts.length - 1]); }
    else if (event.kind === 'result') {
      answer.innerHTML = markdown(event.text || texts[texts.length - 1] || '');
      const meta = [];
      if (event.turns) meta.push(`${event.turns}턴`);
      if (typeof event.cost === 'number') meta.push(`약 $${event.cost.toFixed(3)}`);
      if (meta.length) answer.insertAdjacentHTML('beforeend', `<p class="hint">${meta.join(', ')}</p>`);
      if (event.denials && event.denials.length) {
        box.querySelector('.denials').innerHTML = `<div class="notice warn">실행하지 못한 도구가 있습니다. 승인 카드에서 거부했거나, 공유 준비 단계라 원격에 쓸 수 있는 명령이 막혔거나, 허용 목록 밖이었던 경우입니다. 자주 쓰는 도구라면 <code>dougle-gui --allow-tool &lt;규칙&gt;</code> 으로 미리 허용할 수 있습니다.<ul>${event.denials.map(d => `<li><code>${esc(d.tool)}</code> ${esc(d.detail)}</li>`).join('')}</ul></div>`;
      }
    } else if (event.kind === 'exit') {
      if (event.finished) timing.finished = event.finished;
      setStatus(event.status);
      if (document.hidden) document.title = `(${STATUS_LABEL[event.status] || event.status}) Dougle`;
      box.querySelector('.log').open = false;
      if (event.status !== 'done' && !answer.textContent.trim()) answer.innerHTML = `<div class="notice bad">작업이 ${esc(STATUS_LABEL[event.status] || event.status)} 상태로 끝났습니다. 진행 과정을 확인해 주세요.</div>`;
    }
    steps.scrollTop = steps.scrollHeight;
  };
  source.addEventListener('end', () => {
    source.close();
    refreshActive();
    api(`/api/runs/${runId}`).then(run => onFinish && onFinish(run)).catch(() => {});
  });
  return box;
}

async function refreshActive() {
  const el = document.getElementById('active-run');
  try {
    const runs = await api('/api/runs');
    const running = runs.find(r => r.status === 'running');
    const waiting = running && running.pending_approvals > 0;
    el.innerHTML = running ? `<a href="#/run/${running.id}"><span class="spinner"></span> ${esc(KIND_LABEL[running.kind])} ${waiting ? '<strong>승인 대기</strong>' : '실행 중'}</a>` : '';
    if (waiting) document.title = '(승인 대기) Dougle';
    else if (!document.hidden) document.title = 'Dougle';
  } catch (_) { el.textContent = ''; }
}

async function startRun(kind, input, session) {
  const body = { kind, input };
  if (session) body.session = session;
  const run = await api('/api/runs', { body });
  refreshActive();
  return run;
}

// ---- pages ---------------------------------------------------------------
async function pageKnowledge() {
  setView('<h1>지식</h1><p class="sub">불러오는 중입니다…</p>');
  let overview, topics;
  try { [overview, topics] = await Promise.all([api('/api/overview'), api('/api/topics')]); }
  catch (e) { return setView('<h1>지식</h1>' + errorBox(e)); }
  if (!overview.exists) {
    return setView(`<h1>지식</h1><div class="panel"><p><code>${esc(overview.root)}</code> 에 아직 지식이 없습니다.</p>
      <p>먼저 <a href="#/connect">연결하기</a>로 자료를 연결하거나 <a href="#/fetch">가져오기</a>로 팀 지식을 받아 주세요.</p></div>`);
  }
  const fresh = overview.freshness || {};
  const bindings = (overview.bindings || []).map(b => `<tr>
      <td><a href="https://${esc(b.repository)}" target="_blank" rel="noopener noreferrer">${esc(b.repository.replace(/^github\.com\//, ''))}</a></td>
      <td>${esc(b.project)}</td>
      <td>${commitLink(b.repository, b.merged) || '아직 없음'}</td>
      <td>${b.operation_kind === 'fetch' && b.operation_at ? esc(when(b.operation_at)) : '-'}</td>
      <td>${commitLink(b.repository, b.published) || '없음'}</td>
      <td>${b.pending ? '<span class="chip warn">작업 미완료</span>' : '<span class="chip ok">정상</span>'}</td></tr>`).join('');
  setView(`
    <h1>지식</h1><p class="sub"><code>${esc(overview.root)}</code></p>
    <div class="stats">
      <div class="stat"><b>${overview.topics}</b><span>토픽</span></div>
      <div class="stat"><b>${overview.claims}</b><span>claim</span></div>
      <div class="stat"><b>${overview.sources}</b><span>소스</span></div>
      <div class="stat"><b>${fresh.needs_recheck || 0}</b><span>재확인 필요</span></div>
      <div class="stat"><b>${overview.conflicts}</b><span>충돌, 미해결 표시</span></div>
    </div>
    ${legend('knowledge')}
    ${bindings ? `<div class="panel"><h2 style="margin-top:0">연결된 팀 지식 저장소</h2>
      <table class="list"><thead><tr><th>저장소</th><th>지식 패키지</th><th title="팀 저장소에서 마지막으로 가져온 커밋">가져온 버전</th><th>가져온 일시</th><th title="이 PC에서 마지막으로 올린 커밋">올린 버전</th><th>상태</th></tr></thead><tbody>${bindings}</tbody></table>
      <p class="hint">지식 패키지는 팀 저장소에 공유되는 단위입니다(예: receiving). 저장소 하나에 여러 패키지가 있을 수 있습니다. 팀 지식 저장소에서 가져온 내용은 이 PC에서 원본을 직접 확인한 것이 아니라 모두 '재확인 필요'로 표시됩니다. 관련 질문을 하면 그때 원본을 다시 확인하고 답합니다.</p></div>` : ''}
    <form class="search" id="search"><input type="text" name="q" placeholder="토픽, claim, 소스 검색 (예: 회송 메일)" autocomplete="off"><button class="primary">검색</button></form>
    <div id="results"></div>
    <h2>토픽 ${topics.length}개</h2>
    <div class="grid">${topics.map(t => `
      <a class="card" href="#/topic/${encodeURIComponent(t.key)}">
        <h3>${esc(t.title)}</h3><p>${esc(t.about)}</p>
        <div class="chips"><span class="chip">claim ${t.claims}</span>${Object.entries(t.freshness).map(([k, v]) => freshChip(k, v)).join('')}${chip(t.imported ? MARKS.imported : MARKS.local)}${t.conflicts ? chip(MARKS.conflict, ' ' + t.conflicts) : ''}${t.collection ? `<span class="chip">${esc(t.collection)}</span>` : ''}</div>
      </a>`).join('')}</div>`);
  const form = document.getElementById('search');
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    const q = form.q.value.trim();
    const results = document.getElementById('results');
    if (!q) { results.innerHTML = ''; return; }
    results.innerHTML = '<p class="sub">검색 중입니다…</p>';
    try {
      const hits = await api('/api/search?q=' + encodeURIComponent(q));
      results.innerHTML = `<div class="panel"><div class="row" style="justify-content:space-between"><strong>검색 결과 ${hits.length}건</strong><a href="#/ask?q=${encodeURIComponent(q)}">이 내용으로 질문하기 →</a></div>${hits.length ? hits.map(hit => {
        const href = hit.type === 'source' ? `#/source/${encodeURIComponent(hit.key)}` : `#/topic/${encodeURIComponent(hit.key)}${hit.claim ? '/' + encodeURIComponent(hit.claim) : ''}`;
        const kind = { topic: '토픽', claim: 'claim', source: '소스' }[hit.type];
        return `<div class="result"><div class="row"><span class="chip">${kind}</span><a href="${href}">${esc(hit.title)}</a>${hit.freshness ? freshChip(hit.freshness) : ''}</div><div class="snip">${esc(hit.snippet)}</div></div>`;
      }).join('') : '<p class="empty">일치하는 지식이 없습니다. 질문하기에서 원본을 찾아보게 할 수 있습니다.</p>'}</div>`;
    } catch (e) { results.innerHTML = errorBox(e); }
  });
}

async function pageTopic(key, claimId) {
  setView('<p class="sub">불러오는 중입니다…</p>');
  let topic;
  try { topic = await api('/api/topics/' + encodeURIComponent(key)); }
  catch (e) { return setView(errorBox(e)); }
  setView(`
    <p class="sub"><a href="#/">← 지식</a></p>
    <h1>${esc(topic.title)}</h1>
    <div class="chips" style="margin-bottom:14px">${chip(topic.imported ? MARKS.imported : MARKS.local)}${Object.entries(topic.freshness).map(([k, v]) => freshChip(k, v)).join('')}</div>
    <div class="panel">${markdown(topic.header)}</div>
    ${legend('knowledge')}
    <div class="row" style="margin:10px 0"><a href="#/ask?q=${encodeURIComponent(topic.title + '에 대해 알려 주세요')}"><button>이 토픽으로 질문하기</button></a></div>
    <div class="panel">${topic.claims.length ? topic.claims.map(c => `
      <section class="claim${c.id === claimId ? ' target' : ''}" id="claim-${esc(c.id)}">
        <h3><span class="cid">${esc(c.id)}</span> ${esc(c.title)}</h3>
        <div class="chips" style="margin-bottom:6px">${provenanceChip(c.provenance)}${freshChip(c.freshness)}${c.conflict ? chip(MARKS.conflict) : ''}</div>
        ${markdown(c.body)}
      </section>`).join('') : markdown(topic.text)}</div>`);
  if (claimId) { const el = document.getElementById('claim-' + claimId); if (el) el.scrollIntoView({ block: 'start' }); }
}

async function pageSources() {
  setView('<h1>소스</h1><p class="sub">불러오는 중입니다…</p>');
  let sources;
  try { sources = await api('/api/sources'); } catch (e) { return setView('<h1>소스</h1>' + errorBox(e)); }
  setView(`<h1>소스</h1><p class="sub">지식의 근거가 되는 원본 기록입니다. 원본 자체는 바뀌지 않습니다.</p>
    ${legend('source')}
    <div class="panel"><table class="list"><thead><tr><th>ID</th><th>대상</th><th>상태</th><th>관찰</th></tr></thead><tbody>
    ${sources.map(s => `<tr><td><a href="#/source/${encodeURIComponent(s.key)}">${esc(s.key)}</a></td><td>${esc(s.title.replace(/^S\d+\s*[—-]\s*/, ''))}</td><td>${stateChip(s.state)}</td><td>${esc(s.observed).slice(0, 40)}</td></tr>`).join('')}
    </tbody></table></div>`);
}

async function pageSource(key) {
  setView('<p class="sub">불러오는 중입니다…</p>');
  let source;
  try { source = await api('/api/sources/' + encodeURIComponent(key)); } catch (e) { return setView(errorBox(e)); }
  setView(`<p class="sub"><a href="#/sources">← 소스</a></p><h1>${esc(source.title)}</h1>
    <div class="chips" style="margin-bottom:14px">${stateChip(source.state)}${chip(source.imported ? MARKS.imported : MARKS.local)}</div>
    <div class="panel">${markdown(source.text.split('\n').slice(1).join('\n'))}</div>
    ${legend('source')}`);
}

// Ask keeps one conversation per tab: follow-ups resume the same Claude session.
const thread = { session: null, items: [] };
async function pageAsk(params) {
  setView(`<h1>질문하기</h1><p class="sub">연결한 지식과 원본을 근거로 답합니다. 이어서 질문하면 같은 대화로 이어집니다.</p>
    <div class="thread" id="thread"></div>
    <form id="ask" class="panel"><textarea name="q" placeholder="예: 회송/폐기 배치는 어떤 조건으로 대상을 뽑나요?"></textarea>
      <div class="row" style="justify-content:space-between;margin-top:8px"><button type="button" id="reset">새 대화</button><button class="primary">질문하기</button></div></form>`);
  const box = document.getElementById('thread');
  const form = document.getElementById('ask');
  const draw = () => {
    box.innerHTML = '';
    for (const item of thread.items) {
      box.appendChild(h(`<div class="msg q">${esc(item.q)}</div>`));
      const wrap = h('<div class="msg"></div>');
      box.appendChild(wrap);
      renderRun(wrap, item.run, { showPrompt: false, onFinish: run => { if (run.session) thread.session = run.session; } });
    }
  };
  draw();
  if (params.get('q')) form.q.value = params.get('q');
  document.getElementById('reset').addEventListener('click', () => { closeStreams(); thread.session = null; thread.items = []; draw(); });
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    const q = form.q.value.trim();
    if (!q) return;
    form.querySelector('.primary').disabled = true;
    try {
      const run = await startRun('ask', q, thread.session);
      thread.items.push({ q, run: run.id });
      form.q.value = '';
      box.appendChild(h(`<div class="msg q">${esc(q)}</div>`));
      const wrap = h('<div class="msg"></div>');
      box.appendChild(wrap);
      renderRun(wrap, run.id, { showPrompt: false, onFinish: done => { if (done.session) thread.session = done.session; form.querySelector('.primary').disabled = false; } });
    } catch (e) {
      form.insertAdjacentHTML('beforeend', errorBox(e));
      form.querySelector('.primary').disabled = false;
    }
  });
}

// Default repository and package from the first sharing binding, if any.
async function sharingDefaults() {
  let bindings = [];
  try { bindings = (await api('/api/overview')).bindings || []; } catch (_) { /* store may be empty */ }
  const b = bindings[0];
  return { bindings, repo: b ? 'https://' + b.repository : '', project: b ? b.project : '' };
}
function sameRepo(a, b) {
  const norm = v => String(v || '').trim().replace(/^https?:\/\//, '').replace(/\.git$/, '').replace(/\/+$/, '').toLowerCase();
  return norm(a) && norm(a) === norm(b);
}
function commitLink(repository, sha) {
  if (!sha) return '';
  const short = esc(sha.slice(0, 7));
  return /^github\.com\/[\w.-]+\/[\w.-]+$/.test(repository)
    ? `<a href="https://${esc(repository)}/commit/${esc(sha)}" target="_blank" rel="noopener noreferrer"><code>${short}</code></a>`
    : `<code>${short}</code>`;
}
function when(iso) {
  const d = new Date(iso);
  return isNaN(d) ? '' : d.toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });
}

// How connect, publish and fetch move knowledge; the current step is highlighted.
function flowGuide(active) {
  const edge = (kind, label) => `<div class="edge${kind === active ? ' on' : ''}">${label}</div>`;
  return `<div class="flow" aria-label="지식이 오가는 흐름">
    <div class="node"><b>원본 자료</b><small>코드, 문서, DB 등</small></div>
    ${edge('connect', '연결하기 →')}
    <div class="node"><b>내 지식</b><small>내 PC에만 저장</small></div>
    <div class="edges">${edge('publish', '공유하기 →')}${edge('fetch', '← 가져오기')}</div>
    <div class="node"><b>팀 지식 저장소</b><small>GitHub, 팀원과 함께 씀</small></div>
  </div>`;
}

function bindRunForm(id, kind, build) {
  const form = document.getElementById(id);
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    const out = document.getElementById('out');
    try {
      const run = await startRun(kind, build(form));
      out.innerHTML = '';
      renderRun(out, run.id);
    } catch (e) { out.innerHTML = errorBox(e); }
  });
}

function pageConnect() {
  setView(`<h1>연결하기</h1><p class="sub">원본 자료를 읽어 업무 지식으로 정리합니다. 원본은 읽기만 하고 바꾸지 않습니다.</p>
    ${flowGuide('connect')}
    <div class="notice ok">연결한 지식은 내 PC에만 저장됩니다. 팀과 나누려면 <a href="#/publish">공유하기</a>를 실행해 주세요.</div>
    <form class="panel" id="connect"><label for="connect-target" style="margin-top:0">연결할 자료</label>
      <input type="text" id="connect-target" name="target" placeholder="로컬 경로, GitHub, Confluence 링크 등 (예: ~/work/receiving-service)">
      <p class="hint">Confluence, DB 등은 Claude 에 해당 서비스 연결이 있어야 읽을 수 있습니다.</p>
      <div class="row" style="justify-content:flex-end;margin-top:8px"><button class="primary">연결하기</button></div></form>
    <div id="out"></div>`);
  bindRunForm('connect', 'connect', form => form.target.value.trim());
}

async function pageFetch() {
  const { bindings, repo } = await sharingDefaults();
  setView(`<h1>가져오기</h1><p class="sub">팀이 공유한 지식을 내 지식에 합칩니다. 내 지식과 정정은 덮어쓰지 않고, 가져온 내용은 재확인 필요로 표시됩니다. 원격 저장소에는 쓰지 않습니다.</p>
    ${flowGuide('fetch')}
    <form class="panel" id="lookup">
      <label for="fetch-repo" style="margin-top:0">팀 지식 저장소</label>
      <div class="search" style="margin:0"><input type="text" id="fetch-repo" name="repo" value="${esc(repo)}" placeholder="https://github.com/org/knowledge"><button>가져올 수 있는 지식 패키지 보기</button></div>
      <p class="hint">GitHub 저장소만 지원합니다. 내 PC의 gh 로그인으로 저장소를 읽기만 합니다.</p></form>
    <div id="packages"></div>
    <div id="out"></div>`);
  const lookup = document.getElementById('lookup');
  const list = document.getElementById('packages');
  const show = async () => {
    const target = lookup.repo.value.trim();
    if (!target) { list.innerHTML = errorBox('empty_repo'); return; }
    list.innerHTML = '<p class="sub">저장소를 확인하는 중입니다…</p>';
    let data;
    try { data = await api('/api/remote/packages?repo=' + encodeURIComponent(target)); }
    catch (e) { list.innerHTML = errorBox(e); return; }
    if (!data.packages.length) {
      list.innerHTML = `<div class="panel"><p class="empty">이 저장소에서 가져올 수 있는 지식 패키지를 찾지 못했습니다. 지식 패키지는 <code>knowledge/&lt;패키지 이름&gt;/manifest.md</code> 형태로 올라가 있어야 합니다.</p></div>`;
      return;
    }
    list.innerHTML = `<div class="panel"><h2 style="margin-top:0">가져올 수 있는 지식 패키지 ${data.packages.length}개</h2><p class="hint" style="margin-top:-4px">지식 패키지는 팀 저장소에 공유되는 단위입니다(예: receiving). 저장소 하나에 여러 패키지가 있을 수 있습니다.</p>${data.packages.map((p, i) => {
      const linked = bindings.find(b => sameRepo(b.repository, data.repository) && b.project === p.project);
      return `<div class="result"><div class="row" style="justify-content:space-between"><div>
          <strong>${esc(p.project)}</strong> <span class="chip">토픽 ${p.topics}</span>${p.exported ? ` <span class="chip">공유일 ${esc(p.exported)}</span>` : ''}${p.branch !== data.default_branch ? ` <span class="chip">${esc(p.branch)} 브랜치</span>` : ''}${linked ? ` <span class="chip accent" title="이전에 가져온 적이 있습니다. 다시 가져오면 바뀐 부분만 합칩니다.">가져온 적 있음 (${esc((linked.merged || '').slice(0, 7))})</span>` : ''}${p.format && p.format !== 'dougle-knowledge/v1' ? ` <span class="chip bad">지원하지 않는 형식 ${esc(p.format)}</span>` : ''}
        </div><button class="primary" data-pick="${i}">${linked ? '다시 가져오기' : '가져오기'}</button></div>
        <div class="snip">${esc(p.scope || p.title)}</div></div>`;
    }).join('')}<p class="hint">다시 가져오면 내 PC에서 바뀐 내용과 정정은 유지하고, 팀 저장소에서 바뀐 부분만 합칩니다.</p></div>`;
    list.querySelectorAll('[data-pick]').forEach(button => button.addEventListener('click', async () => {
      const p = data.packages[Number(button.dataset.pick)];
      const branch = p.branch !== data.default_branch ? ` (${p.branch} 브랜치)` : '';
      const out = document.getElementById('out');
      try {
        const run = await startRun('fetch', `https://${data.repository}${branch} 의 ${p.project} 지식 패키지(${p.path})를 가져와 합쳐 주세요`);
        out.innerHTML = '';
        renderRun(out, run.id);
        out.scrollIntoView({ behavior: 'smooth' });
      } catch (e) { out.innerHTML = errorBox(e); }
    }));
  };
  lookup.addEventListener('submit', ev => { ev.preventDefault(); show(); });
  if (repo) show();
}

async function pagePublish() {
  const { bindings, repo, project } = await sharingDefaults();
  let topics = [];
  try { topics = await api('/api/topics'); } catch (_) { /* store may be empty */ }
  const local = topics.filter(t => !t.imported);
  setView(`<h1>공유하기</h1><p class="sub">내 지식 중 일부를 팀 저장소에 올립니다. 먼저 준비 단계에서 올라갈 내용과 민감정보 검사 결과를 확인하고, 확인 버튼을 눌러야 실제로 올립니다.</p>
    ${flowGuide('publish')}
    <form class="panel" id="publish">
      <label for="publish-repo" style="margin-top:0">대상 저장소</label>
      <input type="text" id="publish-repo" name="repo" value="${esc(repo)}" placeholder="https://github.com/org/knowledge">
      <label for="publish-scope">공유할 지식 패키지</label>
      <input type="text" id="publish-scope" name="scope" value="${esc(project)}" placeholder="지식 패키지 이름 (예: receiving)">
      <p class="hint">지식 패키지는 팀 저장소에 공유되는 단위입니다(예: receiving). 저장소 하나에 여러 패키지가 있을 수 있습니다. 새 이름을 적으면 새 패키지로 올라가고, 기존 이름을 적으면 그 패키지에 합쳐집니다. 저장소만 적으면 내 지식 전체를 올리지 않습니다. 준비 단계에서는 원격 쓰기(git push 등)를 막고, 읽기와 쓰기 모두 가능한 gh api는 실행 전에 승인 카드로 묻습니다.</p>
      <div id="existing"></div>
      <label>공유할 토픽 (선택)</label>
      ${local.length ? `<p class="hint" style="margin-top:0">내 PC에서 직접 연결한 토픽입니다. 고르면 그 토픽만 올리고, 고르지 않으면 패키지 이름에 해당하는 지식을 올립니다. 이미 공유한 토픽도 여기 보일 수 있으며, 다시 공유하면 바뀐 부분만 올라갑니다.</p>
        <div class="picks">${local.map(t => `<label class="pick"><input type="checkbox" name="topic" value="${esc(t.title)}"> ${esc(t.title)} <span class="chip">claim ${t.claims}</span></label>`).join('')}</div>`
      : '<p class="hint" style="margin-top:0">내 PC에서 직접 연결한 토픽이 없습니다. 지금 있는 지식은 모두 팀 저장소에서 가져온 것입니다. <a href="#/connect">연결하기</a>로 자료를 연결하면 여기 표시됩니다.</p>'}
      <div class="row" style="justify-content:flex-end;margin-top:8px"><button class="primary">게시 준비</button></div></form>
    <div id="out"></div>`);
  const form = document.getElementById('publish');
  const showExisting = () => {
    const match = bindings.find(b => sameRepo(b.repository, form.repo.value) && form.scope.value.trim().split(/\s+/)[0] === b.project);
    document.getElementById('existing').innerHTML = match ? `<div class="notice ok">이 저장소에는 이미 <strong>${esc(match.project)}</strong> 지식 패키지가 있습니다. 덮어쓰지 않고 원격 내용과 합쳐 올립니다.
      <ul><li>팀원이 추가한 내용은 그대로 둡니다.</li><li>같은 항목을 양쪽에서 다르게 바꿨으면 둘 다 남기고 충돌로 표시합니다.</li><li>합친 결과가 원격과 같으면 아무것도 올리지 않습니다.</li></ul></div>` : '';
  };
  form.repo.addEventListener('input', showExisting);
  form.scope.addEventListener('input', showExisting);
  showExisting();
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    const out = document.getElementById('out');
    try {
      const target = form.repo.value.trim();
      const scope = form.scope.value.trim();
      if (!target || !scope) throw new Error(!target ? 'empty_repo' : 'empty_scope');
      const picked = [...form.querySelectorAll('input[name=topic]:checked')].map(i => i.value);
      const request = picked.length
        ? `${target} 의 ${scope} 지식 패키지에 다음 토픽을 공유해 주세요: ${picked.join(', ')}`
        : `${target} 에 ${scope} 지식 패키지를 공유해 주세요`;
      const run = await startRun('publish', request);
      out.innerHTML = '';
      renderRun(out, run.id, { onFinish: done => {
        if (done.status !== 'done') return;
        const confirmBox = h(`<div class="panel"><div class="notice warn">위 준비 결과에서 올라갈 내용, 제외된 자료, 민감정보 검사 결과를 확인해 주세요. 확인을 누르면 같은 세션에서 원격 저장소에 올립니다.</div>
          <div class="row" style="justify-content:flex-end"><button class="primary">이 내용으로 게시</button></div></div>`);
        out.appendChild(confirmBox);
        confirmBox.querySelector('button').addEventListener('click', async () => {
          confirmBox.querySelector('button').disabled = true;
          try { const next = await api(`/api/runs/${done.id}/confirm`, { body: {} }); refreshActive(); renderRun(out, next.id); }
          catch (e) { confirmBox.insertAdjacentHTML('beforeend', errorBox(e)); }
        });
      } });
    } catch (e) { out.innerHTML = errorBox(e); }
  });
}

async function pageBot() {
  setView('<h1>Slack</h1><p class="sub">불러오는 중입니다…</p>');
  let data;
  try { data = await api('/api/bot'); } catch (e) { return setView('<h1>Slack</h1>' + errorBox(e)); }
  const bot = data.bot;
  let botHtml;
  if (!bot.installed) botHtml = '<p>dougle-bot 이 설치되어 있지 않습니다. Claude Code 에서 <code>/dougle:dougle-bot</code> 으로 설치하고 설정할 수 있습니다.</p>';
  else if (bot.error) botHtml = `<div class="notice bad">상태를 읽지 못했습니다 (${esc(bot.error)}).</div>${bot.detail ? `<pre>${esc(bot.detail)}</pre>` : ''}`;
  else if (bot.status) {
    const s = bot.status;
    botHtml = `<div class="chips" style="margin-bottom:8px"><span class="chip ${s.running ? 'ok' : ''}">${s.running ? '실행 중' : '중지됨'}</span>${s.version ? `<span class="chip">v${esc(s.version)}</span>` : ''}${s.configured === false ? '<span class="chip warn">설정 전</span>' : ''}</div>
      <details><summary>전체 상태</summary><pre>${esc(JSON.stringify(s, null, 2))}</pre></details>`;
  } else botHtml = `<pre>${esc(bot.raw || '')}</pre>`;
  const schedules = data.schedules || [];
  setView(`<h1>Slack</h1><p class="sub">상태만 보여줍니다. 봇 시작, 중지와 답변 승인은 Claude Code 대화에서 진행해 주세요.</p>
    <div class="panel"><h2 style="margin-top:0">dougle-bot</h2>${botHtml}</div>
    <div class="panel"><h2 style="margin-top:0">문의 채널 모니터링 (dougle-schedule)</h2>
    ${schedules.length ? `<table class="list"><thead><tr><th>ID</th><th>채널</th><th>상태</th><th>문의</th><th>마지막 성공 확인</th></tr></thead><tbody>${schedules.map(s => `<tr><td>${esc(s.id)}</td><td>${esc(s.channel)}</td><td>${esc(s.status)}</td><td>${Object.entries(s.inquiries).map(([k, v]) => `<span class="chip ${k === 'awaiting_approval' ? 'warn' : ''}">${esc(k)} ${v}</span>`).join(' ')}</td><td>${esc(s.last_success)}</td></tr>`).join('')}</tbody></table>` : '<p class="empty">등록된 모니터링이 없습니다.</p>'}</div>`);
}

async function pageRuns() {
  setView('<h1>작업 기록</h1><p class="sub">불러오는 중입니다…</p>');
  let runs;
  try { runs = await api('/api/runs'); } catch (e) { return setView('<h1>작업 기록</h1>' + errorBox(e)); }
  setView(`<h1>작업 기록</h1><p class="sub">이 GUI 를 켠 뒤 실행한 작업입니다. GUI 를 끄면 목록은 사라지지만 지식 저장 결과는 남습니다.</p>
    <div class="panel">${runs.length ? `<table class="list"><thead><tr><th>시각</th><th>종류</th><th>내용</th><th>상태</th></tr></thead><tbody>${runs.map(r => `<tr><td>${new Date(r.created * 1000).toLocaleTimeString('ko-KR')}</td><td>${esc(KIND_LABEL[r.kind])}${r.phase === 'prepare' ? ' 준비' : r.phase === 'confirm' ? ' 게시' : ''}</td><td><a href="#/run/${r.id}">${esc(r.title)}</a></td><td>${esc(STATUS_LABEL[r.status] || r.status)}</td></tr>`).join('')}</tbody></table>` : '<p class="empty">아직 실행한 작업이 없습니다.</p>'}</div>`);
}

function pageRun(id) {
  setView('<p class="sub"><a href="#/runs">← 작업 기록</a></p><div id="out"></div>');
  renderRun(document.getElementById('out'), id);
}

// ---- router --------------------------------------------------------------
function route() {
  closeStreams();
  const raw = location.hash.replace(/^#/, '') || '/';
  const [path, query] = raw.split('?');
  const params = new URLSearchParams(query || '');
  const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
  const section = { topic: 'knowledge', source: 'sources', run: 'runs' }[parts[0]] || parts[0] || 'knowledge';
  document.querySelectorAll('[data-nav]').forEach(a => a.classList.toggle('on', a.dataset.nav === section));
  switch (parts[0]) {
    case undefined: return pageKnowledge();
    case 'topic': return pageTopic(parts[1], parts[2]);
    case 'sources': return pageSources();
    case 'source': return pageSource(parts[1]);
    case 'ask': return pageAsk(params);
    case 'connect': return pageConnect();
    case 'fetch': return pageFetch();
    case 'publish': return pagePublish();
    case 'bot': return pageBot();
    case 'runs': return pageRuns();
    case 'run': return pageRun(parts[1]);
    default: return setView('<p class="empty">페이지를 찾을 수 없습니다.</p>');
  }
}
window.addEventListener('hashchange', route);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refreshActive(); });
route();
refreshActive();
setInterval(refreshActive, 4000);
