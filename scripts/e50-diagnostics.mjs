const sources = ['e50-independent-archive.mjs', 'e50-archive-fixtures.mjs', 'e50-diagnostics.mjs']
const operators = new Set(['==', '!=', '===', '!==', 'strictEqual', 'notStrictEqual', 'deepStrictEqual', 'notDeepStrictEqual', 'match', 'fail'])
const errors = new Set(['AssertionError', 'Error', 'TypeError', 'SyntaxError', 'AbortError', 'TimeoutError'])
const codes = new Set(['ENOENT', 'EACCES', 'EPERM', 'EPIPE', 'ECONNREFUSED', 'ERR_ASSERTION'])

export function safeError(error) {
  const frames = []
  for (const line of String(error?.stack ?? '').split('\n').slice(1, 30)) {
    for (const name of sources) {
      const prefix = new URL(name, import.meta.url).href + ':'
      const offset = line.indexOf(prefix)
      if (!/^\s+at /.test(line) || offset < 0) continue
      const location = line.slice(offset + prefix.length).match(/^(\d{1,7}):\d{1,7}\)?$/)
      if (location) frames.push({ file: name, line: Number(location[1]) })
    }
  }
  return { type: errors.has(error?.name) ? error.name : 'OtherError',
    operator: operators.has(error?.operator) ? error.operator : null,
    code: codes.has(error?.code) ? error.code : null, frames: frames.slice(0, 5) }
}

export function diagnostics() {
  const state = { checkpoint: 'preflight', steps: [], child: null, http: null }
  return { state, mark(name) {
    if (!/^[a-z][a-z0-9-]{0,63}$/.test(name)) throw new Error('Invalid diagnostic checkpoint')
    state.checkpoint = name
    if (state.steps.at(-1) !== name && state.steps.length < 128) state.steps.push(name)
  } }
}

export function startupClassifier() {
  let tail = ''
  const seen = new Set()
  const patterns = [
    ['SANDBOX_DIAGNOSTIC', /sandbox-exec:|sandbox_apply:/],
    ['PERMISSION_DENIED', /Operation not permitted|Permission denied|AccessDeniedException/],
    ['JVM_STARTUP_ERROR', /Error occurred during initialization of VM|Could not create the Java Virtual Machine/],
    ['JAR_UNAVAILABLE', /Unable to access jarfile/],
    ['APPLICATION_START_FAILED', /APPLICATION FAILED TO START|Application run failed/],
  ]
  return { accept(chunk) {
    tail = (tail + String(chunk)).slice(-8192)
    for (const [code, pattern] of patterns) if (pattern.test(tail)) seen.add(code)
  }, snapshot: () => [...seen].sort() }
}
