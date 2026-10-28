import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export function verifyBrand(config, desktop) {
  assert.equal(config.productName, 'Dougle')
  assert.equal(config.identifier, 'dev.dougle.desktop')
  assert.equal(desktop.name, 'dougle-desktop')
  assert.equal(config.version, desktop.version)
  assert(config.bundle.resources.includes('resources/dougle.jar'))
  assert(!JSON.stringify(config).match(/knowledge[ ._-]?forge/i))
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = new URL('../', import.meta.url)
  verifyBrand(JSON.parse(readFileSync(new URL('desktop/src-tauri/tauri.conf.json', root))),
    JSON.parse(readFileSync(new URL('desktop/package.json', root))))
  console.log('PASS: Dougle product, identifier, resources and versions agree')
}
