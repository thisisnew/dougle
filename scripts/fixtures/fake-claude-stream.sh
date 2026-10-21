#!/bin/sh
set -eu
if [ "$#" = 1 ] && [ "$1" = '--version' ]; then
  printf '%s\n' '0.0.0-test-fixture (Claude Code)'
  exit 0
fi
model=''
format=''
partial=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --model) shift; model="$1" ;;
    --output-format) shift; format="$1" ;;
    --include-partial-messages) partial='yes' ;;
  esac
  shift
done
test "$model" = 'stream-fixture-model'
test "$format" = 'stream-json'
test "$partial" = 'yes'
cat >/dev/null
printf '%s\n' '{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"message_start"}}'
printf '%s\n' '{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_start","index":0,"content_block":{"type":"text"}}}'
printf '%s\n' '{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"가상 임시 출력"}}}'
sleep 2
printf '%s\n' '{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" — 검증 전"}}}'
sleep 2
printf '%s\n' '{"type":"result","subtype":"success","is_error":false,"structured_output":{"title":"TEST FIXTURE","claims":[{"text":"검사를 통과한 가상 최종 답변입니다.","evidenceIds":["R1"]}]}}'
