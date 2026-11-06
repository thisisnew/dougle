import fcntl
import logging
import os
import platform
import signal
import subprocess
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .actions import Actions
from .config import private_dir
from .engine import ClaudeCliEngine
from .intake import receive
from .safety import BotError
from .slack_gateway import BoltSlackGateway
from .store import Store
from .worker import Worker


def apple_silicon():
    if platform.system() != "Darwin":
        return False
    if platform.machine() == "arm64":
        return True
    try:
        result = subprocess.run(
            ["/usr/sbin/sysctl", "-n", "hw.optional.arm64"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=3,
        )
        return result.returncode == 0 and result.stdout.strip() == b"1"
    except (OSError, subprocess.SubprocessError):
        return False


class InstanceLock:
    def __init__(self, path):
        self.path = path
        self.file = None

    def __enter__(self):
        private_dir(self.path.parent)
        if self.path.is_symlink():
            raise BotError("unsafe_lock_path")
        self.file = self.path.open("a+")
        os.chmod(self.path, 0o600)
        try:
            fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.file.close()
            self.file = None
            raise BotError("already_running") from None
        return self

    def __exit__(self, *args):
        if self.file:
            fcntl.flock(self.file.fileno(), fcntl.LOCK_UN)
            self.file.close()


def running(home):
    path = Path(home) / "runtime.lock"
    if not path.exists():
        return False
    try:
        with path.open("a+") as f:
            try:
                fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(f.fileno(), fcntl.LOCK_UN)
                return False
            except BlockingIOError:
                return True
    except OSError:
        raise BotError("status_unavailable") from None


def build_app(gateway, token, identity, store, config, worker, actions, executor):
    from slack_bolt import App
    from slack_bolt.authorization import AuthorizeResult

    logger = logging.getLogger("dougle_bot.slack")
    logger.handlers = [logging.NullHandler()]
    logger.propagate = False
    logger.setLevel(logging.CRITICAL)

    def authorize(**kwargs):
        return AuthorizeResult(
            enterprise_id=None,
            team_id=identity["team_id"],
            bot_token=token,
            bot_user_id=identity["user_id"],
            bot_id=identity.get("bot_id"),
        )

    app = App(
        client=gateway.client,
        authorize=authorize,
        process_before_response=True,
        request_verification_enabled=False,
        logger=logger,
    )

    def safe(fn, *args):
        try:
            fn(*args)
        except BotError:
            pass
        except Exception:
            store.audit(None, "system", "interaction_failed")

    @app.event("app_mention")
    def mention(ack, body):
        # process_before_response=True: local durable intake precedes Socket Mode ack.
        ident = receive(store, config, body, identity["user_id"])
        ack()
        if ident:
            executor.submit(safe, worker.notify, ident)

    def button(ack, body):
        user = body.get("user", {}).get("id")
        channel = body.get("channel", {}).get("id")
        ts = body.get("message", {}).get("ts")
        try:
            actions.authorized(user)
            if body.get("team", {}).get("id") != identity["team_id"]:
                raise BotError("wrong_workspace")
            item = body["actions"][0]
            actions.resolve(user, item["value"], channel, ts)
        except (BotError, KeyError, IndexError, TypeError):
            ack()
            executor.submit(
                safe,
                gateway.warn,
                channel,
                user,
                "현재 승인자와 최신 초안에서만 처리할 수 있습니다.",
            )
            return
        ack()

        def act(fn, *args):
            try:
                fn(*args)
            except BotError as exc:
                gateway.warn(
                    channel,
                    user,
                    f"처리하지 못했습니다 ({exc.code}). 최신 카드와 연결 상태를 확인해 주세요.",
                )
            except Exception:
                store.audit(None, "system", "interaction_failed")
                gateway.warn(
                    channel, user, "처리 결과를 확인하지 못했습니다. 상태를 확인해 주세요."
                )

        if item["action_id"] == "post":
            executor.submit(act, actions.post, user, item["value"], channel, ts)
        else:
            executor.submit(
                act,
                actions.open_modal,
                user,
                item["value"],
                channel,
                ts,
                body["trigger_id"],
                item["action_id"],
            )

    for action in ("post", "edit", "regenerate", "lookup", "reject"):
        app.action(action)(button)

    def submitted(ack, body):
        try:
            user = body.get("user", {}).get("id")
            actions.authorized(user)
            if body.get("team", {}).get("id") != identity["team_id"]:
                raise BotError("wrong_workspace")
            view = body["view"]
            values = view["state"]["values"]
            content = values.get("content", {}).get("value", {}).get("value") or ""
            observed = values.get("observed_at", {}).get("value", {}).get("value")
            ident = actions.submit(
                user,
                view["private_metadata"],
                view["callback_id"].removeprefix("dougle_"),
                content,
                observed,
            )
        except (BotError, KeyError, TypeError):
            ack(
                response_action="errors",
                errors={"content": "최신 초안과 입력 내용·조회 시각을 확인해 주세요."},
            )
            return
        ack()
        executor.submit(safe, worker.show_card, ident)

    for action in ("edit", "regenerate", "lookup", "reject"):
        app.view("dougle_" + action)(submitted)
    return app


def serve(config, secrets):
    if not apple_silicon():
        raise BotError("apple_silicon_required")
    from slack_bolt.adapter.socket_mode.builtin import SocketModeHandler

    with InstanceLock(config.home / "runtime.lock"):
        bot, app_token = secrets.get("bot"), secrets.get("app")
        if not bot or not app_token:
            raise BotError("slack_tokens_required")
        gateway = BoltSlackGateway(bot)
        identity = gateway.identity()
        if config.slack.workspace_id and config.slack.workspace_id != identity["team_id"]:
            raise BotError("wrong_workspace")
        store = Store(config.home / "bot.db")
        stop = threading.Event()
        gateway.cancelled = lambda: stop.is_set()

        def halt(*args):
            stop.set()
            store.stop()

        signal.signal(signal.SIGTERM, halt)
        signal.signal(signal.SIGINT, halt)
        engine = ClaudeCliEngine(
            config.engine,
            config.home,
            cancelled=lambda: stop.is_set() or store.control()["desired"] != "running",
        )
        worker = Worker(store, config, engine, gateway)
        actions = Actions(store, config, gateway, worker)
        executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="dougle-interactions")
        app = build_app(gateway, bot, identity, store, config, worker, actions, executor)
        handler = SocketModeHandler(app, app_token=app_token)

        def work():
            try:
                while not stop.is_set() and store.control()["desired"] == "running":
                    if not worker.run_once():
                        stop.wait(0.25)
            except Exception:
                stop.set()
                store.stop("worker_failed")

        thread = None
        try:
            store.recover()
            store.begin_run(os.getpid(), identity["team_id"], identity["user_id"])
            from . import __version__

            store.audit(None, "system", "runtime_started", __version__)
            handler.connect()
            thread = threading.Thread(target=work, daemon=True)
            thread.start()
            while not stop.wait(0.5):
                if store.control()["desired"] != "running":
                    break
                store.heartbeat(handler.client.is_connected())
        except Exception:
            store.stop("runtime_connection_failed")
            raise BotError("runtime_connection_failed") from None
        finally:
            stop.set()
            store.stop()
            handler.close()
            if thread:
                thread.join()
            executor.shutdown(wait=True, cancel_futures=True)
            store.close()
