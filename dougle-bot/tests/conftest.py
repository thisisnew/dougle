import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))


import socket
from dataclasses import replace
from types import SimpleNamespace

import pytest
from test_answer_style import SESSION, SKILLS, answer

from dougle_bot.actions import Actions
from dougle_bot.config import Config, EngineConfig, SlackConfig
from dougle_bot.engine import EngineResult, FakeEngine
from dougle_bot.slack_gateway import FakeSlack
from dougle_bot.store import Store
from dougle_bot.worker import Worker


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    def blocked(*args, **kwargs):
        raise AssertionError("External network access is forbidden in bot tests")

    monkeypatch.setattr(socket.socket, "connect", blocked)
    monkeypatch.setattr(socket, "create_connection", blocked)


@pytest.fixture
def app(tmp_path):
    now = [1000.0]
    config = Config(
        SlackConfig("UAPPROVER", ["CTEST", "COTHER"], "TTEST"),
        replace(EngineConfig(), workdir=str(tmp_path / "engine")),
        tmp_path,
    )
    store = Store(tmp_path / "bot.db", clock=lambda: now[0])
    store.begin_run(1, "TTEST", "UBOT")
    slack = FakeSlack()
    engine = FakeEngine([EngineResult(SESSION, answer(), 0.02, SKILLS)] * 10)
    worker = Worker(store, config, engine, slack)
    actions = Actions(store, config, slack, worker)
    obj = SimpleNamespace(
        store=store, cfg=config, slack=slack, engine=engine, worker=worker, actions=actions, now=now
    )
    yield obj
    store.close()
