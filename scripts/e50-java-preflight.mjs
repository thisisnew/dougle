import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { chmod, mkdir, mkdtemp, readFile, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { childEnvironment, sandboxProfile, sha256 } from './e50-archive-fixtures.mjs'

const execute = promisify(execFile)
const networkOnly = '(version 1) (allow default) (deny network*) (allow network-bind (local ip "localhost:*")) (allow network-inbound (local ip "localhost:*")) (allow network-outbound (remote ip "localhost:*"))'
const probeDiffs = {
  '--system-root-directory': '(allow file-read-data (literal "/"))',
}

export function versionProbeDiff(option) {
  assert(Object.hasOwn(probeDiffs, option))
  return probeDiffs[option]
}

export function safeNativeLines(output) {
  return String(output).split('\n').filter(line => /^(?:openjdk |java version |OpenJDK |Java\(TM\)|dyld\[\d+\]:|libc\+\+abi:|libsystem[^:]*:|  (?:Library not loaded|Referenced from|Reason|tried):|Error occurred during initialization of VM|Could not create the Java Virtual Machine|sandbox-exec:|# (?:A fatal error|SIG[A-Z]+|JRE version:|Java VM:|Problematic frame:))/.test(line))
    .slice(0, 16).map(line => line.replace(/\b0x[a-fA-F0-9]+\b/g, '<address>')
      .replace(/\b\d{4,}\b/g, '<number>').replace(/(?:file:\/\/)?\/(?:[^\s'"(),:]+\/?)+/g, '<path>')
      .slice(0, 1000))
}

async function run() {
  assert.equal(process.platform, 'darwin')
  assert(process.argv.length === 3 && ['--compare-version-only', '--strict-version-only', '--pid-denials', ...Object.keys(probeDiffs)].includes(process.argv[2]))
  const java = await realpath('/Applications/Knowledge Forge.app/Contents/Resources/resources/runtime/bin/java')
  const before = sha256(await readFile(java))
  const root = await realpath(await mkdtemp(join(tmpdir(), 'dougle-e50-java-')))
  await chmod(root, 0o700)
  const results = []
  try {
    for (const mode of process.argv[2] !== '--compare-version-only' ? ['strict-files'] : ['strict-files', 'network-only-control']) {
      const directory = join(root, mode), home = join(directory, 'home'), scratch = join(directory, 'tmp')
      for (const path of [home, scratch]) await mkdir(path, { recursive: true, mode: 0o700 })
      const profileDiff = Object.hasOwn(probeDiffs, process.argv[2]) ? versionProbeDiff(process.argv[2]) : ''
      const profile = (mode === 'strict-files' ? sandboxProfile(directory, dirname(dirname(java)), directory) : networkOnly) + ' ' + profileDiff
      let output, code = 0, signal = null, pid
      try {
        const pending = execute('/usr/bin/sandbox-exec', ['-p', profile, java, '-Djava.net.preferIPv4Stack=true',
          '-Duser.home=' + home, '-Djava.io.tmpdir=' + scratch, '-version'],
        { cwd: directory, env: childEnvironment(home, directory, scratch), timeout: 10000, killSignal: 'SIGKILL', maxBuffer: 65536 })
        pid = pending.child.pid
        output = await pending
      } catch (error) {
        output = error; code = Number.isInteger(error.code) ? error.code : null
        signal = ['SIGABRT', 'SIGKILL', 'SIGSEGV'].includes(error.signal) ? error.signal : null
      }
      results.push({ mode, profileDiff, code, signal, stdoutBytes: Buffer.byteLength(output.stdout ?? ''), stderrBytes: Buffer.byteLength(output.stderr ?? ''),
        diagnostics: safeNativeLines(String(output.stdout ?? '') + '\n' + String(output.stderr ?? '')) })
      if (process.argv[2] === '--pid-denials') {
        assert(Number.isInteger(pid) && pid > 0)
        const marker = 'java(' + pid + ')'
        const predicate = 'eventMessage CONTAINS "' + marker + '"'
        const capture = { pid, scope: 'exact-new-child-java-pid-denials', denials: [] }
        try {
          const logs = await execute('/usr/bin/log', ['show', '--last', '1m', '--style', 'ndjson', '--predicate', predicate],
            { cwd: directory, env: childEnvironment(home, directory, scratch), timeout: 10000, killSignal: 'SIGKILL', maxBuffer: 65536 })
          for (const line of logs.stdout.split('\n').filter(Boolean)) {
            let entry
            try { entry = JSON.parse(line) } catch { continue }
            const message = entry.eventMessage
            if (typeof message !== 'string' || !message.includes(marker)) continue
            const match = message.match(/deny\(\d+\) (file-[a-z-]+) (\/[^\r\n]+)/)
            if (!match) continue
            const resource = match[2].trim()
            const systemPath = /^\/(?:System\/|usr\/|bin\/|sbin\/|dev\/|private\/etc\/|private\/var\/db\/)/.test(resource)
            capture.denials.push({ operation: match[1], resource: systemPath ? resource.slice(0, 512) : '<non-system-path-withheld>' })
          }
        } catch { capture.error = 'PID_SCOPED_LOG_UNAVAILABLE' }
        results.at(-1).capture = capture
      }
    }
  } finally {
    await rm(root, { recursive: true, force: true })
  }
  const unchanged = sha256(await readFile(java)) === before
  console.log(JSON.stringify({ versionOnly: true, javaSha256: before, results, temporaryRemoved: true, javaUnchanged: unchanged }, null, 2))
  assert(unchanged)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  run().catch(() => { console.error('Version-only preflight failed; raw errors withheld'); process.exitCode = 1 })
}
