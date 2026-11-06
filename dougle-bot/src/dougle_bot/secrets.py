import platform
import subprocess
from typing import Protocol

from .safety import BotError

SERVICES = {"bot": "dougle-bot.slack-bot-token", "app": "dougle-bot.slack-app-token"}
PREFIXES = {"bot": "xoxb-", "app": "xapp-"}


class SecretStore(Protocol):
    def get(self, name: str) -> str | None: ...
    def set_interactive(self, name: str) -> None: ...
    def delete(self, name: str) -> None: ...


class KeychainSecretStore:
    def __init__(self, runner=subprocess.run, system=platform.system):
        self.runner, self.system = runner, system

    def _args(self, command, name):
        if self.system() != "Darwin":
            raise BotError("macos_keychain_required")
        if name not in SERVICES:
            raise BotError("unknown_secret")
        return ["/usr/bin/security", command, "-a", "dougle-bot", "-s", SERVICES[name]]

    def _run(self, args, **kwargs):
        try:
            return self.runner(args, timeout=60, check=False, **kwargs)
        except (OSError, subprocess.SubprocessError):
            raise BotError("keychain_unavailable") from None

    def get(self, name):
        result = self._run(self._args("find-generic-password", name) + ["-w"], capture_output=True)
        if result.returncode == 44:
            return None
        if result.returncode:
            raise BotError("keychain_access_failed")
        try:
            value = result.stdout.decode("utf-8").rstrip("\r\n")
        except UnicodeError:
            raise BotError("invalid_secret") from None
        if not value.startswith(PREFIXES[name]) or any(c.isspace() for c in value):
            raise BotError("wrong_token_type")
        return value

    def set_interactive(self, name):
        # security prompts through the terminal; no token argv/env/Python input.
        result = self._run(self._args("add-generic-password", name) + ["-U", "-w"])
        if result.returncode:
            raise BotError("keychain_save_failed")
        try:
            if self.get(name) is None:
                raise BotError("keychain_save_failed")
        except BotError as exc:
            if exc.code == "wrong_token_type":
                self.delete(name)
            raise

    def delete(self, name):
        result = self._run(self._args("delete-generic-password", name), capture_output=True)
        if result.returncode not in (0, 44):
            raise BotError("keychain_delete_failed")


class FakeSecretStore:
    def __init__(self, values=None):
        self.values = dict(values or {})

    def get(self, name):
        if name not in SERVICES:
            raise BotError("unknown_secret")
        return self.values.get(name)

    def set_interactive(self, name):
        raise BotError("interactive_unavailable")

    def delete(self, name):
        self.values.pop(name, None)
