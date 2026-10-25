import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { performance } from 'node:perf_hooks'
import { randomBytes } from 'node:crypto'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const directory = await mkdtemp(resolve(tmpdir(), 'forge-smoke-'))
const portServer = createServer()
portServer.listen(0, '127.0.0.1')
await once(portServer, 'listening')
const port = portServer.address().port
await new Promise(resolveClose => portServer.close(resolveClose))
const desktopResources = process.env.FORGE_SMOKE_DESKTOP_RESOURCES
let base = `http://127.0.0.1:${port}`
let sessionToken = ''
const fetch = (url, options = {}) => {
  const headers = new Headers(options.headers)
  if (desktopResources && String(url).startsWith(`${base}/api/`)) headers.set('X-Forge-Session', sessionToken)
  return globalThis.fetch(url, { ...options, headers })
}
let child
let logs = ''
let configuredModel = 'server-default-fixture'
let configuredCodex = resolve(root, 'scripts/fixtures/fake-codex.sh')
let configuredStreamHome = ''

async function start() {
  logs = ''
  const started = performance.now()
  sessionToken = desktopResources ? randomBytes(32).toString('hex') : ''
  const command = desktopResources ? resolve(desktopResources, 'runtime/bin/java') : 'bash'
  const launcher = desktopResources ? ['-jar', resolve(desktopResources, 'knowledge-forge.jar')] : ['scripts/run.sh']
  child = spawn(command, [...launcher,
    `--forge.ai.codex-command=${configuredCodex}`,
    `--forge.ai.codex-stream-home=${configuredStreamHome}`,
    `--forge.ai.codex-model=${configuredModel}`,
    `--forge.ai.claude-command=${resolve(root, 'scripts/fixtures/fake-claude-stream.sh')}`], {
    cwd: root,
    env: { ...process.env, PORT: String(port), FORGE_DATA_DIR: directory, FORGE_DESKTOP: desktopResources ? 'true' : 'false' },
    stdio: [desktopResources ? 'pipe' : 'ignore', 'pipe', 'pipe'],
  })
  child.on('error', error => { logs += error.message })
  child.stdout.on('data', chunk => { logs += chunk.toString() })
  child.stderr.on('data', chunk => { logs += chunk.toString() })
  if (desktopResources) child.stdin.write(`${sessionToken}\n`)
  for (let attempt = 0; attempt < 200; attempt++) {
    if (child.exitCode !== null) throw new Error(`Server exited: ${logs}`)
    try {
      if (desktopResources) {
        const ready = logs.match(/^FORGE_DESKTOP_READY:(\d+)$/m)
        if (!ready) { await new Promise(resolveWait => setTimeout(resolveWait, 150)); continue }
        base = `http://127.0.0.1:${ready[1]}`
        assert.equal((await globalThis.fetch(`${base}/api/session`)).status, 403)
        assert.equal((await fetch(`${base}/api/desktop/ready`)).status, 200)
        assert.ok(!logs.includes(sessionToken))
      }
      const response = await fetch(`${base}/api/session`, { signal: AbortSignal.timeout(1000) })
      if (response.ok) return performance.now() - started
    } catch {}
    await new Promise(resolveWait => setTimeout(resolveWait, 150))
  }
  throw new Error(`Server startup timeout: ${logs}`)
}

async function stop() {
  if (child && child.exitCode === null) {
    const ended = once(child, 'exit')
    if (desktopResources) child.stdin.end()
    else child.kill('SIGTERM')
    let forced = false
    const timeout = setTimeout(() => { forced = true; child.kill('SIGKILL') }, 10000)
    await ended
    clearTimeout(timeout)
    assert.equal(forced, false, 'Server must shut down gracefully')
  }
}

