import argparse
import json
import os
import platform
import subprocess
import sys
import time
from dataclasses import replace
from pathlib import Path

from . import __version__
from .config import Config, EngineConfig, SlackConfig, channel_id, load, private_dir, save
from .progress import safe_profile
from .recovery import reconcile
from .runtime import apple_silicon, running, serve
from .safety import BotError
from .secrets import SERVICES, KeychainSecretStore
from .slack_gateway import BoltSlackGateway
from .store import Store


def default_home():
    return Path.home() / ".dougle-bot"


def status(home, config_path):
    result = {
        "version": __version__,
        "configured": config_path.exists(),
        "running": False,
        "state": "stopped",
        "menu": ["1. 시작", "2. 상태 확인", "3. 중지", "4. 채널 추가"],
    }
    if not config_path.exists():
        result["next"] = "setup"
        return result
    cfg = load(config_path)
    home = cfg.home
    result.update(channels=cfg.slack.allowed_channel_ids, approver=cfg.slack.approver_user_id)
    result["running"] = running(home)
    if (home / "bot.db").exists():
        store = Store(home / "bot.db", migrate=False)
        try:
            control = store.control()
            version = store.one(
                "SELECT detail FROM audit WHERE action='runtime_started' AND at>=? ORDER BY id DESC LIMIT 1",
                (control["started"],),
            )
            result["running_version"] = version["detail"] if result["running"] and version else None
            result["update_required"] = (
                result["running"] and result["running_version"] != __version__
            )
            result.update(
                state=control["desired"] if result["running"] else "stopped",
                heartbeat=control["heartbeat"],
                last_start=control["started"],
                error=control["error"],
                inquiries=store.all(
                    "SELECT status,COUNT(*) AS count FROM inquiries GROUP BY status"
                ),
                active_inquiries=[
                    {
                        "id": row["id"],
                        "status": row["status"],
                        "elapsed_seconds": max(0, int(store.clock() - row["created_at"])),
                        "channel": row["channel_id"],
                        "thread_ts": row["thread_ts"],
                    }
                    for row in store.all(
                        "SELECT i.id,i.status,i.created_at,t.channel_id,t.thread_ts FROM inquiries i JOIN threads t USING(thread_key) WHERE i.status IN ('queued','drafting','needs_lookup','awaiting_approval','posting','post_unknown') ORDER BY i.id LIMIT 50"
                    )
                ],
            )
            result["recent_timings"] = [
                {"inquiry_id": row["inquiry_id"], **profile}
                for row in store.all(
                    "SELECT inquiry_id,detail FROM audit WHERE action='answer_timing' ORDER BY id DESC LIMIT 10"
                )
                if (profile := safe_profile(row["detail"])) is not None
            ]
            result["healthy"] = (
                result["running"]
                and control["desired"] == "running"
                and not control["error"]
                and time.time() - control["heartbeat"] < 15
            )
        finally:
            store.close()
    return result


def doctor(config, secrets, online=False):
    checks = {
        "config": "ok",
        "macos": "ok" if platform.system() == "Darwin" else "unsupported",
        "apple_silicon": "ok" if apple_silicon() else "unsupported",
        "channels": "ok" if config.slack.allowed_channel_ids else "none",
        "engine": "missing",
        "bot_token": "missing",
        "app_token": "missing",
        "slack_connection": "not_checked",
        "skill_execution": "not_checked_until_question",
    }
    name = config.engine.claude_bin
    if "/" in name:
        checks["engine"] = "found" if os.access(Path(name).expanduser(), os.X_OK) else "missing"
    else:
        try:
            run = subprocess.run(
                ["/bin/zsh", "-lic", f"whence -w {name}"], capture_output=True, timeout=10
            )
            if run.returncode == 0:
                checks["engine"] = "found"
        except (OSError, subprocess.TimeoutExpired):
            pass
    for name in ("bot", "app"):
        try:
            checks[name + "_token"] = "present" if secrets.get(name) else "missing"
        except BotError as exc:
            checks[name + "_token"] = exc.code
    if online and checks["bot_token"] == "present":
        gw = BoltSlackGateway(secrets.get("bot"))
        identity = gw.identity()
        checks["workspace"] = identity["team_id"]
        if config.slack.workspace_id and identity["team_id"] != config.slack.workspace_id:
            raise BotError("wrong_workspace")
        for channel in config.slack.allowed_channel_ids:
            gw.call("conversations_history", channel=channel, limit=1)
        checks["slack_connection"] = "read_verified"
    return checks


def parser():
    p = argparse.ArgumentParser(description="✦ dougle · 로컬 Slack 봇")
    p.add_argument("--home", type=Path, default=default_home())
    p.add_argument("--config", type=Path)
    sub = p.add_subparsers(dest="command", required=True)
    for name in ("status", "start", "stop", "run", "version"):
        sub.add_parser(name)
    d = sub.add_parser("doctor")
    d.add_argument("--online", action="store_true")
    setup = sub.add_parser("setup")
    setup.add_argument("--approver", required=True)
    setup.add_argument("--channel", action="append", default=[])
    setup.add_argument("--claude", default="claude")
    add = sub.add_parser("channel-add")
    add.add_argument("channel")
    secret = sub.add_parser("secrets")
    secret.add_argument("action", choices=("guide", "set", "delete"))
    secret.add_argument("name", choices=("bot", "app"), nargs="?")
    r = sub.add_parser("reconcile")
    r.add_argument("inquiry_id", type=int)
    a = sub.add_parser("ask")
    a.add_argument("question", nargs="?")
    return p


