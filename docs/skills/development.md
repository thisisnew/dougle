# 개발

현재 변경의 진입점은 `skills/`, `skill-runtime/`, `scripts/install-skills.py`입니다. 이전 Java/React/Tauri 앱을 빌드하지 않습니다.

```sh
python3 scripts/build-skills.py
python3 scripts/build-skills.py --check
python3 -m unittest discover -s tests/skills -v
python3 scripts/package-skills.py
```

공통 실행 코드 `skill-runtime/dougle.py`와 계약 `tools.md`를 고친 뒤 build-skills로 각 스킬에 복사합니다. --check는 배포 사본과 원본의 바이트 일치를 검사합니다. 각 스킬은 독립적으로 설치 가능해야 합니다. AI별 설치 위치만 다르고 데이터 형식은 같습니다.

테스트는 임시 작업 공간과 HOME을 사용합니다. 외부 AI, 실제 회사 자료, 사용자 계정/DB는 사용하지 않습니다. 추가적인 실모델 검증은 기존 호출 예산/승인 상태를 따릅니다. 단위/도구 테스트를 회사 SSO나 실제 AI 의미 품질 검증으로 보고하지 않습니다.

새 패키지는 0.2.0 스킬 버전이며 0.1.x 데스크톱 자동 업데이트와 분리합니다. release/는 Git에 넣지 않고 GitHub Release 자산으로 제공합니다. 패키지는 두 스킬·설치 프로그램·최신 문서만 포함하며 앱 런타임/모델/사용자 데이터를 포함하지 않습니다. 제품 지원 대상은 M Mac이고 Intel 전용 분기나 패키지는 추가하지 않습니다.
