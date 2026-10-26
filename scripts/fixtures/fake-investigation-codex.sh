#!/bin/sh
set -eu
if [ "$#" = 1 ] && [ "$1" = '--version' ]; then
  printf '%s\n' 'codex-cli 0.0.0-investigation-fixture'
  exit 0
fi
if [ "$#" = 2 ] && [ "$1" = 'login' ] && [ "$2" = 'status' ]; then
  printf '%s\n' 'Logged in using ChatGPT' >&2
  exit 0
fi
directory="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
test -f "$directory/investigation-fixture-only"
output=''
model=''
schema=''
execution=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    exec) execution='yes' ;;
    --output-last-message) shift; output="$1" ;;
    --output-schema) shift; schema="$1" ;;
    --model) shift; model="$1" ;;
  esac
  shift
done
test "$execution" = 'yes'
printf '%s\n' 'generate' >> "$directory/all-generation.calls"
case "$model" in
  investigation-search-fixture|investigation-answer-fixture|investigation-cancel-fixture|investigation-mysql-proposal-fixture|investigation-datadog-proposal-fixture) ;;
  *) exit 2 ;;
esac
test "$(basename "$output")" = 'answer.json'
test "$(cd "$(dirname "$output")" && pwd -P)" = "$(pwd -P)"
test "$(cd "$(dirname "$schema")" && pwd -P)" = "$(pwd -P)"
test -f "$schema"
prompt="$(cat)"
printf '%s\n' 'generate' >> "$directory/$model.calls"
if [ "$model" = 'investigation-answer-fixture' ]; then
  grep -q '"claims"' "$schema"
  grep -q '"evidenceIds"' "$schema"
  printf '%s' '{"title":"Synthetic investigation answer","claims":[{"text":"Synthetic Strategy evidence delegates hand selection to Strategy.","evidenceIds":["R1"]}]}' > "$output"
  exit 0
fi
grep -q '"decision"' "$schema"
grep -q '"query"' "$schema"
grep -q '"SEARCH"' "$schema"
grep -q '"STOP"' "$schema"
if grep -q '"claims"' "$schema"; then
  printf '%s\n' 'Planning invocation incorrectly uses the answer schema' >&2
  exit 2
fi
if [ "$model" = 'investigation-cancel-fixture' ]; then
  exec /bin/sleep 90
fi
case "$model" in
  investigation-mysql-proposal-fixture)
    printf '%s' "$prompt" | grep -q 'SYNTHETIC_PROPOSE_MYSQL_READ'
    grep -q '"PROPOSE_MYSQL_READ"' "$schema"
    printf '%s' '{"decision":"PROPOSE_MYSQL_READ","query":null}' > "$output"
    exit 0 ;;
  investigation-datadog-proposal-fixture)
    printf '%s' "$prompt" | grep -q 'SYNTHETIC_PROPOSE_DATADOG_LOGS'
    grep -q '"PROPOSE_DATADOG_LOGS"' "$schema"
    printf '%s' '{"decision":"PROPOSE_DATADOG_LOGS","query":null}' > "$output"
    exit 0 ;;
esac
printf '%s' '{"decision":"SEARCH","query":"Strategy"}' > "$output"
