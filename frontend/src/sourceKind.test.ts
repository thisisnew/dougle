import { expect, it } from 'vitest'
import { sourceKindForLocation } from './sourceKind'

it.each([
  ['URL', 'https://github.com/example/repo', 'GITHUB'],
  ['URL', ' https://github.com/example/repo.git/ ', 'GITHUB'],
  ['GITHUB', 'https://example.org/page', 'URL'],
  ['GITHUB', 'https://github.com/example/repo/blob/main/App.java', 'URL'],
  ['GITHUB', 'https://github.com/example/repo/issues/1', 'URL'],
  ['GITHUB', 'https://github.com/example/repo/tree/main', 'URL'],
  ['URL', 'https://github.com.evil/example/repo', 'URL'],
  ['URL', 'https://github.com@example.org/example/repo', 'URL'],
  ['URL', 'https://github.com/example/../', 'URL'],
  ['LOCAL_FOLDER', '/tmp/example', 'LOCAL_FOLDER'],
  ['MYSQL', 'localhost', 'MYSQL'],
])('routes %s %s as %s', (kind, location, expected) => {
  expect(sourceKindForLocation(kind, location)).toBe(expected)
})
