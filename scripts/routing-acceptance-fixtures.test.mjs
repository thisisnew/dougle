import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { routingFixtures } from './routing-acceptance-fixtures.mjs'

test('routing fixtures bind all first 100 case IDs exactly once to independent expected inputs', async () => {
  const fixtures = routingFixtures()
  assert.equal(fixtures.length, 20)
  const ids = fixtures.flatMap(item => Object.values(item.caseIds))
  assert.equal(new Set(ids).size, 100)
  assert.deepEqual(ids, Array.from({ length: 100 }, (_, index) => 'T' + String(index + 1).padStart(4, '0')))
  assert.equal(fixtures.filter(item => item.expectedKind === null).length, 5)
  assert.equal(fixtures[10].expectedKind, 'URL')
  assert.equal(fixtures[11].expectedKind, 'GITHUB')
  assert.equal(fixtures[12].expectedLocation, 'https://github.com/example/repo')
  assert.deepEqual(JSON.parse(await readFile(new URL('../docs/fixtures/routing-acceptance.json', import.meta.url), 'utf8')), fixtures)
})
