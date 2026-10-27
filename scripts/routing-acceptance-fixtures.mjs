import { buildCatalog } from './acceptance-catalog.mjs'

export function routingFixtures() {
  const catalog = buildCatalog()
  return Array.from({ length: 20 }, (_, index) => {
    const cases = catalog.cases.slice(index * 5, index * 5 + 5)
    const input = cases[0].fixture
    const expectedKind = index < 5 ? 'GITHUB' : index < 11 ? 'URL' : index < 15 ? 'GITHUB' : null
    const expectedLocation = index >= 11 && index < 15 ? 'https://github.com/example/repo' : input.trim()
    return {
      suite: cases[0].suite,
      fixture: cases[0].key.split('-A')[0], input, expectedKind,
      expectedLocation: expectedKind ? expectedLocation : null,
      caseIds: Object.fromEntries(['web', 'github', 'roundtrip', 'api', 'retry'].map((name, offset) => [name, cases[offset].id])),
    }
  })
}
