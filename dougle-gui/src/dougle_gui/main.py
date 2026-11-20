"""dougle-gui command line entry point."""

from __future__ import annotations

import argparse
import errno
import sys
import webbrowser
from pathlib import Path

from . import __version__
from .runs import DEFAULT_ALLOWED_TOOLS, Runner
from .server import App, serve, url, wait_forever
from .store import Store
from .token import TokenError, load_or_create

DEFAULT_PORT = 8765


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="✦ dougle 로컬 GUI")
    p.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"포트 (기본 {DEFAULT_PORT}, 0 이면 빈 포트 자동 선택)")
    p.add_argument("--token", action="store_true", help="접속 토큰 요구 (같은 PC의 다른 프로그램 차단, 토큰은 저장해 재사용)")
    p.add_argument("--new-token", action="store_true", help="접속 토큰을 새로 만들어 이전 주소를 무효화 (--token 포함)")
    p.add_argument("--home", type=Path, default=Path.home() / ".dougle-gui", help="GUI 설정 위치 (접속 토큰 저장)")
    p.add_argument("--store", type=Path, default=Path.home() / ".dougle", help="지식 저장소 위치")
    p.add_argument("--claude-bin", default="claude", help="Claude CLI 이름 또는 경로")
    p.add_argument("--bot-bin", default="dougle-bot", help="dougle-bot 이름 또는 경로")
    p.add_argument(
        "--allow-tool",
        action="append",
        default=[],
        metavar="RULE",
        help="스킬 실행에 추가로 허용할 Claude 도구 규칙 (예: mcp__claude_ai_Atlassian)",
    )
    p.add_argument(
        "--no-approval",
        action="store_true",
        help="허용 목록 밖 도구를 GUI에서 묻지 않고 바로 거부 (기본: 화면에서 승인 요청)",
    )
    p.add_argument("--no-browser", action="store_true", help="브라우저를 자동으로 열지 않음")
    p.add_argument("--version", action="version", version=__version__)
    return p


def cli(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    runner = Runner(
        claude_bin=args.claude_bin,
        allowed_tools=tuple(dict.fromkeys([*DEFAULT_ALLOWED_TOOLS, *args.allow_tool])),
    )
    token = None
    if args.token or args.new_token:
        try:
            token = load_or_create(args.home, rotate=args.new_token)
        except (OSError, TokenError) as exc:
            print(f"접속 토큰을 준비하지 못했습니다: {exc}", file=sys.stderr)
            return 1
    app = App(Store(args.store), runner, bot_bin=args.bot_bin, token=token, approvals=not args.no_approval)
    try:
        httpd = serve(app, args.port)
    except OSError as exc:
        if exc.errno != errno.EADDRINUSE or args.port == 0:
            raise
        httpd = serve(app, 0)
        print(f"경고: {args.port} 포트를 이미 사용 중이라 {app.port} 포트로 열었습니다. "
              "즐겨찾기 주소를 쓰려면 기존 프로세스를 종료하거나 --port 로 지정해 주세요.", file=sys.stderr)
    address = url(app)
    print(f"✦ dougle GUI: {address}", flush=True)
    if token:
        print("이 주소는 즐겨찾기해 두고 계속 쓸 수 있습니다. 다른 사람에게 공유하지 마세요.", flush=True)
    print("종료하려면 Ctrl+C 를 누르세요.", flush=True)
    if runner.claude_path() is None:
        print("경고: Claude CLI 를 찾지 못했습니다. --claude-bin 으로 경로를 지정해 주세요.", file=sys.stderr)
    if not args.no_browser:
        webbrowser.open(address)
    try:
        wait_forever(httpd)
    finally:
        runner.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(cli())
