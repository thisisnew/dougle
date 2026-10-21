#!/bin/sh
set -eu
output=''
while [ "$#" -gt 0 ]; do
  if [ "$1" = '--output-last-message' ]; then
    shift
    output="$1"
  fi
  shift
done
test "$(basename "$output")" = 'answer.json'
test "$(cd "$(dirname "$output")" && pwd -P)" = "$(pwd -P)"
cat >/dev/null
sleep 2
printf '%s' '{"title":"TEST FIXTURE 초안","claims":[{"text":"테스트 전용 가상 출력입니다.","evidenceIds":["E1"]}]}' > "$output"
