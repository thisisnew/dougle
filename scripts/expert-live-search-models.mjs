import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { constants } from 'node:fs'
import { lstat, open, realpath } from 'node:fs/promises'
import { resolve } from 'node:path'

export function modelArchitecture(architecture) {
  assert.ok(['x64', 'arm64'].includes(architecture), 'Unsupported evaluation model architecture')
  return architecture === 'x64' ? 'x86_64' : 'arm64'
}

export function searchModelPath(root, explicit, architecture = process.arch) {
  const selected = modelArchitecture(architecture)
  assert.ok(explicit === undefined || typeof explicit === 'string' && explicit.trim().length > 0, 'Empty model override')
  return resolve(root, explicit ?? `.tools/search-model/${selected}`)
}

export function modelManifest(architecture = process.arch) {
  const selected = modelArchitecture(architecture)
  return [
    { name: 'model.onnx', size: 118346824, sha256: 'dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88' },
    { name: 'tokenizer.json', size: 17082730, sha256: '0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39' },
    selected === 'arm64'
      ? { name: 'reranker.onnx', size: 118620017, sha256: '1825907d6c1a9001ff78124780bbde20a614a8c3df3b63409cf3c72c6fe5c8b4' }
      : { name: 'reranker.onnx', size: 118620016, sha256: '6c2513767fb63d008a4377bef7a7a3555433d9436342bb53e35a3a72ffc52d4b' },
    { name: 'reranker-tokenizer.json', size: 17082660, sha256: '62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626' },
  ]
}

export async function verifyModelFiles(directory, manifest) {
  assert.ok((await lstat(directory)).isDirectory(), 'Model path must be an unlinked directory')
  const canonical = await realpath(directory)
  for (const asset of manifest) {
    assert.match(asset.name, /^[a-z0-9.-]+$/)
    const path = resolve(canonical, asset.name)
    assert.ok((await lstat(path)).isFile(), `Missing regular model file: ${asset.name}`)
    const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW)
    try {
      const before = await file.stat()
      assert.ok(before.isFile() && before.size === asset.size, `Model size mismatch: ${asset.name}`)
      const hash = createHash('sha256')
      for await (const chunk of file.createReadStream({ autoClose: false })) hash.update(chunk)
      const after = await file.stat()
      const current = await lstat(path)
      for (const field of ['dev', 'ino', 'size', 'mtimeMs', 'ctimeMs']) {
        assert.equal(after[field], before[field], 'Model changed during verification')
        assert.equal(current[field], before[field], 'Model replaced during verification')
      }
      assert.equal(hash.digest('hex'), asset.sha256, `Model checksum mismatch: ${asset.name}`)
    } finally { await file.close() }
  }
  return canonical
}

export async function verifySearchModels(root, explicit, architecture = process.arch) {
  const files = modelManifest(architecture)
  const directory = await verifyModelFiles(searchModelPath(root, explicit, architecture), files)
  return { directory, architecture: modelArchitecture(architecture), files, verifiedAt: new Date().toISOString() }
}
