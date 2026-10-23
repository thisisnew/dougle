#!/bin/sh
set -eu
if [ "$#" = 1 ] && [ "$1" = '--version' ]; then
  printf '%s\n' 'codex-cli 0.0.0-recovery-fixture'
  exit 0
fi
directory="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
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
case "$model" in
  recovery-generation) evidence='E1' ;;
  recovery-answer) evidence='R1' ;;
  *) exit 2 ;;
esac
test "$(basename "$output")" = 'answer.json'
test "$(cd "$(dirname "$output")" && pwd -P)" = "$(pwd -P)"
cat >/dev/null
printf '%s\n' 'request' >> "$directory/$model.calls"
if [ ! -f "$directory/release" ]; then
  exec /bin/sleep 90
fi
printf '{"title":"Recovery fixture","claims":[{"text":"Synthetic recovery evidence.","evidenceIds":["%s"]}]}' "$evidence" > "$output"
