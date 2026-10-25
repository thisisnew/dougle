import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { copyFile, lstat, mkdir, open, rename, rm, stat } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const architecture = process.argv[2]
if (!['x86_64', 'arm64'].includes(architecture)) throw new Error('Expected x86_64 or arm64')
const destination = resolve(process.argv[3] || resolve(root, 'desktop/src-tauri/resources/search-model-e5-v1'))
const verifyOnly = process.argv[4] === '--verify-only'
const cache = resolve(root, '.tools/search-model', architecture)
const embedding = 'https://huggingface.co/intfloat/multilingual-e5-small/resolve/614241f622f53c4eeff9890bdc4f31cfecc418b3/onnx/'
const reranker = 'https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1/resolve/1427fd652930e4ba29e8149678df786c240d8825/'
const assets = [
  ['model.onnx', embedding + 'model_qint8_avx512_vnni.onnx', 118346824, 'dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88'],
  ['tokenizer.json', embedding + 'tokenizer.json', 17082730, '0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39'],
  architecture === 'arm64'
    ? ['reranker.onnx', reranker + 'onnx/model_qint8_arm64.onnx', 118620017, '1825907d6c1a9001ff78124780bbde20a614a8c3df3b63409cf3c72c6fe5c8b4']
    : ['reranker.onnx', reranker + 'onnx/model_quint8_avx2.onnx', 118620016, '6c2513767fb63d008a4377bef7a7a3555433d9436342bb53e35a3a72ffc52d4b'],
  ['reranker-tokenizer.json', reranker + 'tokenizer.json', 17082660, '62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626'],
]
async function valid(file, size, hash) {
  const metadata = await lstat(file).catch(() => null)
  if (!metadata?.isFile() || metadata.size !== size) return false
  const digest = createHash('sha256')
  for await (const chunk of createReadStream(file)) digest.update(chunk)
  return digest.digest('hex') === hash
}
for (const directory of verifyOnly ? [destination] : [cache, destination]) {
  if ((await lstat(directory).catch(() => null))?.isSymbolicLink()) throw new Error('Refusing linked model directory')
  if (!verifyOnly) await mkdir(directory, { recursive: true })
}
for (const [name, url, size, hash] of assets) {
  if (verifyOnly) {
    if (!await valid(resolve(destination, name), size, hash)) throw new Error(`Bundled model failed verification: ${name}`)
    continue
  }
  const file = resolve(cache, name)
  if (!await valid(file, size, hash)) {
    const temporary = file + '.part'
    await rm(temporary, { force: true })
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(900000) })
      if (!response.ok || !response.body) throw new Error(`Model download failed: ${name}`)
      const output = await open(temporary, 'wx', 0o600)
      let downloaded = 0
      try {
        for await (const chunk of response.body) {
          downloaded += chunk.length
          if (downloaded > size) throw new Error('Unexpected model size')
          await output.writeFile(chunk)
        }
      } finally { await output.close() }
      if (!await valid(temporary, size, hash)) throw new Error(`Model integrity check failed: ${name}`)
      await rename(temporary, file)
    } finally { await rm(temporary, { force: true }) }
  }
  const target = resolve(destination, name)
  if ((await lstat(target).catch(() => null))?.isSymbolicLink()) throw new Error('Refusing linked model file')
  await copyFile(file, target)
  if (!await valid(target, size, hash)) throw new Error(`Staged model failed verification: ${name}`)
}
for (const name of ['NOTICE.md', 'MIT.txt', 'Apache-2.0.txt']) {
  const target = resolve(destination, name)
  if ((await lstat(target).catch(() => null))?.isSymbolicLink()) throw new Error('Refusing linked model notice')
  if (!verifyOnly) await copyFile(resolve(root, 'scripts/search-model-licenses', name), target)
  {
    const expected = createHash('sha256')
    const actual = createHash('sha256')
    for await (const chunk of createReadStream(resolve(root, 'scripts/search-model-licenses', name))) expected.update(chunk)
    for await (const chunk of createReadStream(target)) actual.update(chunk)
    if (expected.digest('hex') !== actual.digest('hex')) throw new Error(`Bundled notice failed verification: ${name}`)
  }
}
console.log(`Verified bundled search engine: ${architecture}, ${(await Promise.all(assets.map(async ([name]) => (await stat(resolve(destination, name))).size))).reduce((sum, size) => sum + size, 0)} bytes`)
