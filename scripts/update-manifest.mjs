import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { readFile, writeFile, mkdir, stat } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const execute = promisify(execFile)
const version = JSON.parse(await readFile(resolve(root, 'desktop/package.json'), 'utf8')).version
assert.match(version, /^\d+\.\d+\.\d+$/)
const tag = `v${version}-preview.1`
const publish = process.argv.includes('--publish')
const release = publish ? JSON.parse((await execute('gh', ['api', `repos/thisisnew/dougle/releases/tags/${tag}`])).stdout) : null
if (release) { assert.equal(release.draft, false); assert.equal(release.tag_name, tag) }
const config = resolve(root, 'desktop/src-tauri/tauri.conf.json')
const platforms = {}
for (const [platform, architecture] of [['darwin-x86_64', 'x86_64'], ['darwin-aarch64', 'arm64']]) {
  const name = `Dougle_${version}_${architecture}.app.tar.gz`
  const archive = resolve(root, 'release', name)
  const signature = archive + '.sig'
  await execute('bash', ['scripts/desktop-cargo.sh', 'run', '--locked', '--example', 'updater_verify', '--', config, archive, signature, version], {
    cwd: root, env: { ...process.env, TAURI_CONFIG: '{"bundle":{"resources":[]}}', CARGO_BUILD_JOBS: '2' }, maxBuffer: 1024 * 1024,
  })
  if (release) {
    for (const filename of [name, name + '.sig', `Dougle_${version}_${architecture}.dmg`]) await verifyPublicAsset(filename)
  }
  platforms[platform] = { url: `https://github.com/thisisnew/dougle/releases/download/${tag}/${name}`,
    signature: (await readFile(signature, 'utf8')).trim() }
}
async function verifyPublicAsset(filename) {
  const asset = release.assets.find(asset => asset.name === filename)
  assert.ok(asset, `Missing public asset: ${filename}`)
  assert.equal(asset.state, 'uploaded')
  const path = resolve(root, 'release', filename)
  assert.equal(asset.size, (await stat(path)).size)
  const digest = createHash('sha256')
  for await (const chunk of createReadStream(path)) digest.update(chunk)
  assert.equal(asset.digest, 'sha256:' + digest.digest('hex'))
}
if (release) {
  // Never promote the fallback feed for a DMG-only or incomplete release again.
  await verifyPublicAsset('latest.json')
  await verifyPublicAsset('SHA256SUMS')
  const publishedManifest = JSON.parse(await readFile(resolve(root, 'release/latest.json'), 'utf8'))
  assert.equal(publishedManifest.version, version)
  assert.deepEqual(publishedManifest.platforms, platforms)
}
const manifest = { version, notes: '앱 메뉴에서 업데이트를 설치하고 다시 시작할 수 있습니다.',
  pub_date: release?.published_at ?? new Date().toISOString(), platforms }
const destination = resolve(root, publish ? 'updates/preview.json' : 'release/latest.json')
await mkdir(resolve(root, publish ? 'updates' : 'release'), { recursive: true })
await writeFile(destination, JSON.stringify(manifest, null, 2) + '\n')
console.log(publish ? 'Published-asset signatures and digests verified; preview manifest ready for commit.' : 'Signed update manifest generated in release/latest.json; public feed unchanged.')
