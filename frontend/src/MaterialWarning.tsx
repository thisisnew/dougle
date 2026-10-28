import { CircleAlert } from 'lucide-react'
import { useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

export function shortMaterialError(error?: string | null) {
  if (/개 파일을 읽지 못했습니다/.test(error || '')) return '일부 파일을 읽지 못했습니다. 이전 자료는 유지됩니다.'
  if (/폴더 깊이|지원하지 않는 파일 경로/.test(error || '')) return '처리할 수 없는 폴더 깊이 또는 파일 이름입니다.'
  if (/심볼릭 링크|특수 파일/.test(error || '')) return '바로가기·특수 파일은 수집하지 않습니다.'
  if (/Jira.*Confluence.*연결/.test(error || '')) return 'Jira · Confluence 연결이 필요합니다.'
  if (/로컬 폴더 수집 실패/.test(error || '')) return '로컬 폴더를 읽지 못했습니다.'
  if (/개 파일 확인이 필요/.test(error || '')) return '일부 파일을 확인해 주세요.'
  if (/텍스트가 길어|큰 소스 파일|Java 구문/.test(error || '')) return '자료의 일부만 분석했습니다.'
  if (/256 MiB/.test(error || '')) return '파일당 256 MiB 처리 한도를 초과했습니다.'
  if (/Notion 페이지 접근/.test(error || '')) return 'Notion 페이지 접근 권한이 없습니다.'
  if (/Notion.*너무/.test(error || '')) return 'Notion 페이지 수집 한도를 초과했습니다.'
  if (/Notion 요청 한도/.test(error || '')) return 'Notion 요청이 많습니다. 잠시 후 다시 시도해 주세요.'
  if (/MySQL 연결이 필요/.test(error || '')) return 'MySQL 연결이 필요합니다.'
  if (/MySQL 인증 실패/.test(error || '')) return 'MySQL 인증에 실패했습니다.'
  if (/MySQL 연결 실패/.test(error || '')) return 'MySQL에 연결할 수 없습니다.'
  if (/DB 열|DB 구조/.test(error || '')) return 'DB 구조 수집 한도를 초과했습니다.'
  if (/Notion 연결/.test(error || '')) return 'Notion 연결이 필요합니다.'
  if (!error) return '자료를 가져오지 못했습니다.'
  if (/일부 파일/.test(error)) return '일부 파일을 읽지 못했습니다.'
  if (/추출 가능한 텍스트|텍스트가 없/.test(error)) return '읽을 수 있는 텍스트가 없습니다.'
  if (/텍스트 추출 실패/.test(error)) return '파일 내용을 읽지 못했습니다.'
  if (/\b404\b|not found|찾을 수 없|존재하지 않/i.test(error)) return '자료를 찾을 수 없습니다.'
  if (/\b40[13]\b|권한|permission|denied|인증/i.test(error)) return '접근 권한을 확인해 주세요.'
  if (/timeout|timed out|시간.*초과/i.test(error)) return '연결 시간이 초과되었습니다.'
  if (/\b429\b|too many/i.test(error)) return '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
  if (/10 MiB/.test(error)) return '파일당 10 MiB 제한을 초과했습니다.'
  if (/500개/.test(error)) return '폴더·저장소당 500개 파일 제한을 초과했습니다.'
  if (/50 MiB/.test(error)) return '폴더·저장소당 총 50 MiB 제한을 초과했습니다.'
  if (/\b5\d\d\b/.test(error)) return '상대 서버가 응답하지 않습니다.'
  if (/크기|MiB|개 파일|용량|총 .*제한/.test(error)) return '자료 크기 또는 개수 제한을 초과했습니다.'
  if (/실제 폴더|디렉터리|directory/i.test(error)) return '폴더 위치를 확인해 주세요.'
  if (/HTTPS|URL|주소/.test(error)) return '자료 주소를 확인해 주세요.'
  if (/network|connect|UnknownHost|네트워크|연결/i.test(error)) return '네트워크 연결을 확인해 주세요.'
  if (/암호|encrypted|password/i.test(error)) return '암호로 보호된 자료입니다.'
  return '자료를 읽지 못했습니다. 다시 동기화해 주세요.'
}

export function materialErrorHelp(error?: string | null, kind?: string) {
  if (/개 파일 확인이 필요|개 파일을 읽지 못했습니다/.test(error || '')) return error!.slice(0, 500)
  if (/폴더 깊이|지원하지 않는 파일 경로/.test(error || '')) return '해당 하위 폴더를 별도로 연결하거나 파일 이름을 확인하세요. 정상 파일은 계속 수집합니다.'
  if (/심볼릭 링크|특수 파일/.test(error || '')) return '바로가기 대신 원본 파일이 있는 실제 폴더를 연결하세요. 다른 정상 파일은 계속 수집합니다.'
  if (/로컬 폴더 수집 실패|macOS/.test(error || '')) return error!.slice(0, 500)
  if (/개 파일 확인이 필요|텍스트가 길어|큰 소스 파일|Java 구문/.test(error || '')) return error!.slice(0, 500)
  if (/개 파일을 읽지 못했습니다/.test(error || '')) return error!.slice(0, 500)
  if (/256 MiB/.test(error || '')) return '256 MiB가 넘는 원본은 더 작은 파일로 나눠 등록해 주세요.'
  if (/20,000|8 GiB/.test(error || '')) return '필요한 하위 폴더로 나눠 등록해 주세요. 자료당 20,000개 파일·8 GiB까지 처리합니다.'
  if (/Jira.*Confluence.*연결/.test(error || '')) return '설정 → 플러그인에서 Jira · Confluence를 연결해 주세요.'
  if (/Notion 페이지 접근/.test(error || '')) return 'Notion 페이지의 ⋯ → 연결에서 해당 연결을 추가한 뒤 동기화하세요.'
  if (/Notion.*너무/.test(error || '')) return '더 작은 하위 페이지의 링크를 등록해 주세요.'
  if (/Notion 요청 한도/.test(error || '')) return '잠시 기다린 뒤 동기화를 다시 눌러 주세요.'
  if (/MySQL 연결이 필요/.test(error || '')) return '플러그인에서 해당 데이터베이스를 연결해 주세요.'
  if (/MySQL 인증 실패/.test(error || '')) return '사용자 ID·비밀번호·접속 허용 호스트를 확인하세요.'
  if (/MySQL 연결 실패/.test(error || '')) return '호스트·포트·방화벽·TLS 인증서를 확인하세요.'
  if (/DB 열|DB 구조/.test(error || '')) return '5,000열 이하의 더 작은 데이터베이스를 연결하세요.'
  if (/Notion 연결/.test(error || '')) return '플러그인에서 Notion을 연결해 주세요.'
  if (/10 MiB/.test(error || '')) return '파일을 10 MiB 이하로 줄이거나 나눠 다시 추가해 주세요.'
  if (/500개|500.*파일|50 MiB/.test(error || '')) return '이전 수집 한도입니다. 앱을 업데이트하고 다시 동기화해 주세요.'
  if (/크기|개수|용량|총 .*제한/.test(error || '')) return '필요한 파일만 모은 작은 폴더로 나눠 추가해 주세요. 파일당 256 MiB, 자료당 20,000개·총 8 GiB까지 지원합니다.'
  if (/추출 가능한 텍스트|텍스트가 없|텍스트 추출/.test(error || '')) return '텍스트가 선택되는 PDF나 문서로 변환해 다시 추가해 주세요.'
  if (/404|not found|찾을 수 없|존재하지 않|디렉터리|directory/i.test(error || '')) return '이동·삭제된 자료인지 확인하고, 위치가 바뀌었다면 삭제 후 다시 추가해 주세요.'
  if (/40[13]|권한|permission|denied|인증/i.test(error || '')) return kind === 'LOCAL_FOLDER' ? 'macOS 시스템 설정 → 개인정보 보호 및 보안 → 파일 및 폴더에서 Dougle의 접근 권한과 원본 폴더의 읽기 권한을 확인하세요.' : '자료 접근 권한을 확인하고, 인증이 필요한 문서는 해당 플러그인을 연결해 주세요.'
  if (/암호|encrypted|password/i.test(error || '')) return '암호를 해제한 복사본을 다시 추가해 주세요.'
  if (/HTTPS|URL|주소/.test(error || '')) return '브라우저에서 열리는 HTTPS 주소를 복사해 다시 추가해 주세요.'
  if (kind === 'LOCAL_FOLDER') return '폴더 위치, macOS 시스템 설정의 Dougle 파일 및 폴더 접근 권한과 로컬 저장 공간을 확인하세요. 로컬 폴더 수집에는 인터넷이나 AI 연결이 필요하지 않습니다.'
  return '인터넷 연결과 자료 위치를 확인한 뒤 동기화를 다시 눌러 주세요.'
}

export default function MaterialWarning({ name, error, kind, label = '동기화 실패', onAction }: { name: string; error?: string | null; kind?: string; label?: string; onAction?: () => void }) {
  const reason = shortMaterialError(error)
  const id = useId()
  const anchor = useRef<HTMLButtonElement>(null)
  const tooltip = useRef<HTMLSpanElement>(null)
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState({ left: 8, top: 0, above: false })
  useLayoutEffect(() => {
    if (!open) return
    function place() {
      const bounds = anchor.current?.getBoundingClientRect()
      if (!bounds) return
      const height = tooltip.current?.offsetHeight || 80
      const width = tooltip.current?.offsetWidth || 260
      const above = window.innerHeight - bounds.bottom < height + 16 && bounds.top > height + 16
      setPosition({ left: Math.max(8, Math.min(bounds.left, window.innerWidth - width - 8)), top: above ? bounds.top - 8 : Math.max(8, Math.min(bounds.bottom + 8, window.innerHeight - height - 8)), above })
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true) }
  }, [open, reason, error])
  return <><button ref={anchor} type="button" className="material-warning" data-action={!!onAction} aria-label={`${name} ${label}: ${reason}`} aria-describedby={open ? id : undefined}
    onMouseEnter={() => setOpen(true)} onMouseLeave={() => { if (document.activeElement !== anchor.current) setOpen(false) }}
    onFocus={() => setOpen(true)} onBlur={() => setOpen(false)} onClick={() => { if (onAction) onAction(); else setOpen(true) }} onKeyDown={event => { if (event.key === 'Escape') setOpen(false) }}>
    <CircleAlert size={17} aria-hidden="true" />
  </button>{open && createPortal(<span ref={tooltip} id={id} role="tooltip" className={`material-error-tooltip${position.above ? ' above' : ''}`} style={{ left: position.left, top: position.top }}><strong>{reason}</strong><span>{materialErrorHelp(error, kind)}</span></span>, document.body)}</>
}