async function api(path, method = 'GET', body) {
  const headers = { 'X-Forge-Client': '1' }
  if (body && !(body instanceof FormData)) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${base}/api${path}`, {
    method, headers, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(path.endsWith('/sync') ? 180000 : 10000),
  })
  assert.ok(response.ok, `${method} ${path}: ${response.status} ${response.ok ? '' : await response.text()}`)
  return response.status === 204 ? null : response.json()
}

const measure = async action => {
  const started = performance.now()
  await action()
  return performance.now() - started
}
const percentile = (values, quantile) => [...values].sort((left, right) => left - right)[Math.ceil(values.length * quantile) - 1]

async function waitForJob(path, predicate) {
  const deadline = Date.now() + 15000
  while (Date.now() < deadline) {
    const job = await api(path)
    if (predicate(job)) return job
    await new Promise(resolveWait => setTimeout(resolveWait, 100))
  }
  throw new Error(`Job did not reach expected state: ${path}`)
}

try {
  const startupMs = await start()
  const cliStatus = await api('/ai/providers/CODEX/probe', 'POST')
  assert.equal(cliStatus.status, 'AVAILABLE')
  assert.equal(cliStatus.version, '0.0.0-test-fixture')
  assert.equal(cliStatus.requestedModel, configuredModel)
  assert.equal(cliStatus.authentication, 'UNKNOWN')
  assert.equal(cliStatus.remoteConnection, 'NOT_CHECKED')
  const page = await fetch(base)
  assert.equal(page.status, 200)
  assert.match(await page.text(), /<title>Dougle<\/title>/)
  const workspace = await api('/workspaces', 'POST', { name: '가상 온실 연구' })
  const folder = await api(`/workspaces/${workspace.id}/folders`, 'POST', { name: '가상 자료' })
  const bytes = await readFile(resolve(root, 'fixtures/research-notes.md'))
  const form = new FormData()
  form.append('file', new Blob([bytes]), 'research-notes.md')
  form.append('parentId', folder.id)
  const file = await api(`/workspaces/${workspace.id}/files`, 'POST', form)
  const contentPath = `/workspaces/${workspace.id}/entries/${file.id}`
  const preview = await api(`${contentPath}/preview`)
  assert.equal(preview.text, bytes.toString('utf8'))
  assert.deepEqual(Buffer.from(await (await fetch(`${base}/api${contentPath}/content`)).arrayBuffer()), bytes)
  const sourceDirectory = resolve(directory, 'synthetic-project')
  await cp(resolve(root, 'fixtures/synthetic-project'), sourceDirectory, { recursive: true })
  const source = await api(`/workspaces/${workspace.id}/sources`, 'POST', {
    name: '가상 프로젝트', kind: 'LOCAL_FOLDER', location: sourceDirectory, domain: '실험',
  })
  assert.equal(source.pollingEnabled, false)
  let synced
  const sourceSyncMs = await measure(async () => {
    synced = await api(`/workspaces/${workspace.id}/sources/${source.id}/sync`, 'POST')
    assert.equal(synced.status, 'READY', synced.error)
  })
  const searchPath = `/workspaces/${workspace.id}/search?query=measureTemperature&domains=${encodeURIComponent('실험')}`
  const symbolHits = await api(searchPath)
  assert.equal(symbolHits.length, 1)
  assert.equal(symbolHits[0].bodyMatch, true)
  assert.ok(symbolHits[0].startLine >= 1)
  assert.ok(symbolHits[0].endLine >= symbolHits[0].startLine)
  assert.ok(symbolHits[0].snippet.includes('measureTemperature'))
  assert.equal(symbolHits[0].textHash.length, 64)
  const bundleBase = `/workspaces/${workspace.id}/evidence-bundles`
  const originalBundle = await api(bundleBase, 'POST', { question: 'measureTemperature', domains: ['실험'], includeKnowledge: false })
  assert.equal(originalBundle.payload.status, 'READY')
  assert.equal(originalBundle.payload.items[0].entryId, symbolHits[0].entryId)
  assert.equal(originalBundle.payload.items[0].quote, symbolHits[0].snippet)
  assert.equal(originalBundle.digest.length, 64)
  assert.equal((await api(`${bundleBase}/${originalBundle.id}/validation`)).eligible, true)
  const originalAnswerPreview = await api(`${bundleBase}/${originalBundle.id}/answer-preview`, 'POST', { provider: 'CODEX', requestedModel: 'preview-only-model' })
  assert.equal(originalAnswerPreview.bundleDigest, originalBundle.digest)
  assert.equal(originalAnswerPreview.requestedModel, 'preview-only-model')
  assert.ok(originalAnswerPreview.prompt.includes('untrusted data'))
  const approvalPath = `${bundleBase}/${originalBundle.id}/answer-approval`
  assert.equal((await api(approvalPath)).status, 'NONE')
  const answerApproval = await api(approvalPath, 'POST', {
    provider: originalAnswerPreview.provider, requestedModel: originalAnswerPreview.requestedModel,
    digest: originalAnswerPreview.digest, acknowledged: true, expectedApprovalId: null,
  })
  assert.equal(answerApproval.status, 'APPROVED')
  const answerRequest = { requestId: crypto.randomUUID(), approvalId: answerApproval.approval.id, digest: originalAnswerPreview.digest, approved: true }
  const answerSubmitPath = `${bundleBase}/${originalBundle.id}/answer-jobs`
  const answerJob = await api(answerSubmitPath, 'POST', answerRequest)
  const answerJobPath = `/workspaces/${workspace.id}/answer-jobs/${answerJob.id}`
  const completedAnswer = await waitForJob(answerJobPath, job => !['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.status))
  assert.equal(completedAnswer.status, 'SUCCEEDED', completedAnswer.error)
  assert.equal(completedAnswer.answer, '테스트 전용 가상 답변입니다. [R1]')
  const conversationBase = `/workspaces/${workspace.id}/conversations`
  const conversation = await api(conversationBase, 'POST', { title: '가상 관측 보관함' })
  const conversationPath = `${conversationBase}/${conversation.id}`
  const linkedConversation = await api(`${conversationPath}/turns`, 'POST', { jobId: answerJob.id, expectedRevision: 0 })
  assert.equal(linkedConversation.turns[0].jobId, answerJob.id)
  assert.equal(linkedConversation.turns[0].position, 1)
  assert.deepEqual(await api(`${conversationPath}/turns`, 'POST', { jobId: answerJob.id, expectedRevision: 0 }), linkedConversation)
  const contextRequest = { bundleId: originalBundle.id, expectedRevision: 1, jobIds: [answerJob.id], provider: 'CODEX', requestedModel: 'preview-only-model' }
  const contextPreview = await api(`${conversationPath}/preview`, 'POST', contextRequest)
  assert.equal(contextPreview.history[0].jobId, answerJob.id)
  assert.ok(contextPreview.prompt.includes('UNTRUSTED CONTEXT'))
  assert.notEqual(contextPreview.digest, originalAnswerPreview.digest)
  async function checkAnswerEvents() {
    const response = await fetch(`${base}/api${answerJobPath}/events`, { signal: AbortSignal.timeout(10000) })
    assert.equal(response.status, 200)
    assert.ok(response.headers.get('content-type').startsWith('text/event-stream'))
    const events = (await response.text()).trim().split(/\r?\n\r?\n/).map(block => {
      const lines = block.split(/\r?\n/)
      return { name: lines.find(line => line.startsWith('event:')).slice(6).trim(),
        data: JSON.parse(lines.filter(line => line.startsWith('data:')).map(line => line.slice(5)).join('\n')) }
    })
    assert.deepEqual(events.map(event => event.name), ['evidence', 'job', 'done'])
    assert.deepEqual(events[0].data, originalBundle)
    assert.deepEqual(events[1].data, completedAnswer)
    assert.equal(events[2].data.reason, 'terminal')
  }
  await checkAnswerEvents()
  assert.equal((await api(approvalPath)).status, 'CONSUMED')
  assert.deepEqual(await api(answerSubmitPath, 'POST', answerRequest), completedAnswer)
  assert.equal((await api(`/workspaces/${workspace.id}/generation-jobs`)).length, 0)
  const analysis = await api(`/workspaces/${workspace.id}/entries/${symbolHits[0].entryId}/analysis`)
  assert.ok(analysis.symbols.some(symbol => symbol.name === 'measureTemperature' && symbol.line === 4))
  const knowledgeBase = `/workspaces/${workspace.id}/knowledge`
  const draft = await api(knowledgeBase, 'POST', {
    title: '가상 실험 초안', markdown: '검토가 필요한 수동 메모',
    citations: [{ entryId: symbolHits[0].entryId, startLine: 1, endLine: 4 }],
  })
  assert.equal(draft.version.status, 'DRAFT')
  const knowledgePath = `${knowledgeBase}/${draft.card.id}`
  const organizationPath = `/workspaces/${workspace.id}/knowledge-organization`
  const knowledgeFolder = await api(`${organizationPath}/folders`, 'POST', { name: '가상 지식 폴더', parentId: null })
  const knowledgeChild = await api(`${organizationPath}/folders`, 'POST', { name: '가상 하위 폴더', parentId: knowledgeFolder.id })
  assert.equal((await api(`${organizationPath}/cards/${draft.card.id}`, 'PUT', { folderId: knowledgeChild.id, expectedRevision: 0 })).placements[0].revision, 1)
  assert.equal((await api(knowledgePath)).version.number, 1)
  const evidenceLocationPath = `${knowledgePath}/versions/1/evidence/${draft.version.evidence[0].snapshotId}/location`
  const evidenceLocation = await api(evidenceLocationPath)
  assert.equal(evidenceLocation.entry.id, symbolHits[0].entryId)
  assert.equal(evidenceLocation.freshness, 'CURRENT')
  assert.equal(evidenceLocation.ancestors.at(-1).id, evidenceLocation.entry.parentId)
  const serverDefault = await api(`${knowledgePath}/generation/preview`, 'POST', { provider: 'CODEX' })
  assert.equal(serverDefault.requestedModel, configuredModel)
  const transmission = await api(`${knowledgePath}/generation/preview`, 'POST', { provider: 'CODEX', requestedModel: 'synthetic-smoke-model' })
  assert.equal(transmission.requestedModel, 'synthetic-smoke-model')
  assert.notEqual(transmission.digest, serverDefault.digest)
  assert.ok(transmission.prompt.includes('E1'))
  assert.equal(transmission.digest.length, 64)
  for (const [approved, digest, status] of [[false, transmission.digest, 403], [true, 'wrong', 409]]) {
    const rejected = await fetch(`${base}/api${knowledgePath}/generation`, {
      method: 'POST', headers: { 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'CODEX', expectedVersion: 1, approved, digest }),
    })
    assert.equal(rejected.status, status)
  }
  assert.equal((await api(knowledgeBase)).length, 1)
  const jobBase = `/workspaces/${workspace.id}/generation-jobs`
  const generation = { requestId: crypto.randomUUID(), provider: 'CODEX', requestedModel: transmission.requestedModel, expectedVersion: 1, digest: transmission.digest, approved: true }
  const submitted = await api(`${knowledgePath}/generation/jobs`, 'POST', generation)
  assert.equal(submitted.id, generation.requestId)
  assert.equal(submitted.requestedModel, 'synthetic-smoke-model')
  assert.equal((await api(`${knowledgePath}/generation/jobs`, 'POST', generation)).id, submitted.id)
  const completed = await waitForJob(`${jobBase}/${submitted.id}`, job => !['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.status))
  assert.equal(completed.status, 'SUCCEEDED', completed.error)
  assert.equal(completed.requestedModel, 'synthetic-smoke-model')
  assert.equal(completed.cliVersion, '0.0.0-test-fixture')
  assert.equal(completed.cliVersionStatus, 'AVAILABLE')
  assert.ok(completed.cliCheckedAt)
  assert.equal((await api(`${knowledgeBase}/${completed.resultCardId}`)).version.title, 'TEST FIXTURE 초안')
  assert.equal((await api(`${knowledgeBase}/${completed.resultCardId}`)).version.manuallyEdited, false)
  assert.ok((await api(`${knowledgeBase}/${completed.resultCardId}`)).version.markdown.includes('요청 모델: synthetic-smoke-model'))
  assert.ok((await api(`${knowledgeBase}/${completed.resultCardId}`)).version.markdown.includes(transmission.digest))
  assert.equal((await api(knowledgePath)).version.markdown, draft.version.markdown)
  const cancelledRequest = { ...generation, requestId: crypto.randomUUID() }
  const cancellable = await api(`${knowledgePath}/generation/jobs`, 'POST', cancelledRequest)
  await waitForJob(`${jobBase}/${cancellable.id}`, job => job.status === 'RUNNING' && job.cliCheckedAt)
  await api(`${jobBase}/${cancellable.id}/cancel`, 'POST')
  assert.equal((await waitForJob(`${jobBase}/${cancellable.id}`, job => job.status === 'CANCELLED')).resultCardId, null)
  assert.equal((await api(knowledgeBase)).length, 2)
  const snapshotPath = `${knowledgePath}/evidence/${draft.version.evidence[0].snapshotId}/content`
  const publicationPath = `${knowledgeBase}/${completed.resultCardId}`
  const review = await api(`${publicationPath}/review`, 'POST', {
    expectedVersion: 1, reviewer: 'Synthetic smoke reviewer', note: 'Synthetic fixture contract only; not semantic AI validation.',
    claimsSupported: true, uncertaintyRemoved: true,
  })
  assert.equal(review.publication, null)
  assert.equal((await api(`${publicationPath}/publication`, 'POST', { expectedVersion: 1, expectedPublicationRevision: 0 })).publication.version, 1)
  const publishedSearchPath = `/workspaces/${workspace.id}/search?query=TEST&includeKnowledge=true`
  const publicationDeadline = Date.now() + 10000
  let publishedHit
  while (Date.now() < publicationDeadline) {
    publishedHit = (await api(publishedSearchPath)).find(hit => hit.cardId === completed.resultCardId)
    if (publishedHit) break
    await new Promise(resolveWait => setTimeout(resolveWait, 100))
  }
  assert.equal(publishedHit?.kind, 'KNOWLEDGE')
  assert.equal(publishedHit.version, 1)
  assert.equal(publishedHit.freshness, 'CURRENT')
  assert.ok((await api(`${publishedSearchPath}&domains=${encodeURIComponent('실험')}`)).some(hit => hit.cardId === completed.resultCardId))
  const publishedBundle = await api(bundleBase, 'POST', { question: '출력', includeKnowledge: true })
  assert.equal(publishedBundle.payload.status, 'READY')
  assert.equal(publishedBundle.payload.items[0].cardId, completed.resultCardId)
  assert.equal(publishedBundle.payload.items[0].version, 1)
  assert.ok(publishedBundle.payload.items[0].supports.length > 0)
  const publishedAnswerPreview = await api(`${bundleBase}/${publishedBundle.id}/answer-preview`, 'POST', { provider: 'CLAUDE', requestedModel: 'preview-only-model' })
  assert.ok(publishedAnswerPreview.prompt.includes('R1/E1'))
  const decisionsPath = `${jobBase}/${completed.id}/decisions`
  assert.deepEqual(await api(decisionsPath), [])
  const rejectedProposal = await api(decisionsPath, 'POST', {
    expectedRevision: 0, expectedSourceVersion: 1, expectedResultVersion: 1, status: 'REJECTED', note: 'Synthetic rejection',
  })
  assert.equal(rejectedProposal[0].revision, 1)
  const adoptionDraft = await api(knowledgeBase, 'POST', {
    title: 'Synthetic adoption original', markdown: 'Original fixture [E1]',
    citations: [{ entryId: symbolHits[0].entryId, startLine: 1, endLine: 4 }],
  })
  const adoptionPath = `${knowledgeBase}/${adoptionDraft.card.id}`
  await api(`${adoptionPath}/review`, 'POST', {
    expectedVersion: 1, reviewer: 'Synthetic reviewer', note: 'Fixture contract only', claimsSupported: true, uncertaintyRemoved: true,
  })
  await api(`${adoptionPath}/publication`, 'POST', { expectedVersion: 1, expectedPublicationRevision: 0 })
  const adoptionPreview = await api(`${adoptionPath}/generation/preview`, 'POST', { provider: 'CODEX', requestedModel: 'synthetic-smoke-model' })
  const adoptionRequest = await api(`${adoptionPath}/generation/jobs`, 'POST', {
    requestId: crypto.randomUUID(), provider: 'CODEX', requestedModel: adoptionPreview.requestedModel,
    expectedVersion: 1, digest: adoptionPreview.digest, approved: true,
  })
  const adoptionJob = await waitForJob(`${jobBase}/${adoptionRequest.id}`, job => !['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.status))
  assert.equal(adoptionJob.status, 'SUCCEEDED', adoptionJob.error)
  const acceptancePath = `${jobBase}/${adoptionJob.id}/acceptance`
  const acceptedProposal = await api(acceptancePath, 'POST', {
    expectedRevision: 0, expectedSourceVersion: 1, expectedResultVersion: 1, confirmed: true, note: 'Synthetic adoption',
  })
  assert.equal(acceptedProposal.acceptance.appliedVersion, 2)
  assert.equal(acceptedProposal.acceptance.mode, 'FULL')
  assert.equal((await api(`${adoptionPath}/published`)).version.number, 1)
  const manualPreview = await api(`${adoptionPath}/generation/preview`, 'POST', { provider: 'CODEX', requestedModel: 'synthetic-smoke-model' })
  const manualRequest = await api(`${adoptionPath}/generation/jobs`, 'POST', {
    requestId: crypto.randomUUID(), provider: 'CODEX', requestedModel: manualPreview.requestedModel,
    expectedVersion: 2, digest: manualPreview.digest, approved: true,
  })
  const manualJob = await waitForJob(`${jobBase}/${manualRequest.id}`, job => !['QUEUED', 'RUNNING', 'CANCELLING'].includes(job.status))
  assert.equal(manualJob.status, 'SUCCEEDED', manualJob.error)
  await api(`${adoptionPath}/versions`, 'POST', {
    expectedVersion: 2, title: 'Human title', markdown: 'Human paragraph [E1]',
    citations: [{ entryId: file.id, startLine: 1, endLine: 1 }],
  })
  const manualPath = `${jobBase}/${manualJob.id}/acceptance`
  const evidencePlan = await api(`${manualPath}/merge-preview`)
  assert.deepEqual(evidencePlan.plan.mapping, [2])
  assert.ok(evidencePlan.plan.generatedMarkdown.includes('[E2]'))
  const manualAcceptance = await api(`${manualPath}/merge`, 'POST', {
    expectedRevision: 0, expectedSourceVersion: 3, expectedResultVersion: 1, confirmed: true,
    note: 'Synthetic selected content', title: 'Human title', markdown: 'Human paragraph [E1]\nSelected AI sentence [E2]', combineEvidence: true,
  })
  assert.equal(manualAcceptance.acceptance.mode, 'MANUAL')
  assert.equal(manualAcceptance.acceptance.appliedVersion, 4)
  const originalJava = await readFile(resolve(sourceDirectory, 'src/Greenhouse.java'))
  assert.deepEqual(Buffer.from(await (await fetch(`${base}/api${snapshotPath}`)).arrayBuffer()), originalJava)
  const movedFolder = await api(`${organizationPath}/folders/${knowledgeChild.id}`, 'PUT', {
    parentId: null, name: '이동한 가상 폴더', expectedRevision: 1,
  })
  assert.equal(movedFolder.revision, 2)
  await stop()
  configuredModel = 'changed-server-default-fixture'
  const restartMs = await start()
  assert.deepEqual(await api(acceptancePath), acceptedProposal)
  assert.deepEqual(await api(manualPath), manualAcceptance)
  assert.equal((await api(adoptionPath)).version.number, 4)
  assert.equal((await api(adoptionPath)).version.manuallyEdited, true)
  assert.equal((await api(adoptionPath)).version.markdown, 'Human paragraph [E1]\nSelected AI sentence [E2]')
  assert.deepEqual((await api(adoptionPath)).version.evidence.map(item => item.snapshotId), evidencePlan.plan.evidence.map(item => item.snapshotId))
  assert.equal((await api(`${adoptionPath}/versions/3`)).version.evidence.length, 1)
  assert.equal((await api(`${adoptionPath}/versions/2`)).version.manuallyEdited, false)
  assert.equal((await api(`${adoptionPath}/versions/3`)).version.markdown, 'Human paragraph [E1]')
  assert.equal((await api(`${adoptionPath}/versions/1`)).version.markdown, adoptionDraft.version.markdown)
  assert.equal((await api(`${adoptionPath}/published`)).version.number, 1)
  assert.equal((await api(`${adoptionPath}/publication`)).review, null)
  await api(adoptionPath, 'DELETE')
  await api(`${knowledgeBase}/${adoptionJob.resultCardId}`, 'DELETE')
  await api(`${knowledgeBase}/${manualJob.resultCardId}`, 'DELETE')
  assert.deepEqual(await api(acceptancePath), acceptedProposal)
  assert.deepEqual(await api(decisionsPath), rejectedProposal)
  const reopenedProposal = await api(decisionsPath, 'POST', {
    expectedRevision: 1, expectedSourceVersion: 1, expectedResultVersion: 1, status: 'OPEN', note: 'Synthetic reconsideration',
  })
  assert.deepEqual(reopenedProposal.map(item => item.status), ['OPEN', 'REJECTED'])
  const restoredOrganization = await api(organizationPath)
  assert.equal(restoredOrganization.folders.length, 2)
  assert.deepEqual(restoredOrganization.folders.find(folder => folder.id === knowledgeChild.id), movedFolder)
  assert.equal(restoredOrganization.placements[0].folderId, knowledgeChild.id)
  const folderConflict = await fetch(`${base}/api${organizationPath}/folders/${knowledgeChild.id}`, {
    method: 'DELETE', headers: { 'X-Forge-Client': '1' },
  })
  assert.equal(folderConflict.status, 409)
  await api(`${organizationPath}/cards/${draft.card.id}`, 'PUT', { folderId: null, expectedRevision: 1 })
  await api(`${organizationPath}/folders/${knowledgeChild.id}`, 'DELETE')
  await api(`${organizationPath}/folders/${knowledgeFolder.id}`, 'DELETE')
  assert.equal((await api(knowledgePath)).version.number, 1)
  assert.equal((await api(evidenceLocationPath)).entry.id, symbolHits[0].entryId)
  assert.equal((await api(`${publicationPath}/publication`)).review.reviewer, 'Synthetic smoke reviewer')
  assert.equal((await api(`${publicationPath}/published`)).version.number, 1)
  await api(`${publicationPath}/versions`, 'POST', { expectedVersion: 1, title: 'Synthetic revised draft', markdown: 'Synthetic fixture [E1]' })
  assert.equal((await api(`${publicationPath}/published`)).version.number, 1)
  assert.equal((await api(`${publicationPath}/publication`)).review, null)
  const restoredPublicationHit = (await api(publishedSearchPath)).find(hit => hit.cardId === completed.resultCardId)
  assert.equal(restoredPublicationHit.version, 1)
  assert.equal(restoredPublicationHit.name, publishedHit.name)
  assert.equal(restoredPublicationHit.snippet, publishedHit.snippet)
  assert.deepEqual(await api(`${bundleBase}/${originalBundle.id}`), originalBundle)
  assert.deepEqual(await api(`${bundleBase}/${publishedBundle.id}`), publishedBundle)
  assert.equal((await api(bundleBase)).length, 2)
  assert.equal((await api(`${bundleBase}/${originalBundle.id}/answer-preview`, 'POST', { provider: 'CODEX', requestedModel: 'preview-only-model' })).digest, originalAnswerPreview.digest)
  assert.deepEqual(await api(approvalPath), { ...answerApproval, status: 'CONSUMED' })
  assert.deepEqual(await api(answerJobPath), completedAnswer)
  assert.deepEqual(await api(conversationPath), linkedConversation)
  assert.equal((await api(`${conversationPath}/preview`, 'POST', contextRequest)).digest, contextPreview.digest)
  await api(conversationPath, 'DELETE')
  assert.deepEqual(await api(conversationBase), [])
  assert.deepEqual(await api(answerJobPath), completedAnswer)
  assert.deepEqual(await api(`${bundleBase}/${originalBundle.id}`), originalBundle)
  await checkAnswerEvents()
  assert.deepEqual(await api(answerSubmitPath, 'POST', answerRequest), completedAnswer)
  assert.equal((await api(`${bundleBase}/${publishedBundle.id}/answer-preview`, 'POST', { provider: 'CLAUDE', requestedModel: 'preview-only-model' })).digest, publishedAnswerPreview.digest)
  assert.equal((await api(`${jobBase}/${completed.id}`)).resultCardId, completed.resultCardId)
  assert.equal((await api(`${knowledgePath}/generation/jobs`, 'POST', generation)).status, 'SUCCEEDED')
  assert.equal((await api(`${knowledgePath}/generation/jobs`, 'POST', cancelledRequest)).status, 'CANCELLED')
  assert.equal((await api(`${jobBase}/${completed.id}`)).requestedModel, 'synthetic-smoke-model')
  assert.equal((await api(`${jobBase}/${cancellable.id}`)).requestedModel, 'synthetic-smoke-model')
  assert.equal((await api(`${jobBase}/${completed.id}`)).cliVersion, '0.0.0-test-fixture')
  assert.equal((await api(`${jobBase}/${completed.id}`)).cliCheckedAt, completed.cliCheckedAt)
  assert.equal((await api(`${jobBase}/${cancellable.id}`)).cliVersion, '0.0.0-test-fixture')
  assert.equal((await api(`${knowledgePath}/generation/preview`, 'POST', { provider: 'CODEX', requestedModel: 'synthetic-smoke-model' })).digest, transmission.digest)
  assert.equal((await api(knowledgeBase)).length, 2)
  assert.equal((await api('/session')).activeWorkspaceId, workspace.id)
  assert.equal((await api(`${contentPath}/preview`)).text, bytes.toString('utf8'))
  assert.equal((await api(searchPath)).length, 1)
  assert.equal((await api(knowledgePath)).version.markdown, '검토가 필요한 수동 메모')
  const edited = await api(`${knowledgePath}/versions`, 'POST', {
    expectedVersion: 1, title: '가상 실험 초안', markdown: '사람이 수정한 두 번째 초안',
  })
  assert.equal(edited.version.number, 2)
  const comparison = await api(`${jobBase}/${completed.id}/comparison`)
  assert.equal(comparison.base.version.number, 1)
  assert.equal(comparison.base.version.markdown, draft.version.markdown)
  assert.equal(comparison.current.version.markdown, edited.version.markdown)
  assert.equal(comparison.generated.version.number, 1)
  assert.equal(comparison.generated.card.latestVersion, 2)
  assert.equal((await api(`${publicationPath}/published`)).version.number, 1)
  assert.equal((await api(`${knowledgePath}/versions/1`)).version.markdown, draft.version.markdown)
  const conflict = await fetch(`${base}/api${knowledgePath}/versions`, {
    method: 'POST', headers: { 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
    body: JSON.stringify({ expectedVersion: 1, title: '충돌', markdown: '덮어쓰면 안 되는 내용' }),
  })
  assert.equal(conflict.status, 409)
  const same = await api(`/workspaces/${workspace.id}/sources/${source.id}/sync`, 'POST')
  assert.equal(same.revision, synced.revision)
  const restoredHit = (await api(searchPath))[0]
  assert.equal(restoredHit.entryId, symbolHits[0].entryId)
  assert.equal(restoredHit.snippet, symbolHits[0].snippet)
  assert.equal(restoredHit.startLine, symbolHits[0].startLine)
  assert.equal(restoredHit.endLine, symbolHits[0].endLine)
  assert.equal(restoredHit.bodyMatch, true)
  await rm(sourceDirectory, { recursive: true })
  const failed = await api(`/workspaces/${workspace.id}/sources/${source.id}/sync`, 'POST')
  assert.equal(failed.status, 'ERROR')
  assert.equal(failed.revision, synced.revision)
  assert.equal((await api(searchPath)).length, 1)
  await api(`/workspaces/${workspace.id}/sources/${source.id}`, 'DELETE')
  assert.equal((await api(searchPath)).length, 0)
  assert.equal((await api(knowledgePath)).version.evidence[0].freshness, 'MISSING')
  assert.equal((await fetch(`${base}/api${evidenceLocationPath}`)).status, 404)
  assert.equal((await api(`${publicationPath}/published`)).version.evidence[0].freshness, 'MISSING')
  assert.equal((await api(`${publicationPath}/publication`)).publication.version, 1)
  assert.equal((await api(publishedSearchPath)).find(hit => hit.cardId === completed.resultCardId).freshness, 'MISSING')
  assert.ok(!(await api(`${publishedSearchPath}&domains=${encodeURIComponent('실험')}`)).some(hit => hit.cardId === completed.resultCardId))
  assert.deepEqual(await api(`${bundleBase}/${originalBundle.id}`), originalBundle)
  assert.deepEqual(await api(`${bundleBase}/${publishedBundle.id}`), publishedBundle)
  const missingBundle = await api(bundleBase, 'POST', { question: '출력', includeKnowledge: true })
  assert.equal(missingBundle.payload.status, 'NO_EVIDENCE')
  assert.equal((await api(approvalPath)).status, 'CONSUMED')
  assert.deepEqual(await api(answerJobPath), completedAnswer)
  const revokedAnswerApproval = await api(`${approvalPath}/${answerApproval.approval.id}`, 'DELETE')
  assert.equal(revokedAnswerApproval.status, 'REVOKED')
  assert.deepEqual(await api(approvalPath), revokedAnswerApproval)
  for (const frozen of [originalBundle, publishedBundle]) {
    assert.equal((await api(`${bundleBase}/${frozen.id}/validation`)).eligible, false)
    const blocked = await fetch(`${base}/api${bundleBase}/${frozen.id}/answer-preview`, {
      method: 'POST', headers: { 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'CODEX', requestedModel: 'preview-only-model' }),
    })
    assert.equal(blocked.status, 409)
  }
  assert.ok(missingBundle.payload.excluded.some(item => item.reason === 'NOT_CURRENT'))
  await api(`${bundleBase}/${missingBundle.id}`, 'DELETE')
  assert.equal((await api(bundleBase)).length, 2)
  assert.deepEqual(Buffer.from(await (await fetch(`${base}/api${snapshotPath}`)).arrayBuffer()), originalJava)
  const streamWorkspace = await api('/workspaces', 'POST', { name: 'synthetic streaming smoke' })
  const streamBase = `/workspaces/${streamWorkspace.id}`
  const streamForm = new FormData()
  streamForm.append('file', new Blob(['synthetic telescope observation']), 'stream-fixture.txt')
  await api(`${streamBase}/files`, 'POST', streamForm)
  const streamBundle = await api(`${streamBase}/evidence-bundles`, 'POST', { question: 'telescope', domains: [], includeKnowledge: false })
  const streamPath = `${streamBase}/evidence-bundles/${streamBundle.id}`
  const streamPreview = await api(`${streamPath}/answer-preview`, 'POST', { provider: 'CLAUDE', requestedModel: 'stream-fixture-model' })
  const streamApproval = await api(`${streamPath}/answer-approval`, 'POST', { provider: 'CLAUDE', requestedModel: 'stream-fixture-model', digest: streamPreview.digest, acknowledged: true, expectedApprovalId: null })
  const streamJob = await api(`${streamPath}/answer-jobs`, 'POST', { requestId: crypto.randomUUID(), approvalId: streamApproval.approval.id, digest: streamPreview.digest, approved: true })
  const streamResponse = await fetch(`${base}/api${streamBase}/answer-jobs/${streamJob.id}/events`, { signal: AbortSignal.timeout(15000) })
  assert.equal(streamResponse.status, 200)
  const streamEvents = (await streamResponse.text()).trim().split(/\r?\n\r?\n/).filter(block => block.startsWith('event:')).map(block => {
    const lines = block.split(/\r?\n/)
    return { name: lines[0].slice(6).trim(), data: JSON.parse(lines.filter(line => line.startsWith('data:')).map(line => line.slice(5)).join('\n')) }
  })
  assert.equal(streamEvents[0].name, 'evidence')
  assert.ok(streamEvents.some(event => event.name === 'partial' && event.data.unverified && event.data.text.includes('가상 임시 출력')))
  const partialIndex = streamEvents.findIndex(event => event.name === 'partial')
  const finalIndex = streamEvents.findIndex(event => event.name === 'job' && event.data.status === 'SUCCEEDED')
  assert.ok(partialIndex > 0 && finalIndex > partialIndex)
  assert.equal(streamEvents[finalIndex].data.answer, '검사를 통과한 가상 최종 답변입니다. [R1]')
  assert.ok(!JSON.stringify(await api(`${streamBase}/answer-jobs/${streamJob.id}`)).includes('가상 임시 출력'))
  await stop()
  configuredCodex = resolve(root, 'scripts/fixtures/fake-codex-app-server.mjs')
  configuredStreamHome = resolve(directory, 'codex-profile')
  await mkdir(configuredStreamHome, { mode: 0o700 })
  await writeFile(resolve(configuredStreamHome, 'auth.json'), '{"synthetic":true}', { mode: 0o600 })
  await start()
  const codexPreview = await api(`${streamPath}/answer-preview`, 'POST', { provider: 'CODEX', requestedModel: 'app-server-fixture-model' })
  const codexApproval = await api(`${streamPath}/answer-approval`, 'POST', { provider: 'CODEX', requestedModel: 'app-server-fixture-model', digest: codexPreview.digest, acknowledged: true, expectedApprovalId: streamApproval.approval.id })
  const codexJob = await api(`${streamPath}/answer-jobs`, 'POST', { requestId: crypto.randomUUID(), approvalId: codexApproval.approval.id, digest: codexPreview.digest, approved: true })
  const codexEvents = await (await fetch(`${base}/api${streamBase}/answer-jobs/${codexJob.id}/events`, { signal: AbortSignal.timeout(15000) })).text()
  assert.ok(codexEvents.indexOf('event:evidence') < codexEvents.indexOf('event:partial'))
  assert.ok(codexEvents.includes('가상 Codex 임시 출력'))
  assert.ok(codexEvents.includes('SUCCEEDED'))
  const codexFinal = await api(`${streamBase}/answer-jobs/${codexJob.id}`)
  assert.equal(codexFinal.answer, '검사를 통과한 Codex 가상 답변입니다. [R1]')
  assert.ok(!JSON.stringify(codexFinal).includes('임시 출력'))
  const cancelApproval = await api(`${streamPath}/answer-approval`, 'POST', { provider: 'CODEX', requestedModel: 'app-server-fixture-model', digest: codexPreview.digest, acknowledged: true, expectedApprovalId: codexApproval.approval.id })
  const cancelJob = await api(`${streamPath}/answer-jobs`, 'POST', { requestId: crypto.randomUUID(), approvalId: cancelApproval.approval.id, digest: codexPreview.digest, approved: true })
  await waitForJob(`${streamBase}/answer-jobs/${cancelJob.id}`, job => job.status === 'RUNNING')
  await api(`${streamBase}/answer-jobs/${cancelJob.id}/cancel`, 'POST')
  const cancelledCodex = await waitForJob(`${streamBase}/answer-jobs/${cancelJob.id}`, job => job.status === 'CANCELLED')
  assert.equal(cancelledCodex.answer, null)
  const contextRoom = await api(`${streamBase}/conversations`, 'POST', { title: 'synthetic contextual chat' })
  const contextRoomPath = `${streamBase}/conversations/${contextRoom.id}`
  await api(`${contextRoomPath}/turns`, 'POST', { jobId: codexJob.id, expectedRevision: 0 })
  const followupBundle = await api(`${streamBase}/evidence-bundles`, 'POST', { question: 'telescope', domains: [], includeKnowledge: false })
  const followupPath = `${streamBase}/evidence-bundles/${followupBundle.id}`
  const followupSelection = { bundleId: followupBundle.id, expectedRevision: 1, jobIds: [codexJob.id], provider: 'CODEX', requestedModel: 'app-server-fixture-model' }
  const followupPreview = await api(`${contextRoomPath}/preview`, 'POST', followupSelection)
  const frozenContext = await api(`${contextRoomPath}/requests`, 'POST', { selection: followupSelection, digest: followupPreview.digest })
  assert.equal((await api(`${followupPath}/answer-approval`)).status, 'NONE')
  await stop()
  await start()
  const restoredContext = await api(`${followupPath}/answer-preview`, 'POST', { provider: 'CODEX', requestedModel: 'app-server-fixture-model' })
  assert.equal(restoredContext.contextRequestId, frozenContext.id)
  assert.equal(restoredContext.prompt, frozenContext.prompt)
  assert.equal(restoredContext.digest, frozenContext.digest)
  const followupApproval = await api(`${followupPath}/answer-approval`, 'POST', { provider: 'CODEX', requestedModel: 'app-server-fixture-model', digest: restoredContext.digest, acknowledged: true, expectedApprovalId: null })
  const followupJob = await api(`${followupPath}/answer-jobs`, 'POST', { requestId: crypto.randomUUID(), approvalId: followupApproval.approval.id, digest: restoredContext.digest, approved: true })
  const followupEvents = await (await fetch(`${base}/api${streamBase}/answer-jobs/${followupJob.id}/events`, { signal: AbortSignal.timeout(15000) })).text()
  assert.ok(followupEvents.includes('SUCCEEDED'))
  assert.ok(followupEvents.indexOf('event:evidence') < followupEvents.indexOf('event:partial'))
  assert.deepEqual((await api(contextRoomPath)).turns.map(turn => turn.jobId), [codexJob.id, followupJob.id])
  await api(streamBase, 'DELETE')
  const livePublic = []
  if (process.argv.includes('--live-public')) {
    for (const registration of [
      { name: '공개 HTTPS 검증', kind: 'URL', location: 'https://example.org', domain: '검증' },
      { name: '공개 GitHub 검증', kind: 'GITHUB', location: 'https://github.com/thisisnew/dougle', domain: '검증' },
    ]) {
      const registered = await api(`/workspaces/${workspace.id}/sources`, 'POST', registration)
      const collected = await api(`/workspaces/${workspace.id}/sources/${registered.id}/sync`, 'POST')
      assert.equal(collected.status, 'READY', `${registration.kind}: ${collected.error}`)
      livePublic.push({ kind: registration.kind, location: registration.location, revision: collected.revision })
      await api(`/workspaces/${workspace.id}/sources/${registered.id}`, 'DELETE')
    }
  }
  const uploadTimes = []
  for (let index = 0; index < 100; index++) {
    const synthetic = new FormData()
    synthetic.append('file', new Blob([`가상 측정 기록 ${index}\n`.repeat(100)]), `sample-${index}.txt`)
    uploadTimes.push(await measure(() => api(`/workspaces/${workspace.id}/files`, 'POST', synthetic)))
  }
  const listPath = `/workspaces/${workspace.id}/entries`
  for (let index = 0; index < 5; index++) await api(listPath)
  const listTimes = []
  for (let index = 0; index < 50; index++) {
    listTimes.push(await measure(async () => assert.equal((await api(listPath)).length, 101)))
  }
  const searchTimes = []
  for (let index = 0; index < 30; index++) {
    searchTimes.push(await measure(async () => {
      assert.ok((await api(`/workspaces/${workspace.id}/search?query=${encodeURIComponent('측정')}`)).length > 0)
    }))
  }
  assert.deepEqual(await api(`${listPath}?area=KNOWLEDGE`), [])
  await api(`/workspaces/${workspace.id}`, 'DELETE')
  assert.equal((await api('/session')).activeWorkspaceId, null)
  await stop()
  await start()
  assert.deepEqual((await api('/session')).workspaces, [])
  const result = {
    measuredAt: new Date().toISOString(),
    platform: `${process.platform}/${process.arch}`,
    java: '21',
    springBoot: '4.1.1',
    dataset: '100 synthetic UTF-8 files (~2.5 KB each) + 1 nested fixture; sequential HTTP requests',
    checks: ['Codex opt-in fake App Server evidence-first partial/final SSE and cancellation',
      'ordered conversation links survive restart, retry idempotently, and delete without removing answers or evidence',
      'read-only selected conversation context preview survives restart with a distinct non-approval digest',
      'frozen context survives restart, requires separate approval and dispatch, and atomically links the completed follow-up answer',
      'packaged UI', 'upload/download byte equality', 'UTF-8 preview', 'restart persistence',
      'active workspace persistence', 'independent knowledge area', 'cascade deletion', 'deletion persists after restart',
      'local folder collection', 'Java symbol line numbers', 'search survives restart', 'unchanged revision preserves entry ids',
      'search body offsets and extracted line ranges survive restart',
      'published knowledge search excludes later drafts and survives restart with live evidence freshness and source domain filters',
      'immutable original and published evidence bundles survive restart and source deletion without LLM calls',
      'read-only bundle revalidation and provider/model-bound answer previews survive restart and block deleted evidence',
      'single-use answer approval dispatches fake CLI, retains cited answer and idempotency across restart, and supports revocation',
      'read-only SSE sends immutable evidence before completed answer and replays matching snapshots after restart',
      'fake Claude stream sends unverified temporary text before independently validated final answer without persisting partials',
      'failed collection preserves prior index', 'source deletion removes search hits', 'Korean Nori search',
      'manual knowledge draft and snapshot', 'knowledge survives restart', 'immutable prior version',
      'optimistic edit conflict', 'source deletion preserves historical evidence bytes',
      'CLI payload preview without external call', 'unapproved and mismatched AI transmission rejected',
      'fake CLI async generation and cancellation', 'idempotent job replay across restart', 'completed job and draft persist',
      'per-request model overrides server default', 'requested model history survives changed defaults and restart',
      'pre-inference CLI observation persists for completed and cancelled jobs',
      'separate human review and publication persist across restart',
      'new draft and missing originals preserve prior publication with freshness warnings',
      'version-scoped evidence navigation survives restart and returns 404 after source deletion',
      'independent nested knowledge folders and card placement persist across restart',
      'nonempty folder deletion blocked and moving to root preserves document versions',
      'folder rename and parent move revision survive restart',
      'read-only generation comparison keeps base and initial AI version after later edits',
      'proposal rejection persists across restart and reopening preserves history without document changes',
      'explicit proposal acceptance persists as a new unverified draft while prior version and publication survive restart',
      'manual proposal merge preserves human version and publication with persisted MANUAL acceptance after restart',
      'explicit evidence union preserves human E1 and remaps AI E1 to E2 with persisted snapshots after restart'],
    sourceSyncMs,
    livePublic,
    startupMs: Math.round(startupMs),
    restartMs: Math.round(restartMs),
    upload100: { p50Ms: percentile(uploadTimes, 0.5), p95Ms: percentile(uploadTimes, 0.95) },
    list101Entries50RequestsAfter5Warmups: { p50Ms: percentile(listTimes, 0.5), p95Ms: percentile(listTimes, 0.95) },
    search100Files30Requests: { p50Ms: percentile(searchTimes, 0.5), p95Ms: percentile(searchTimes, 0.95) },
    limitations: 'Single local process, small synthetic dataset, no concurrency; AI jobs use an explicit fake CLI fixture, not real LLM performance.',
  }
  result.launchMode = desktopResources ? 'bundled Java / desktop session / stdin lifecycle (not native webview)' : 'development JAR'
  await writeFile(resolve(root, desktopResources ? 'docs/performance-desktop-runtime.json' : 'docs/performance-phase3-foundation.json'), JSON.stringify(result, null, 2) + '\n')
  console.log(JSON.stringify(result, null, 2))
} catch (error) {
  console.error(logs.slice(-12000))
  throw error
} finally {
  await stop()
  await rm(directory, { recursive: true, force: true })
}
