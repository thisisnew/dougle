import { spawn } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { closeSync, constants, fstatSync, fsyncSync, lstatSync, openSync, readFileSync, realpathSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { isAbsolute, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const LIMIT = 1048576
const LINE_LIMIT = 262144
const BINDINGS = ['UNBOUND', 'MISSING_THREAD', 'INVALID_THREAD', 'OTHER_THREAD', 'MISSING_TURN', 'INVALID_TURN', 'OTHER_TURN', 'MATCH']
const EVENTS = ['AGENT_STARTED', 'AGENT_DELTA', 'AGENT_COMPLETED', 'USER_STARTED', 'USER_COMPLETED',
  'REASONING_STARTED', 'REASONING_COMPLETED', 'OTHER_ITEM_STARTED', 'OTHER_ITEM_COMPLETED',
  'TURN_STARTED', 'TURN_COMPLETED', 'THREAD_STARTED', 'THREAD_STATUS', 'TOKEN_USAGE', 'ERROR', 'OTHER']
const STATUSES = ['NONE', 'COMPLETED', 'FAILED', 'INTERRUPTED', 'IN_PROGRESS', 'OTHER']
const counts = keys => Object.fromEntries(keys.map(key => [key, 0]))
const increment = (record, key, amount = 1) => { record[key] = Math.min(LIMIT, record[key] + amount) }
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const identifier = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 256
const bindingValue = value => value === undefined || value === null ? null : identifier(value) ? value : false
const known = (values, key, fallback) => typeof key === 'string' && Object.hasOwn(values, key) ? values[key] : fallback

function descriptor(value) {
  const params = value.params
  const method = value.method
  let event = known({ 'item/agentMessage/delta': 'AGENT_DELTA', 'turn/started': 'TURN_STARTED', 'turn/completed': 'TURN_COMPLETED',
    'thread/started': 'THREAD_STARTED', 'thread/status/changed': 'THREAD_STATUS', 'thread/tokenUsage/updated': 'TOKEN_USAGE', error: 'ERROR' }, method, 'OTHER')
  if (method === 'item/started' || method === 'item/completed') {
    const type = known({ agentMessage: 'AGENT', userMessage: 'USER', reasoning: 'REASONING' }, params.item?.type, 'OTHER_ITEM')
    event = `${type}_${method === 'item/started' ? 'STARTED' : 'COMPLETED'}`
  }
  const rawStatus = params.turn?.status
  const status = rawStatus === undefined || rawStatus === null ? 'NONE'
    : known({ completed: 'COMPLETED', failed: 'FAILED', interrupted: 'INTERRUPTED', inProgress: 'IN_PROGRESS' }, rawStatus, 'OTHER')
  return { event, thread: bindingValue(params.threadId), directTurn: bindingValue(params.turnId), nestedTurn: bindingValue(params.turn?.id), status }
}

export class ProtocolCounters {
  #thread
  #turn
  #expected = 1
  #early = []
  #pending = Buffer.alloc(0)
  #droppingLine = false
  #stats = {
    version: 1,
    totals: counts(['stdoutBytes', 'lines', 'invalidJson', 'invalidEnvelopes', 'oversizedLines', 'captureLimitReached', 'earlyDropped']),
    responses: counts(['INITIALIZE', 'CONFIG', 'THREAD', 'TURN', 'UNEXPECTED', 'INVALID']),
    preStreamIgnored: counts(EVENTS),
    applicationBinding: Object.fromEntries(EVENTS.map(event => [event, counts(BINDINGS)])),
    normalizedBinding: Object.fromEntries(EVENTS.map(event => [event, counts(BINDINGS)])),
    completionStatus: Object.fromEntries(['APPLICATION_MATCH', 'APPLICATION_IGNORED', 'NORMALIZED_MATCH', 'NORMALIZED_IGNORED'].map(key => [key, counts(STATUSES)])),
  }

  feed(bytes) {
    if (this.#stats.totals.captureLimitReached) return
    const remaining = LIMIT - this.#stats.totals.stdoutBytes
    const accepted = bytes.subarray(0, remaining)
    increment(this.#stats.totals, 'stdoutBytes', accepted.length)
    let offset = 0
    while (offset < accepted.length) {
      const newline = accepted.indexOf(10, offset)
      const end = newline < 0 ? accepted.length : newline
      if (!this.#droppingLine) {
        if (this.#pending.length + end - offset > LINE_LIMIT) {
          this.#pending = Buffer.alloc(0)
          this.#droppingLine = true
          increment(this.#stats.totals, 'oversizedLines')
        } else this.#pending = Buffer.concat([this.#pending, accepted.subarray(offset, end)])
      }
      if (newline < 0) break
      increment(this.#stats.totals, 'lines')
      if (!this.#droppingLine && this.#pending.length) this.#line(this.#pending)
      this.#pending = Buffer.alloc(0)
      this.#droppingLine = false
      offset = end + 1
    }
    if (bytes.length > remaining || this.#stats.totals.stdoutBytes === LIMIT) {
      this.#stats.totals.captureLimitReached = 1
      this.#pending = Buffer.alloc(0)
    }
  }

  #line(bytes) {
    let value
    try { value = JSON.parse(bytes.toString('utf8')) } catch { increment(this.#stats.totals, 'invalidJson'); return }
    if (!object(value)) { increment(this.#stats.totals, 'invalidEnvelopes'); return }
    if (Object.hasOwn(value, 'id')) {
      if (!Number.isInteger(value.id) || value.id !== this.#expected || this.#expected > 4) {
        increment(this.#stats.responses, 'UNEXPECTED'); return
      }
      if (Object.hasOwn(value, 'method') || Object.hasOwn(value, 'error') || !object(value.result)
        || value.id === 3 && !identifier(value.result.thread?.id) || value.id === 4 && !identifier(value.result.turn?.id)) {
        increment(this.#stats.responses, 'INVALID'); return
      }
      increment(this.#stats.responses, ['INITIALIZE', 'CONFIG', 'THREAD', 'TURN'][value.id - 1])
      if (value.id === 3) this.#thread = value.result.thread.id
      if (value.id === 4) {
        this.#turn = value.result.turn.id
        for (const early of this.#early) this.#count(this.#stats, early)
        this.#early = []
      }
      this.#expected++
      return
    }
    if (typeof value.method !== 'string' || value.method.length > 256 || !object(value.params)) {
      increment(this.#stats.totals, 'invalidEnvelopes'); return
    }
    const event = descriptor(value)
    if (this.#turn === undefined) {
      if (this.#expected !== 4) increment(this.#stats.preStreamIgnored, event.event)
      else if (this.#early.length < 128) this.#early.push(event)
      else increment(this.#stats.totals, 'earlyDropped')
    } else this.#count(this.#stats, event)
  }

  #binding(event, normalized) {
    if (this.#thread === undefined || this.#turn === undefined) return 'UNBOUND'
    if (event.thread === null) return 'MISSING_THREAD'
    if (event.thread === false) return 'INVALID_THREAD'
    if (event.thread !== this.#thread) return 'OTHER_THREAD'
    const turn = event.event === 'TURN_COMPLETED' || normalized && event.event === 'TURN_STARTED' ? event.nestedTurn : event.directTurn
    if (turn === null) return 'MISSING_TURN'
    if (turn === false) return 'INVALID_TURN'
    return turn === this.#turn ? 'MATCH' : 'OTHER_TURN'
  }

  #count(stats, event) {
    for (const [key, normalized, prefix] of [['applicationBinding', false, 'APPLICATION'], ['normalizedBinding', true, 'NORMALIZED']]) {
      const binding = this.#binding(event, normalized)
      increment(stats[key][event.event], binding)
      if (event.event === 'TURN_COMPLETED') increment(stats.completionStatus[`${prefix}_${binding === 'MATCH' ? 'MATCH' : 'IGNORED'}`], event.status)
    }
  }

  snapshot() {
    const result = structuredClone(this.#stats)
    for (const event of this.#early) this.#count(result, event)
    result.buffer = { pendingLineBytes: this.#pending.length, earlyPending: this.#early.length }
    return result
  }
}

function fail(code) { const error = new Error(code); error.safeCode = code; throw error }

function privatePath(path, directory = false) {
  if (typeof path !== 'string' || !isAbsolute(path) || resolve(path) !== path) fail('INVALID_PRIVATE_PATH')
  const info = lstatSync(path)
  if (info.isSymbolicLink() || (directory ? !info.isDirectory() : !info.isFile())
    || info.uid !== process.getuid() || (info.mode & 0o077) !== 0) fail('UNSAFE_PRIVATE_PATH')
  return info
}

function pinConfiguration(path) {
  const info = privatePath(path)
  if (info.size > 4096 || info.nlink !== 1) fail('INVALID_PIN_FILE')
  const descriptor = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW)
  let pin
  try {
    const opened = fstatSync(descriptor)
    if (opened.ino !== info.ino || opened.dev !== info.dev) fail('PIN_CHANGED')
    pin = JSON.parse(readFileSync(descriptor, 'utf8'))
  } finally { closeSync(descriptor) }
  if (!object(pin) || Object.keys(pin).sort().join(',') !== 'executable,sha256,statsDirectory,version'
    || pin.version !== 1 || typeof pin.executable !== 'string' || !isAbsolute(pin.executable)
    || typeof pin.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(pin.sha256)) fail('INVALID_PIN')
  privatePath(pin.statsDirectory, true)
  const statsDirectory = realpathSync(pin.statsDirectory)
  if (process.env.CODEX_HOME) {
    const distance = relative(realpathSync(process.env.CODEX_HOME), statsDirectory)
    if (distance === '' || !distance.startsWith(`..${sep}`) && distance !== '..' && !isAbsolute(distance)) fail('PROFILE_STATS_FORBIDDEN')
  }
  const binary = lstatSync(pin.executable)
  if (!binary.isFile() || binary.isSymbolicLink() || !(binary.mode & 0o111) || (binary.mode & 0o022) !== 0 || binary.size > 512 * 1024 * 1024) fail('UNSAFE_EXECUTABLE')
  const executable = realpathSync(pin.executable)
  const file = openSync(executable, constants.O_RDONLY | constants.O_NOFOLLOW)
  try {
    const opened = fstatSync(file)
    if (opened.ino !== binary.ino || opened.dev !== binary.dev
      || createHash('sha256').update(readFileSync(file)).digest('hex') !== pin.sha256) fail('PIN_MISMATCH')
  } finally { closeSync(file) }
  const current = lstatSync(pin.executable)
  if (current.ino !== binary.ino || current.dev !== binary.dev || current.size !== binary.size || current.mtimeMs !== binary.mtimeMs) fail('PIN_CHANGED')
  return { executable, statsDirectory }
}

function statsWriter(directory) {
  const target = join(directory, `codex-app-server-${randomUUID()}.json`)
  let previous
  return counters => {
    const encoded = JSON.stringify(counters.snapshot()) + '\n'
    if (encoded === previous) return
    const temporary = join(directory, `.counter-${randomUUID()}.tmp`)
    let descriptor
    try {
      privatePath(directory, true)
      descriptor = openSync(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600)
      writeFileSync(descriptor, encoded)
      fsyncSync(descriptor)
      closeSync(descriptor)
      descriptor = undefined
      renameSync(temporary, target)
      previous = encoded
    } finally {
      if (descriptor !== undefined) closeSync(descriptor)
      try { unlinkSync(temporary) } catch (error) { if (error.code !== 'ENOENT') throw error }
    }
  }
}

async function main() {
  const args = process.argv.slice(2)
  if (process.platform === 'win32' || args.length < 4 || args[0] !== '--pin-file' || args[2] !== '--') fail('INVALID_INVOCATION')
  const forwarded = args.slice(3)
  const appServer = forwarded.slice(0, 4).join(' ') === '--no-daemon app-server --stdio --strict-config'
  const exec = forwarded[0] === 'exec' || forwarded[0] === '--no-daemon' && forwarded[1] === 'exec'
  const probe = forwarded.length === 1 && forwarded[0] === '--version'
    || forwarded.length === 2 && forwarded[0] === 'login' && forwarded[1] === 'status'
    || forwarded.length === 4 && forwarded[0] === '--config' && forwarded[1] === 'cli_auth_credentials_store="file"'
      && forwarded[2] === 'login' && forwarded[3] === 'status'
  if (!appServer && !exec && !probe) fail('UNSUPPORTED_INVOCATION')
  const pin = pinConfiguration(args[1])
  const counters = appServer ? new ProtocolCounters() : null
  const flush = appServer ? statsWriter(pin.statsDirectory) : () => {}
  flush(counters)
  const child = spawn(pin.executable, forwarded, { cwd: process.cwd(), env: process.env, detached: true, stdio: appServer ? ['pipe', 'pipe', 'pipe'] : 'inherit' })
  let stopping
  let timer
  let failed = false
  const kill = signal => {
    if (!child.pid) return
    try { process.kill(-child.pid, signal) } catch (error) { if (error.code !== 'ESRCH') throw error }
  }
  const stop = signal => {
    if (stopping) return
    stopping = signal
    try { flush(counters) } catch { failed = true }
    kill(signal)
    timer = setTimeout(() => kill('SIGKILL'), 2000)
  }
  const signals = ['SIGTERM', 'SIGINT', 'SIGHUP']
  const handlers = signals.map(signal => () => stop(signal))
  signals.forEach((signal, index) => process.on(signal, handlers[index]))
  const pipeError = () => { failed = true; stop('SIGTERM') }
  process.stdout.on('error', pipeError)
  process.stderr.on('error', pipeError)
  if (appServer) {
    child.stdin.on('error', error => { if (error.code !== 'EPIPE') pipeError() })
    process.stdin.pipe(child.stdin)
    child.stderr.pipe(process.stderr, { end: false })
    child.stdout.on('data', bytes => {
      try {
        counters.feed(bytes)
        flush(counters)
        if (!process.stdout.write(bytes)) child.stdout.pause()
      } catch { pipeError() }
    })
    process.stdout.on('drain', () => child.stdout.resume())
  }
  const ended = await new Promise(resolve => {
    child.once('error', () => { failed = true; resolve({ code: 70 }) })
    child.once('exit', (code, signal) => { kill('SIGKILL'); if (signal) stopping ??= signal })
    child.once('close', (code, signal) => resolve({ code, signal }))
  })
  if (timer) clearTimeout(timer)
  kill('SIGKILL')
  if (appServer) { process.stdin.unpipe(child.stdin); process.stdin.pause() }
  try { flush(counters) } catch { failed = true }
  signals.forEach((signal, index) => process.removeListener(signal, handlers[index]))
  if (failed) { process.exitCode = 70; return }
  const signal = stopping ?? ended.signal
  if (signal) { process.kill(process.pid, signal); return }
  process.exitCode = ended.code ?? 70
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    process.stderr.write(`codex-protocol-counter: ${['INVALID_INVOCATION', 'UNSUPPORTED_INVOCATION', 'INVALID_PRIVATE_PATH', 'UNSAFE_PRIVATE_PATH',
      'INVALID_PIN_FILE', 'PIN_CHANGED', 'INVALID_PIN', 'UNSAFE_EXECUTABLE', 'PIN_MISMATCH', 'PROFILE_STATS_FORBIDDEN'].includes(error.safeCode) ? error.safeCode : 'LOCAL_SETUP_FAILURE'}\n`)
    process.exitCode = 70
  })
}
