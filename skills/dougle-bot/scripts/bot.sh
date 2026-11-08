#!/bin/sh
# Optional runtime only. The core Dougle skills never invoke this bootstrap.
set -eu
umask 077
skill_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
version=0.1.16
bot_home=${DOUGLE_BOT_HOME:-"$HOME/.dougle-bot"}
runtime="$bot_home/runtime/$version"
command_name=${1:-status}
if [ "$command_name" = prepare ]; then
  [ "$(uname -s)" = Darwin ] || { echo 'M Mac에서 실행해 주세요.' >&2; exit 1; }
  chip=$(sysctl -n hw.optional.arm64 2>/dev/null || true)
  [ "$chip" = 1 ] || { echo 'Apple Silicon Mac이 필요합니다.' >&2; exit 1; }
  command -v uv >/dev/null 2>&1 || { echo 'uv가 필요합니다. 회사에서 허용한 방법으로 설치해 주세요. Homebrew 사용 시: brew install uv' >&2; exit 1; }
  [ ! -L "$bot_home" ] && [ ! -L "$bot_home/runtime" ] && [ ! -L "$runtime" ] || { echo '런타임 경로를 확인해 주세요.' >&2; exit 1; }
  (cd "$skill_dir/assets" && shasum -a 256 -c runtime.sha256 >/dev/null) || { echo '설치 파일 검증에 실패했습니다.' >&2; exit 1; }
  if [ -x "$runtime/bin/dougle-bot" ]; then
    exec "$runtime/bin/dougle-bot" --home "$bot_home" version
  fi
  mkdir -p "$bot_home/runtime"
  stage=$(mktemp -d "$bot_home/runtime/.install.XXXXXX")
  trap 'rm -rf "$stage"' EXIT HUP INT TERM
  uv venv --relocatable --python cpython-3.12-macos-aarch64-none --managed-python "$stage/env"
  uv pip install --python "$stage/env/bin/python" --require-hashes -r "$skill_dir/assets/requirements.txt"
  uv pip install --python "$stage/env/bin/python" --no-deps "$skill_dir/assets/dougle_bot-0.1.16-py3-none-any.whl"
  "$stage/env/bin/dougle-bot" version >/dev/null
  [ ! -e "$runtime" ] || { echo '다른 설치가 완료되었습니다. 상태를 다시 확인해 주세요.' >&2; exit 1; }
  mv "$stage/env" "$runtime"
  rmdir "$stage"
  trap - EXIT HUP INT TERM
  exec "$runtime/bin/dougle-bot" --home "$bot_home" version
fi
if [ ! -x "$runtime/bin/dougle-bot" ]; then
  if [ -f "$bot_home/config.toml" ]; then
    echo '{"configured":true,"runtime_installed":false,"next":"prepare","message":"새 봇 실행 환경을 준비해 주세요. 기존 설정과 키체인 토큰은 그대로 사용합니다. 실행 중인 봇은 준비 후 중지하고 다시 시작해야 업데이트가 적용됩니다."}'
  else
    echo '{"configured":false,"runtime_installed":false,"next":"prepare","message":"처음 사용하신다면 봇 실행 환경을 설치해 주세요."}'
  fi
  [ "$command_name" = status ] && exit 0
  exit 1
fi
exec "$runtime/bin/dougle-bot" --home "$bot_home" "$@"
