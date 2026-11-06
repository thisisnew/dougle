import json
import math
import os
import re
import tempfile
import tomllib
from dataclasses import asdict, dataclass, field
from pathlib import Path

from .safety import BotError

DEFAULT_READERS = [
    "mcp__claude_ai_Google_Cloud_BigQuery__execute_sql_readonly",
    "mcp__claude_ai_Google_Cloud_BigQuery__get_table_info",
    "mcp__claude_ai_Google_Cloud_BigQuery__list_table_ids",
    "mcp__claude_ai_Google_Cloud_BigQuery__list_dataset_ids",
    "mcp__claude_ai_Google_Cloud_BigQuery__get_dataset_info",
    "mcp__claude_ai_Atlassian__getConfluencePage",
    "mcp__claude_ai_Atlassian__searchConfluenceUsingCql",
    "mcp__claude_ai_Slack__slack_read_thread",
    "mcp__claude_ai_Slack__slack_search_public",
]
BUILTINS = {"Skill", "Read", "Grep", "Glob", "WebFetch", "Edit", "Write"}
FORBIDDEN = re.compile(
    r"(?:send|post|schedule|reaction|delete|create|update|insert|execute_sql$|write)", re.I
)


def identifier(value, prefix):
    return isinstance(value, str) and bool(re.fullmatch(f"[{prefix}][A-Z0-9]{{2,30}}", value))


def channel_id(value):
    if identifier(value, "CG"):
        return value
    m = re.fullmatch(r"https://[a-zA-Z0-9.-]+\.slack\.com/archives/([CG][A-Z0-9]{2,30})/?", value)
    if m:
        return m[1]
    raise BotError("invalid_channel")


def private_dir(path):
    path = Path(path).expanduser()
    if path.is_symlink():
        raise BotError("unsafe_storage_path")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.stat().st_uid != os.getuid():
        raise BotError("unsafe_storage_owner")
    path.chmod(0o700)
    return path.resolve()


@dataclass(frozen=True)
class SlackConfig:
    approver_user_id: str = ""
    allowed_channel_ids: list[str] = field(default_factory=list)
    workspace_id: str = ""
    ack_reaction: str = "eyes"
    thread_history_limit: int = 50


@dataclass(frozen=True)
class EngineConfig:
    claude_bin: str = "claude"
    model: str = ""
    workdir: str = "~/.dougle-bot/engine"
    timeout_seconds: int = 900
    max_turns: int = 60
    max_budget_usd_per_call: float = 3.0
    daily_budget_usd: float = 30.0
    max_parallel: int = 1
    allowed_tools: list[str] = field(default_factory=lambda: sorted(BUILTINS) + DEFAULT_READERS)
    disallowed_tools: list[str] = field(default_factory=lambda: ["Bash", "Agent", "Task"])
    ask_skill: str = "dougle:dougle-ask"
    style_skill: str = "dougle:i-have-adhd"


@dataclass(frozen=True)
class Config:
    slack: SlackConfig
    engine: EngineConfig
    home: Path

    def validate(self):
        if not identifier(self.slack.approver_user_id, "U"):
            raise BotError("invalid_approver")
        if not isinstance(self.slack.allowed_channel_ids, list) or not all(
            identifier(c, "CG") for c in self.slack.allowed_channel_ids
        ):
            raise BotError("invalid_channel")
        if len(set(self.slack.allowed_channel_ids)) != len(self.slack.allowed_channel_ids):
            raise BotError("duplicate_channel")
        if self.slack.workspace_id and not identifier(self.slack.workspace_id, "T"):
            raise BotError("invalid_workspace")
        ranges = [
            (self.slack.thread_history_limit, 1, 500),
            (self.engine.timeout_seconds, 10, 3600),
            (self.engine.max_turns, 1, 200),
            (self.engine.max_parallel, 1, 1),
        ]
        if any(type(v) is not int or not lo <= v <= hi for v, lo, hi in ranges):
            raise BotError("invalid_integer_setting")
        for v in (self.engine.max_budget_usd_per_call, self.engine.daily_budget_usd):
            if type(v) not in (int, float) or not math.isfinite(v) or not 0 < v <= 1000:
                raise BotError("invalid_budget")
        for value in (self.engine.claude_bin, self.engine.workdir):
            if not isinstance(value, str) or not value.strip() or any(ord(c) < 32 for c in value):
                raise BotError("invalid_engine_path")
        if not isinstance(self.slack.ack_reaction, str):
            raise BotError("invalid_reaction")
        if self.slack.ack_reaction and not re.fullmatch(
            r"[a-z0-9_+-]{1,60}", self.slack.ack_reaction
        ):
            raise BotError("invalid_reaction")
        if not isinstance(self.engine.allowed_tools, list):
            raise BotError("invalid_tool_setting")
        if not {"Skill", "Read"} <= set(self.engine.allowed_tools):
            raise BotError("required_tools_missing")
        for tool in self.engine.allowed_tools:
            if not isinstance(tool, str) or (
                tool not in BUILTINS
                and (not re.fullmatch(r"mcp__[A-Za-z0-9_]+", tool) or FORBIDDEN.search(tool))
            ):
                raise BotError("unsafe_allowed_tool")
        if not isinstance(self.engine.disallowed_tools, list) or not all(
            isinstance(t, str) for t in self.engine.disallowed_tools
        ):
            raise BotError("invalid_tool_setting")
        if "/" not in self.engine.claude_bin and not re.fullmatch(
            r"[A-Za-z][A-Za-z0-9_-]*", self.engine.claude_bin
        ):
            raise BotError("invalid_engine_path")
        if not isinstance(self.engine.model, str) or any(ord(c) < 32 for c in self.engine.model):
            raise BotError("invalid_model")
        for name in (self.engine.ask_skill, self.engine.style_skill):
            if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9:_-]{1,100}", name):
                raise BotError("invalid_skill_name")
        return self


def load(path: Path) -> Config:
    try:
        data = tomllib.loads(path.read_text())
        if set(data) - {"slack", "engine", "storage"}:
            raise BotError("unknown_config_section")
        storage = data.get("storage", {})
        if set(storage) - {"home"}:
            raise BotError("unknown_storage_setting")
        home = Path(storage.get("home", "~/.dougle-bot")).expanduser()
        if not home.is_absolute():
            home = path.parent / home
        return Config(
            SlackConfig(**data.get("slack", {})), EngineConfig(**data.get("engine", {})), home
        ).validate()
    except FileNotFoundError:
        raise BotError("setup_required") from None
    except (TypeError, ValueError, AttributeError, OSError):
        raise BotError("invalid_config") from None


def save(config: Config, path: Path):
    config.validate()
    private_dir(path.parent)
    if path.is_symlink():
        raise BotError("unsafe_config_path")
    sections = {
        "slack": asdict(config.slack),
        "engine": asdict(config.engine),
        "storage": {"home": str(config.home)},
    }
    content = "\n".join(
        f"[{section}]\n"
        + "\n".join(f"{k} = {json.dumps(v, ensure_ascii=False)}" for k, v in values.items())
        + "\n"
        for section, values in sections.items()
    )
    fd, name = tempfile.mkstemp(prefix=".config-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as f:
            f.write(content)
            f.flush()
            os.fsync(f.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)
