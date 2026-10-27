import { expect, it } from 'vitest'
import { sourceAddress, sourceKindForLocation } from './sourceKind'

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

it.each([
  'https://github.com/example/repo?tab=readme-ov-file',
  'https://github.com/example/repo#readme',
  'https://GITHUB.com/example/repo',
  'https://github.com:443/example/repo',
])('normalizes repository UI variation %s', location => {
  expect(sourceAddress('URL', location)).toEqual({ kind: 'GITHUB', location: 'https://github.com/example/repo' })
})

it.each([
  'https://github.com/example/repo?token=secret',
  'https://github.com/example/repo?tab=issues',
  'https://github.com:444/example/repo',
  'https://user@github.com/example/repo',
  'https://github.com/example/repo/blob/main/App.java#L12',
])('does not broaden scope or silently discard unsafe/unknown URL parts: %s', location => {
  expect(sourceAddress('GITHUB', location)).toEqual({ kind: 'URL', location })
})
