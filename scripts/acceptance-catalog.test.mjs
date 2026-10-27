import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { buildCatalog, validateCatalog, renderMarkdown } from './acceptance-catalog.mjs'

test('catalog contains exactly 1000 unique, fully specified, unexecuted cases', () => {
  assert.equal(validateCatalog(buildCatalog()), true)
})

test('missing, duplicate or claimed-passing cases are rejected', () => {
  const missing = buildCatalog(); missing.cases.pop()
  assert.throws(() => validateCatalog(missing))
  const duplicate = buildCatalog(); duplicate.cases[1] = duplicate.cases[0]
  assert.throws(() => validateCatalog(duplicate))
  const promoted = buildCatalog(); promoted.cases[0].status = 'PASS'
  assert.throws(() => validateCatalog(promoted))
  const incomplete = buildCatalog(); incomplete.cases[0].expected = []
  assert.throws(() => validateCatalog(incomplete))
})

test('catalog includes mismatched tabs, detail links, risky addresses and semantic failures', () => {
  const cases = buildCatalog().cases
  const routing = cases.filter(item => item.suite === 'R01')
  assert(routing.some(item => item.steps[1].includes('웹 링크')))
  assert(routing.some(item => item.steps[1].includes('GitHub에서')))
  assert(cases.some(item => item.fixture.includes('/issues/12') && item.expected[0].includes('clone0회')))
  assert(cases.some(item => item.fixture.startsWith('javascript:') && item.expected[0].includes('거부')))
  assert(cases.some(item => item.fixture.includes('선택 알고리즘을 외부')))
  assert(cases.some(item => item.fixture.includes('모든 팀에 한 방법론')))
  assert(cases.some(item => item.fixture.includes('Rosetta 없음')))
  assert(cases.some(item => item.fixture.includes('Slack 채널에 답글')))
})

test('committed catalog and markdown exactly match deterministic generator', async () => {
  const expected = buildCatalog()
  const json = JSON.parse(await readFile(new URL('../docs/acceptance-1000.json', import.meta.url), 'utf8'))
  const markdown = await readFile(new URL('../docs/acceptance-1000.md', import.meta.url), 'utf8')
  assert.deepEqual(json, expected)
  assert.equal(markdown, renderMarkdown(expected))
  assert.equal(markdown.match(/^\| T\d{4} \|/gm).length, 1000)
})
