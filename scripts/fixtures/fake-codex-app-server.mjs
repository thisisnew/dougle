#!/usr/bin/env node
import assert from 'node:assert/strict'
import { appendFileSync, readFileSync, statSync } from 'node:fs'
import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'

const args = process.argv.slice(2)
if (args.includes('--version')) {
  console.log('codex-cli 0.0.0-test-fixture')
  process.exit(0)
}
const option = name => args.find(value => value.startsWith(`${name}=`))?.slice(name.length + 1)
const mode = option('--fixture-mode') ?? 'normal'
const trace = option('--fixture-trace')
const record = value => { if (trace) appendFileSync(trace, `${JSON.stringify(value)}\n`) }
record({ pid: process.pid, cwd: process.cwd(), home: process.env.HOME, profile: process.env.CODEX_HOME, env: Object.keys(process.env), args })
assert.ok(args.includes('app-server') && args.includes('--stdio') && args.includes('--no-daemon'))
assert.equal(process.env.HOME, process.cwd())
assert.notEqual(process.env.HOME, process.env.CODEX_HOME)
const features = Object.fromEntries(args.flatMap((arg, index) => arg === '--disable' ? [[args[index + 1], false]] : []))
for (const required of ['shell_tool', 'hooks', 'plugins', 'view_image', 'skill_search', 'remote_plugin']) assert.equal(features[required], false)
for (const required of ['approval_policy="never"', 'sandbox_mode="read-only"', 'model_provider="openai"', 'web_search="disabled"']) assert.ok(args.includes(required))
assert.equal(process.env.OPENAI_API_KEY, undefined)
assert.equal(process.env.ANTHROPIC_API_KEY, undefined)
const send = message => process.stdout.write(`${JSON.stringify(message)}\n`)
const response = (id, result) => send({ id, result })
const notification = (method, body) => send({ method, params: { threadId: 'fixture-thread', turnId: 'fixture-turn', ...body } })
const item = { type: 'agentMessage', id: 'fixture-answer', phase: 'final_answer', text: '' }
const partial = () => {
  notification('item/started', { item })
  notification('item/agentMessage/delta', { itemId: item.id, delta: '가상 Codex 임시 출력' })
}
setInterval(() => {}, 1000)
createInterface({ input: process.stdin }).on('line', line => {
  const request = JSON.parse(line)
  record({ method: request.method, id: request.id })
  if (request.method === 'initialize') {
    if (mode === 'hang') {
      const child = spawn('/bin/sleep', ['60'], { stdio: 'ignore' })
      record({ childPid: child.pid })
      return
    }
    if (mode === 'large-line') return process.stdout.write('x'.repeat(262145))
    if (mode === 'stderr-limit') return process.stderr.write('x'.repeat(262145))
    if (mode === 'exit') return process.exit(7)
    response(mode === 'wrong-id' ? 99 : request.id, { userAgent: 'fixture' })
  } else if (request.method === 'config/read') {
    response(request.id, {
      config: { approval_policy: 'never', sandbox_mode: 'read-only', web_search: 'disabled', model_provider: 'openai',
        features, mcp_servers: {}, model_providers: {} },
      layers: [{ name: { type: 'sessionFlags' }, config: {} },
        { name: { type: 'user' }, config: mode === 'unsafe-config' ? { mcp_servers: { unexpected: {} } } : {} }],
    })
  } else if (request.method === 'thread/start') {
    assert.equal(request.params.ephemeral, true)
    assert.equal(request.params.approvalPolicy, 'never')
    response(request.id, { thread: { id: 'fixture-thread', ephemeral: true }, cwd: process.cwd(), model: request.params.model ?? 'fixture-default',
      modelProvider: 'openai', approvalPolicy: 'never', instructionSources: [],
      sandbox: { type: mode === 'unsafe-thread' ? 'dangerFullAccess' : 'readOnly', networkAccess: false } })
  } else if (request.method === 'turn/start') {
    if (mode === 'image') {
      const images = request.params.input.filter(value => value.type === 'localImage')
      assert.equal(images.length, 1)
      assert.ok(request.params.input.some(value => value.text === 'Image evidence [R1]'))
      assert.ok(images[0].path.startsWith(process.cwd() + '/'))
      assert.equal(statSync(images[0].path).mode & 0o777, 0o600)
      assert.equal(readFileSync(images[0].path).toString(), 'synthetic-image-bytes')
      record({ verifiedImage: images[0].path })
    }
    assert.ok(request.params.input[0].text.length > 0)
    assert.equal(request.params.outputSchema.type, 'object')
    if (mode === 'early') partial()
    response(request.id, { turn: { id: 'fixture-turn', status: 'inProgress', items: [] } })
    if (mode === 'request') return send({ id: 90, method: 'item/commandExecution/requestApproval', params: {} })
    if (mode === 'tool') return notification('item/started', { item: { id: 'unexpected', type: 'commandExecution' } })
    if (mode !== 'early') partial()
    setTimeout(() => {
      notification('item/completed', { item: { ...item, text: JSON.stringify({ title: '가상 Codex 답변',
        claims: [{ text: '검사를 통과한 Codex 가상 답변입니다.', evidenceIds: ['R1'] }] }) } })
      notification('turn/completed', { turn: { id: 'fixture-turn', status: 'completed', items: [] } })
    }, 1800)
  }
})
