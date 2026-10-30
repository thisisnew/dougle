import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import userEvent from '@testing-library/user-event'
import MaterialWarning, { materialErrorHelp, shortMaterialError } from './MaterialWarning'

it('explains local folder failures without suggesting a network dependency', () => {
  expect(materialErrorHelp('unknown', 'LOCAL_FOLDER')).toContain('로컬 폴더 수집에는 인터넷이나 AI 연결이 필요하지 않습니다')
  expect(shortMaterialError('폴더 깊이는 16단계 이하여야 합니다.')).toContain('폴더 깊이')
  expect(materialErrorHelp('심볼릭 링크·특수 파일')).toContain('실제 폴더')
  expect(materialErrorHelp('AccessDeniedException', 'LOCAL_FOLDER')).toContain('macOS 시스템 설정')
  expect(materialErrorHelp('AccessDeniedException', 'LOCAL_FOLDER')).not.toContain('링크')
  expect(materialErrorHelp('1개 파일을 읽지 못했습니다. shortcut: 심볼릭 링크는 읽지 않습니다.', 'LOCAL_FOLDER')).toContain('shortcut')
})

it.each([
  ['HTTP 404 /private/path', '자료를 찾을 수 없습니다.'],
  ['HTTP 403', '접근 권한을 확인해 주세요.'],
  ['connection timeout', '연결 시간이 초과되었습니다.'],
  ['텍스트 추출 실패: InternalException', '파일 내용을 읽지 못했습니다.'],
  ['추출 가능한 텍스트가 없습니다.', '읽을 수 있는 텍스트가 없습니다.'],
  ['일부 파일 오류', '일부 파일을 읽지 못했습니다.'],
  ['파일당 수집 한도 10 MiB를 초과했습니다.', '파일당 10 MiB 제한을 초과했습니다.'],
  ['폴더의 파일 수가 500개를 초과했습니다.', '폴더·저장소당 500개 파일 제한을 초과했습니다.'],
  ['Source 총 50 MiB 제한을 초과했습니다.', '폴더·저장소당 총 50 MiB 제한을 초과했습니다.'],
  ['internal stack trace /private/path', '자료를 읽지 못했습니다. 다시 동기화해 주세요.'],
])('오류를 짧은 사용자 안내로 변환한다: %s', (error, expected) => {
  expect(shortMaterialError(error)).toBe(expected)
})

it('용량 제한의 수치와 해결 방법을 제공하고 서버 오류와 구분한다', () => {
  expect(materialErrorHelp('파일 크기 또는 개수 제한을 초과했습니다.')).toContain('작은 폴더로 나눠')
  expect(materialErrorHelp('파일 크기 또는 개수 제한을 초과했습니다.')).toContain('20,000개·총 8 GiB')
  expect(materialErrorHelp('HTTP 500')).not.toContain('500개')
})

it('부분 추출은 재시도 안내 대신 보존한 범위와 조치 방법을 표시한다', () => {
  const detail = '텍스트가 길어 앞부분만 검색합니다. 파일을 나눠 등록하세요.'
  expect(shortMaterialError(detail)).toBe('자료의 일부만 분석했습니다.')
  expect(materialErrorHelp(detail)).toBe(detail)
  expect(materialErrorHelp(`1개 파일 확인이 필요합니다. notes.txt: ${detail}`)).toContain(detail)
})

it('마우스와 키보드로 실제 툴팁을 열고 Escape로 닫는다', async () => {
  render(<MaterialWarning name="가상 자료" error="HTTP 404 /private/path" />)
  const warning = screen.getByRole('button', { name: '가상 자료 동기화 실패: 자료를 찾을 수 없습니다.' })
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  await userEvent.hover(warning)
  expect(screen.getByRole('tooltip')).toHaveTextContent('자료를 찾을 수 없습니다.')
  expect(screen.getByRole('tooltip').parentElement).toBe(document.body)
  expect(warning).toHaveAttribute('aria-describedby', screen.getByRole('tooltip').id)
  await userEvent.unhover(warning)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  await userEvent.tab()
  expect(screen.getByRole('tooltip')).toBeInTheDocument()
  await userEvent.keyboard('{Escape}')
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  await userEvent.click(warning)
  expect(screen.getByRole('tooltip')).toBeInTheDocument()
})

it.each([404, 500, 502])('서비스 연결 HTTP %s를 삭제된 자료로 안내하지 않는다', status => {
  const message = `서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요. (HTTP ${status})`
  expect(shortMaterialError(message)).toBe('자료 서비스에 연결하지 못했습니다.')
  expect(materialErrorHelp(message)).toContain(`HTTP ${status}`)
  expect(materialErrorHelp(message)).not.toContain('삭제')
})
