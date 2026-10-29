import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'

const resources = resolve(process.argv[2] || 'desktop/src-tauri/resources')
const output = resolve(process.argv[3] || '/tmp/dougle-portable-install-results.json')
const logLimitBytes = 100000
await mkdir(dirname(output), { recursive: true })
const logDirectory = await mkdtemp(output + '.logs-')
const digest = createHash('sha256')
for await (const block of createReadStream(resolve(resources, 'dougle.jar'))) digest.update(block)
const report = { schemaVersion: 1, jarSha256: digest.digest('hex'), architecture: process.arch,
  scope: 'Bundled JVM, clean synthetic HOME/data, fake authenticated CLIs; not company SSO or ARM runtime acceptance',
  actualAiCalls: 0, logDirectory, logLimitBytesPerStream: logLimitBytes, cases: [] }
const modes = [['absent-tools', []], ['discovered-tools', ['--auto-discovery']],
  ['profile-alias-live-selection', ['--profile-alias', '--live-selection']]]
for (const portable of [false, true]) {
  for (const [mode, flags] of modes) {
    const started = Date.now()
    const id = `${portable ? 'unicode-space-quote-home' : 'ascii-home'}:${mode}`
    const result = await new Promise(resolveResult => {
      let stdout = Buffer.alloc(0), stderr = Buffer.alloc(0), timedOut = false
      let stdoutBytes = 0, stderrBytes = 0, spawnError = null
      const child = spawn(process.execPath, ['scripts/fresh-install-smoke.mjs', resources, ...flags,
        ...(portable ? ['--portable-path'] : [])], { stdio: ['ignore', 'pipe', 'pipe'], detached: true })
      child.stdout.on('data', chunk => {
        stdoutBytes += chunk.length
        stdout = Buffer.from(Buffer.concat([stdout, chunk]).subarray(-logLimitBytes))
      })
      child.stderr.on('data', chunk => {
        stderrBytes += chunk.length
        stderr = Buffer.from(Buffer.concat([stderr, chunk]).subarray(-logLimitBytes))
      })
      const timer = setTimeout(() => {
        timedOut = true
        if (child.pid) { try { process.kill(-child.pid, 'SIGTERM') } catch {} }
      }, 600000)
      const killTimer = setTimeout(() => {
        if (child.pid) { try { process.kill(-child.pid, 'SIGKILL') } catch {} }
      }, 615000)
      child.once('error', error => { spawnError = { code: error.code || null, message: error.message.slice(-2000) } })
      child.once('close', (code, signal) => {
        clearTimeout(timer)
        clearTimeout(killTimer)
        resolveResult({ code, signal, timedOut, stdout, stderr, stdoutBytes, stderrBytes, spawnError })
      })
    })
    const logName = id.replace(':', '-')
    const stdoutPath = join(logDirectory, logName + '.stdout.log')
    const stderrPath = join(logDirectory, logName + '.stderr.log')
    await writeFile(stdoutPath, result.stdout, { mode: 0o600 })
    await writeFile(stderrPath, result.stderr, { mode: 0o600 })
    let checks = []
    let valid = false
    let validationError = null
    try {
      const stdout = result.stdout.toString('utf8')
      const start = stdout.lastIndexOf('{\n  "checks":')
      assert.ok(start >= 0, 'Smoke JSON summary missing from retained stdout tail')
      const details = JSON.parse(stdout.slice(start))
      assert.equal(details.actualAiCalls, 0)
      assert.equal(details.companyAuthenticationVerified, false)
      assert.equal(details.portablePath, portable)
      assert.ok(Array.isArray(details.checks), 'Smoke checks must be an array')
      assert.ok(details.checks.length >= 4)
      checks = details.checks
      valid = true
    } catch (error) { validationError = error.message.slice(-2000) }
    const status = result.code === 0 && !result.spawnError && !result.timedOut && valid ? 'PASS' : 'FAIL'
    const failureReasons = []
    if (result.spawnError) failureReasons.push('Spawn failed: ' + result.spawnError.message)
    if (result.timedOut) failureReasons.push('Smoke exceeded the 600000 ms timeout')
    if (result.signal) failureReasons.push('Smoke terminated by signal ' + result.signal)
    if (result.code !== 0) failureReasons.push('Smoke exit code: ' + result.code)
    if (validationError) failureReasons.push('Summary validation failed: ' + validationError)
    report.cases.push({ id, status, checks, elapsedMilliseconds: Date.now() - started,
      exitCode: result.code, signal: result.signal, timedOut: result.timedOut,
      spawnError: result.spawnError, validationError, failureReasons,
      stderrTail: status === 'FAIL' ? result.stderr.toString('utf8').slice(-2000) : null,
      logs: {
        stdout: { path: stdoutPath, totalBytes: result.stdoutBytes, retainedBytes: result.stdout.length,
          truncated: result.stdoutBytes > result.stdout.length },
        stderr: { path: stderrPath, totalBytes: result.stderrBytes, retainedBytes: result.stderr.length,
          truncated: result.stderrBytes > result.stderr.length },
      } })
    await writeFile(output, JSON.stringify(report, null, 2) + '\n')
    console.log(`${status} ${id}${status === 'FAIL' ? ': ' + failureReasons.join('; ') + '\nLogs: ' + stdoutPath + ' ' + stderrPath : ''}`)
  }
}
assert.ok(report.cases.every(item => item.status === 'PASS'), 'Portable installation scenarios failed; inspect ' + output + ' and ' + logDirectory)
