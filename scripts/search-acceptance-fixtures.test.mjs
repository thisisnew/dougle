import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { searchAcceptanceFixtures } from './search-acceptance-fixtures.mjs'
import { buildCatalog } from './acceptance-catalog.mjs'

test('search fixtures cover exactly T0601–T0750 with five actions and stable hashes', () => {
  const fixtures = searchAcceptanceFixtures()
  assert.deepEqual(fixtures.map(item => item.id), buildCatalog().cases.slice(600, 750).map(item => item.id))
  for (let offset = 0; offset < fixtures.length; offset += 5) {
    assert.deepEqual(fixtures.slice(offset, offset + 5).map(item => item.action), [0, 1, 2, 3, 4])
    assert.equal(new Set(fixtures.slice(offset, offset + 5).map(item => item.fixtureHash)).size, 1)
  }
  assert.deepEqual(JSON.parse(readFileSync(new URL('../docs/fixtures/search-acceptance.json', import.meta.url))), fixtures)
})

test('positive ground truth references actual fixture paths and fragments', () => {
  for (const fixture of searchAcceptanceFixtures()) {
    for (const path of fixture.paths) assert(fixture.documents.some(document => document.path === path))
    for (const fragment of fixture.fragments) assert(fixture.documents.some(document => document.text.includes(fragment)))
    assert.equal(fixture.status, undefined)
  }
})
