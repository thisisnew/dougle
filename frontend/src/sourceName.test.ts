import { expect, it } from 'vitest'
import { sourceName } from './sourceName'

it.each([
  ['LOCAL_FOLDER', '/synthetic/연구 자료/', '연구 자료'],
  ['LOCAL_FOLDER', '/', '폴더'],
  ['URL', 'https://example.com', 'example.com'],
  ['URL', 'https://example.com/%ED%9A%8C%EC%9D%98?key=secret#note', '회의'],
  ['URL', 'https://example.com/%broken', '%broken'],
  ['URL', 'https://example.com/%2f%5c%00', '웹 링크'],
  ['GITHUB', 'https://github.com/example/project.git/', 'project'],
])('주소에서 이름을 만들되 경로·제어문자·쿼리를 노출하지 않는다: %s %s', (kind, location, expected) => {
  expect(sourceName(kind, location)).toBe(expected)
})

it('긴 이름을 서버 제한에 맞추고 유니코드 문자를 자르지 않는다', () => {
  const result = sourceName('LOCAL_FOLDER', '/' + '가'.repeat(119) + '🔍')
  expect(result).toBe('가'.repeat(119))
})

it('잘못된 URL은 등록 전에 거부한다', () => {
  expect(() => sourceName('URL', 'not a url')).toThrow()
})
