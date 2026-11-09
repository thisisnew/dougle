# 게시 구조 검사와 영구 식별자

게시 전 `scripts/check-publication.pl`을 실행합니다. 기존 민감정보 검사와 같은 표준 Perl 환경을 사용하며 봇·Python·서버 설치는 필요하지 않습니다. 검사 실패·실행 불가이면 게시하지 않습니다. PR은 만들지 않습니다.

## 영구 ID와 입력

기존 `C01`은 그 자체로 전역 ID가 아닙니다. 프로젝트 안에서 `<stable-source-id>/<original-claim-id>`로 한 번 자격을 부여하고 로컬 binding에 매핑을 저장합니다. 새 사실은 충돌 없는 ID를 한 번 발급한 뒤 재시도에 재사용합니다. 문장 해시·파일 경로·표현 변경으로 ID를 다시 발급하지 않습니다. 분할/통합 때 원래 ID와 의존관계를 남깁니다. 의미가 같은 듯한 두 ID를 자동 합치지 않습니다.

B(마지막 공통 기준), L(선택 로컬), R(고정 원격), F(병합 결과)를 각각 UTF-8 JSON으로 준비합니다. 처음 공유할 때만 빈 claims의 B를 사용합니다. 기존 Markdown 패키지는 원문·근거·조건을 읽어 보존한 projection을 최초 1회 만듭니다. 원격에 기존 자료가 있는데 빈 R로 가장하면 안 됩니다. projection의 누락은 자동으로 알아낼 수 없으므로 원문 대조 검토가 필수입니다. 기존 저장소를 이 기능 설치만으로 이전하지 않습니다.

```json
{"format":"dougle-publication/v1","project":"stable-project","files":{"topics/T001.md":"<sha256 of exact bytes>"},"claims":[{"id":"S001/C01","scope":"S001","core":{"statement":"승인 후 확정합니다.","scope":"S001","dependencies":[],"conditions":[],"exceptions":[],"provenance":"observed"},"dependencies":[],"evidence":[{"source":"S001","revision":"<original revision>","locator":"<original location>","provenance":"observed"}],"history":[],"alternatives":[],"status":"needs_recheck"}]}
```

core에는 사실의 모든 조건·예외·부정·순서·범위·철회 상태를 담습니다. 필드 이름의 순서만 정규화하며 배열 순서·숫자·단위·문장을 바꾸지 않습니다. 이 검사기의 자동 동등성은 정확한 core 일치입니다. Fact-v1의 의미 동등성 주장이 있어도 서로 다른 문장을 자동으로 동등 처리하지 않습니다. 검증된 표현 정리도 기존 core와 원문을 history에 남기는 명시적 변경으로 기록합니다.

status는 `needs_recheck`, `verified`, `unresolved`, `withdrawn`입니다. `verified`는 출처 검증을 완료한 동일 입력 레코드가 있는 경우만 유지할 수 있습니다. 새 결론을 게시 성공만으로 verified로 올리지 않습니다. 철회에는 core.withdrawal_reason이 필요하며 근거와 과거 결론은 삭제하지 않습니다. 기존 충돌의 자동 해결은 지원하지 않습니다. 별도의 원문 재검증·정정과 충돌 이력을 갖춘 수동 해결 절차가 필요합니다.

## 실행과 게시

F는 로컬 JSON 입력입니다. stage/publication.md는 정확히 `# Dougle publication\n\n` 뒤에 json 코드 펜스를 열고 F의 원래 바이트를 넣은 다음 `\n`과 닫는 코드 펜스, 마지막 `\n`을 붙여 만듭니다. F의 공백/개행까지 보존합니다. 이 Markdown 포장으로 기존 Markdown-only 민감정보 검사와 fetch 경계를 유지합니다. files에는 stage의 모든 일반 파일의 상대경로와 SHA256을 넣되 `publication.md`과 `publication-ledger.md`만 제외합니다. 비밀정보·절대 로컬 경로는 projection에도 담지 않습니다.

```sh
perl <installed-skill>/scripts/check-publication.pl B.json L.json R.json F.json <stage> > <temporary-receipt>
```

성공 종료를 확인한 뒤 receipt를 stage/publication-ledger.md으로 복사합니다. stdout은 json 코드 펜스를 가진 Markdown이며 이번 입력·결과 해시와 ID별 결정 코드입니다. 실패한 출력을 receipt로 사용하지 않습니다. 두 Markdown 파일은 패키지와 같은 Git commit에 게시하므로 Git 이력이 변경 내역을 보존합니다. 이전 원격 publication.md과 원격 commit을 로컬 작업 기록에 저장합니다. stage가 바뀌면 projection·검사·receipt를 다시 만듭니다.

그다음 전체 stage의 의미·공개 범위 검토와 민감정보 검사를 수행하고, R의 원격 head를 expected head로 사용하는 원자적 commit/정상 fast-forward push를 합니다. 원격이 이동하면 보존한 L과 새 R로 다시 병합·검사합니다. 개별 파일 SHA 검사만으로 패키지를 게시하지 않습니다. 원격 read-back과 로컬 병합 완료 뒤에만 공통 B를 전진시킵니다.

검사는 누락 ID, 범위 충돌, 근거/과거 결론/충돌 대안 유실, 틀린 3-way 결과, 빠진 의존관계, 부당한 verified 승격, 검사 대상과 stage 바이트 불일치를 차단합니다. 원문의 진실성·의미 동등성·원격 head 일치·민감정보는 이 검사만으로 증명되지 않습니다. 주변 조건·의존 원본이 바뀌면 해당 사실도 재확인해야 하며, 발췌 해시가 같다고 최신성을 자동 연장하지 않습니다.
