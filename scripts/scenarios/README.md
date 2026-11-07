# 시나리오 평가 도구

개발 전용입니다. 사용자 스킬에는 포함되지 않습니다. 실제 AI 호출은 사전 승인된 평가에서만 실행합니다. 회사 서비스·사용자 지식 대신 합성 자료와 격리 MCP를 사용합니다.

- `run_*_matrix.py`: Codex CLI와 fake MCP로 연결·질문·갱신·읽기 실행
- `grade_connect.py`, `grade_host.py`: 도구 기록·저장 결과 검사
- `merge_model.py`: 독립 병합 정답과 최초·반복·순서 반전 비교
- `policy_decisions.py`, `review_policy.py`: 행동 선택 평가. 실제 작업 실행이 아닙니다.
- `local_checks.py`, `scanner_differential.py`: 실제 민감정보 검사와 이전 검사 결과 비교
- `performance_components.py`: 파일 작업 측정. AI 전체 응답 속도가 아닙니다.
- `runtime_mapping.py`: 실제 pytest 결과와 시나리오 ID 연결
- `report.py`: 원시 모델 이벤트를 제외한 공개 결과 생성

Python 개발 환경과 Codex CLI를 사용합니다. 네이티브 이미지 fixture 생성에만 개발용 Pillow가 필요합니다. 스킬 사용자의 의존성이 아닙니다. 결과 위치는 `.tools/scenario-run-20261107/`입니다. 기존 실행 폴더가 있으면 중복 호출을 피하므로 새 실험은 경로와 기록을 명시적으로 분리하세요. `scanner_differential.py`는 비교용 원본 검사 스크립트가 이 결과 폴더에 있어야 합니다.

```sh
python -m pytest -q dougle-bot/tests
node scripts/test-publish-sensitive.mjs
DOUGLE_SCAN_ENGINE=legacy node scripts/test-publish-sensitive.mjs
# 아래부터 실제 유료 AI 호출: 실행 승인 후 사용
python scripts/scenarios/run_connect_matrix.py
python scripts/scenarios/run_ask_matrix.py
python scripts/scenarios/run_fresh_matrix.py
python scripts/scenarios/run_ingest_matrix.py
```

[실행 범위와 제한](../../docs/scenarios/execution-20261107/README.md)을 함께 보세요. case_complete=false는 의도적인 표시이며, 부분 검사만으로 종단간 통과를 주장하지 않습니다.
