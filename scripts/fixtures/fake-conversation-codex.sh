#!/bin/sh
set -eu
directory="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
test -f "$directory/conversation-fixture-only"
if [ "$#" = 1 ] && [ "$1" = '--version' ]; then
  printf '%s\n' 'codex-cli 0.0.0-conversation-fixture'
  exit 0
fi
if [ "$#" = 2 ] && [ "$1" = 'login' ] && [ "$2" = 'status' ]; then
  printf '%s\n' 'Logged in using ChatGPT' >&2
  exit 0
fi
output=''
schema=''
model=''
execution=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    exec) execution='yes' ;;
    app-server|login) exit 2 ;;
    --output-last-message) shift; output="$1" ;;
    --output-schema) shift; schema="$1" ;;
    --model) shift; model="$1" ;;
  esac
  shift
done
test "$execution" = 'yes'
case "$model" in conversation-fixture|gpt-6.1-sol) ;; *) exit 2 ;; esac
test "$(basename "$output")" = 'answer.json'
test "$(cd "$(dirname "$output")" && pwd -P)" = "$(pwd -P)"
test "$(cd "$(dirname "$schema")" && pwd -P)" = "$(pwd -P)"
test ! -L "$output"
test -f "$schema"
cat > /dev/null
if grep -q '"decision"' "$schema"; then
  ! grep -q '"claims"' "$schema"
  grep -q '"SEARCH"' "$schema"
  grep -q '"query"' "$schema"
  if grep -q '"candidate"' "$schema"; then
    printf '%s' '{"decision":"SEARCH","query":"Strategy","candidate":null}' > "$output"
  else
    printf '%s' '{"decision":"SEARCH","query":"Strategy"}' > "$output"
  fi
  printf '%s\n' 'plan' >> "$directory/generation.calls"
else
  grep -q '"claims"' "$schema"
  grep -q '"evidenceIds"' "$schema"
  printf '%s' '{"title":"Synthetic Strategy answer","claims":[{"text":"Synthetic Strategy delegates hand selection to Strategy. This answer is a deterministic fixture, not actual AI.","evidenceIds":["R1"]}]}' > "$output"
  printf '%s\n' 'answer' >> "$directory/generation.calls"
fi
