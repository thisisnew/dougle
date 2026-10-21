#!/bin/sh
set -eu
if [ "$#" = 1 ] && [ "$1" = '--version' ]; then
  printf '%s\n' 'codex-cli 0.0.0-test-fixture'
  exit 0
fi
output=''
model=''
while [ "$#" -gt 0 ]; do
  if [ "$1" = '--output-last-message' ]; then
    shift
    output="$1"
  elif [ "$1" = '--model' ]; then
    shift
    model="$1"
  fi
  shift
done
test "$model" = 'synthetic-smoke-model' || test "$model" = 'preview-only-model'
test "$(basename "$output")" = 'answer.json'
test "$(cd "$(dirname "$output")" && pwd -P)" = "$(pwd -P)"
cat >/dev/null
sleep 2
if [ "$model" = 'preview-only-model' ]; then
  printf '%s' '{"title":"TEST FIXTURE 답변","claims":[{"text":"테스트 전용 가상 답변입니다.","evidenceIds":["R1"]}]}' > "$output"
  exit 0
fi
printf '%s' '{"title":"TEST FIXTURE 초안","claims":[{"text":"테스트 전용 가상 출력입니다.","evidenceIds":["E1"]}]}' > "$output"
