# CLUE — 자료에서 단서를 찾는 앱

앱의 새 이름은 **CLUE**입니다. 화면에는 짧은 이름과 돋보기를 든 탐정 캐릭터만 표시합니다. 기존의 작은 소개 문구는 제거했습니다.

## 캐릭터

- 안경 없는 꼬마 탐정, 단정한 검은 앞머리·부드러운 얼굴·네이비 옷. 돋보기를 한쪽 눈에 댑니다.
- 프로필 사진은 분위기만 참고했습니다. 실제 초상화를 복제하지 않았습니다.
- 원본 사진은 앱·저장소·배포 파일에 포함하지 않습니다.
- 같은 그림을 사이드바, Dock 아이콘, GitHub 소개에 사용합니다.
- 생성 원본: `desktop/src-tauri/icons/icon.png`; 화면용: `frontend/src/assets/clue-mascot.png`.
- 내장 imagegen으로 생성하고 Tauri icon 도구로 플랫폼 크기를 만들었습니다. 상표 등록·독점 사용 가능성을 확인했다는 뜻은 아닙니다.

## 글자 로고

CLUE는 장식과 넓은 자간을 줄인 산세리프 워드마크입니다. 본문과 달리 Mac의 Helvetica Neue를 우선 사용하고, 24px·500 굵기·좁은 자간으로 조정했습니다. 캐릭터는 44px 정사각형으로 유지해 글자와 역할을 나눕니다. 외부 폰트 다운로드 없이 오프라인에서도 표시합니다.

## 기존 자료는 그대로

화면·창·업데이트 안내·macOS 표시 이름만 CLUE로 바꿉니다. 기존 업데이트와 설치 경로를 유지하기 위해 번들 파일명 `Knowledge Forge.app`, 배포 파일 접두사 `Knowledge.Forge`, 저장소 URL, 앱 ID `dev.knowledgeforge.desktop`은 유지합니다. DB·로그인 위치·서명 키·업데이트 주소는 변경하지 않습니다. 이전 사용자가 자료를 옮기거나 다시 등록할 필요는 없습니다.

`App.tsx`는 생성한 그림을 정적 자산으로 불러옵니다. `Info.plist`는 macOS 표시 이름을 지정하고, `main.rs`·`updates.rs`는 창/알림 제목을 지정합니다. 원본 사진을 런타임에 읽거나 보내는 코드는 없습니다.

## 생성 프롬프트

내장 imagegen, 기존 캐릭터는 스타일 참고·사용자 사진은 느슨한 인상 참고로 사용했습니다.

최종 수정 방향(프롬프트 요약):

> Refine the CLUE detective using the supplied photo loosely: neat rounded black fringe, soft oval face, calm approachable expression, navy jacket. Hold a single magnifying glass directly over one eye. No eyeglasses. Keep polished compact 2D pixel-art styling, pale lavender background and clear small-icon silhouette; no text or extra decorations.

검증·실제 설치 및 배포 상태는 [0.1.11 기록](release-0.1.11-report.md)을 확인하세요.