def execute(args, secrets=None):
    secrets = secrets or KeychainSecretStore()
    home = args.home.expanduser().absolute()
    path = args.config or home / "config.toml"
    if args.command == "version":
        return {"version": __version__}
    if args.command == "status":
        return status(home, path)
    if args.command == "secrets":
        if args.action == "guide":
            return {
                "message": "토큰을 대화창에 보내지 마세요. Mac 터미널에서 아래 명령을 실행하고 보이지 않는 입력창에 직접 입력해 주세요.",
                "commands": [
                    f"security add-generic-password -U -a dougle-bot -s {service} -w"
                    for service in SERVICES.values()
                ],
            }
        if not args.name:
            raise BotError("secret_name_required")
        if args.action == "set":
            if not sys.stdin.isatty():
                raise BotError("terminal_input_required")
            secrets.set_interactive(args.name)
        else:
            secrets.delete(args.name)
        return {"secret": args.name, "result": "ok"}
    if args.command == "setup":
        if path.exists():
            raise BotError("already_configured_use_channel_add")
        config = Config(
            SlackConfig(
                approver_user_id=args.approver,
                allowed_channel_ids=[channel_id(x) for x in args.channel],
            ),
            EngineConfig(claude_bin=args.claude, workdir=str(home / "engine")),
            home,
        ).validate()
        save(config, path)
        return {
            "configured": True,
            "next": "doctor",
            "menu": ["1. 시작", "2. 상태 확인", "3. 중지", "4. 채널 추가"],
        }
    cfg = load(path)
    if args.command == "channel-add":
        if running(cfg.home):
            raise BotError("stop_before_channel_change")
        channel = channel_id(args.channel)
        if channel not in cfg.slack.allowed_channel_ids:
            cfg = replace(
                cfg,
                slack=replace(
                    cfg.slack, allowed_channel_ids=[*cfg.slack.allowed_channel_ids, channel]
                ),
            )
            save(cfg, path)
        return {
            "channels": cfg.slack.allowed_channel_ids,
            "next": "해당 Slack 채널에서 /invite @dougle을 실행한 뒤 doctor --online으로 확인해 주세요. 기존 키체인 토큰은 재사용합니다.",
        }
    if args.command == "doctor":
        return doctor(cfg, secrets, args.online)
    if args.command == "stop":
        if (cfg.home / "bot.db").exists():
            store = Store(cfg.home / "bot.db", migrate=False)
            store.stop()
            store.close()
        for _ in range(40):
            if not running(cfg.home):
                return {"state": "stopped", "missed_questions": "not_imported_on_restart"}
            time.sleep(0.25)
        return {
            "state": "stopping",
            "message": "진행 중 작업 종료를 기다리고 있습니다. 상태를 다시 확인해 주세요.",
        }
    if args.command == "run":
        serve(cfg, secrets)
        return {"state": "stopped"}
    if args.command == "start":
        if running(cfg.home):
            return status(cfg.home, path)
        checks = doctor(cfg, secrets, online=True)
        if (
            checks["bot_token"] != "present"
            or checks["slack_connection"] != "read_verified"
            or checks["engine"] != "found"
            or checks["app_token"] != "present"
            or checks["channels"] != "ok"
            or checks["macos"] != "ok"
            or checks["apple_silicon"] != "ok"
        ):
            return {"started": False, "checks": checks}
        private_dir(cfg.home)
        log = cfg.home / "runtime.log"
        if log.is_symlink():
            raise BotError("unsafe_log_path")
        with log.open("ab") as f:
            log.chmod(0o600)
            proc = subprocess.Popen(
                [sys.executable, "-m", "dougle_bot.main", "--config", str(path), "run"],
                stdin=subprocess.DEVNULL,
                stdout=f,
                stderr=f,
                start_new_session=True,
            )
        for _ in range(40):
            if proc.poll() is not None:
                raise BotError("start_failed_check_status")
            current = status(cfg.home, path)
            if current.get("healthy"):
                return current
            time.sleep(0.25)
        return {
            "state": "starting",
            "message": "연결 중입니다. 상태 확인에서 Slack 연결을 확인해 주세요.",
        }
    if args.command == "reconcile":
        token = secrets.get("bot")
        if not token:
            raise BotError("slack_tokens_required")
        store = Store(cfg.home / "bot.db")
        try:
            return {"confirmed": reconcile(store, BoltSlackGateway(token), args.inquiry_id)}
        finally:
            store.close()
    if args.command == "ask":
        # Dry-run entry point: no implicit Slack target and no unapproved AI cost.
        return {
            "message": "봇 질문은 연결한 Slack 채널에서 @dougle로 보내 주세요. 터미널의 업무 질문은 dougle-ask 스킬을 사용해 주세요.",
            "sent": False,
        }
    raise BotError("unknown_command")


def cli():
    try:
        result = execute(parser().parse_args())
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except BotError as exc:
        print(
            json.dumps(
                {"error": exc.code, "message": "설정 또는 연결 상태를 확인해 주세요."},
                ensure_ascii=False,
            )
        )
        raise SystemExit(1) from None
    except KeyboardInterrupt:
        raise SystemExit(130) from None
    except Exception:
        print(
            json.dumps(
                {
                    "error": "operation_failed",
                    "message": "요청을 완료하지 못했습니다. 상태 확인을 실행해 주세요.",
                },
                ensure_ascii=False,
            )
        )
        raise SystemExit(1) from None


if __name__ == "__main__":
    cli()
