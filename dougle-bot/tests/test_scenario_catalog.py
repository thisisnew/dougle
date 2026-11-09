"""Catalog-ID checks against production intake/worker/evidence; no live services.

These are component observations, not a claim that every catalog acceptance step ran.
"""

import copy
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest
from test_answer_style import SESSION, SKILLS
from test_evidence import output
from test_images import attach, file
from test_runtime import event

from dougle_bot.engine import EngineResult
from dougle_bot.evidence import validate_verification
from dougle_bot.intake import receive
from dougle_bot.safety import BotError
from dougle_bot.slack_gateway import SlackFailure
from dougle_bot.store import Store


def automatic(app):
    cfg = replace(app.cfg, slack=replace(app.cfg.slack, require_approval=False))
    app.cfg = app.worker.config = app.actions.config = cfg


@pytest.mark.parametrize("number", range(1, 25), ids=lambda n: f"DG-INTAKE-{n:03}")
def test_catalog_intake(app, monkeypatch, number):
    automatic(app)
    body = event()
    ev = body["event"]
    rejected = {3, 4, 5, 6, 7, 8, 9, 10, 15, 16, 17, 18, 19, 20}
    if number == 2:
        app.cfg.slack.allowed_channel_ids.append("GPRIVATE")
        ev["channel"] = "GPRIVATE"
    elif number == 3:
        ev["channel"] = "CUNLISTED"
    elif number == 4:
        app.cfg.slack.allowed_channel_ids.clear()
    elif number == 5:
        body["team_id"] = "TOTHER"
    elif number == 6:
        ev["user"] = "UBOT"
    elif number == 7:
        ev["bot_id"] = "BOTHER"
    elif number in (8, 9):
        ev.update(type="message", text="추가 내용입니다.")
        if number == 9:
            ev["thread_ts"] = "1000.100000"
    elif number == 10:
        ev["text"] = "<@UBOT>"
    elif number == 15:
        app.store.stop()
    elif number == 16:
        ev["ts"] = "999.100000"
    elif number == 17:
        ev["ts"] = "5000.100000"
    elif number == 18:
        ev["user"] = "INVALID"
    elif number == 19:
        ev["thread_ts"] = "NaN"
    elif number == 20:
        ev["text"] = "<@UBOT> password=synthetic-private-value"
    elif number == 21:

        def fail(*args):
            raise OSError("synthetic_storage_unavailable")

        monkeypatch.setattr(app.store, "intake", fail)
        with pytest.raises(OSError):
            receive(app.store, app.cfg, body, "UBOT")
        assert not app.engine.calls and not app.slack.calls
        return
    elif number == 22:
        app.slack.failures["reaction"] = SlackFailure("missing_emoji")
    elif number == 23:
        attach(app, text="<@UBOT>")
        ev.update(text="<@UBOT>", files=[file()])
    if number == 12:
        with ThreadPoolExecutor(max_workers=8) as pool:
            ids = list(
                pool.map(
                    lambda _: receive(app.store, app.cfg, copy.deepcopy(body), "UBOT"), range(200)
                )
            )
        assert len([x for x in ids if x is not None]) == 1
        ident = next(x for x in ids if x is not None)
    else:
        ident = receive(app.store, app.cfg, body, "UBOT")
    if number in rejected:
        assert ident is None
        assert not app.store.all("SELECT * FROM inquiries")
        assert not app.store.all("SELECT * FROM jobs")
        assert not app.engine.calls and not app.slack.calls
        return
    assert ident is not None
    if number in (11, 24):
        assert receive(app.store, app.cfg, copy.deepcopy(body), "UBOT") is None
    expected = 1
    if number == 13:
        assert receive(app.store, app.cfg, event(channel="COTHER"), "UBOT") != ident
        expected = 2
        assert len(app.store.all("SELECT * FROM threads")) == 2
    if number == 14:
        assert (
            receive(app.store, app.cfg, event(ts="1002.000001", thread_ts=ev["ts"]), "UBOT")
            != ident
        )
        expected = 2
        assert len(app.store.all("SELECT * FROM threads")) == 1
    assert len(app.store.all("SELECT * FROM inquiries")) == expected
    assert len(app.store.all("SELECT * FROM jobs")) == expected
    if number in (1, 2, 22, 23):
        assert app.worker.run_once()
        assert len(app.engine.calls) == 1
        posts = [c for c in app.slack.calls if c[0] == "post"]
        assert len(posts) == 1 and posts[0][1:3] == (ev["channel"], ev["ts"])
        assert not app.slack.cards
        if number == 23:
            assert app.engine.image_calls[0]


KINDS = [
    ("domain", ["knowledge"]),
    ("implementation", ["code"]),
    ("current_state", ["live"]),
    ("visual", ["image"]),
    ("incident", ["code"]),
    ("incident", ["code", "live"]),
]


@pytest.mark.parametrize("number", range(1, 49), ids=lambda n: f"DG-EVIDENCE-{n:03}")
def test_catalog_evidence(app, number):
    category, required = KINDS[(number - 1) // 8]
    variant = (number - 1) % 8
    checks = [
        {"kind": k, "receipt": "image:FTEST" if k == "image" else "read1", "source_index": 0}
        for k in required
    ]
    value = output(category, tuple(required), checks=checks)
    reads = {"read1": set(required) - {"image"}}
    images = ("FTEST",) if "image" in required else ()
    if variant in (1, 2, 3):
        # A textual claim, failed read, or previous inquiry ID supplies no current receipt.
        reads = {}
        images = ()
    elif variant == 4:
        value["verification"]["checks"][0]["source_index"] = 90
    elif variant == 5:
        value["verification"].update(
            question_kind="clarification", required=[], checks=[], missing=[]
        )
    elif variant == 6:
        value["verification"]["checks"] = []
    elif variant == 7:
        # Verify the real worker's presentation repair, including the live exclusion.
        automatic(app)
        if images:
            attach(app)
        value["body"] = "맞아."
        fixed = copy.deepcopy(value)
        fixed["body"] = "확인된 범위의 설명입니다."
        app.engine.results = [
            EngineResult(SESSION, value, 0.01, SKILLS, reads),
            EngineResult(SESSION, fixed, 0.01, SKILLS, reads),
        ]
        ident = receive(app.store, app.cfg, event(files=[file()] if images else []), "UBOT")
        assert app.worker.run_once()
        assert app.store.inquiry(ident)["status"] == (
            "needs_lookup" if "live" in required else "posted"
        )
        reused = app.store.all("SELECT * FROM audit WHERE action='presentation_evidence_reused'")
        assert bool(reused) is ("live" not in required)
        assert len(app.engine.calls) == 2
        return
    if variant == 0:
        assert validate_verification(
            value, reads, image_ids=images, required_floor=required
        ) == set(required)
    else:
        with pytest.raises(BotError):
            validate_verification(value, reads, image_ids=images, required_floor=required)


@pytest.mark.parametrize("number", [2, 3, 7, 8, 10], ids=lambda n: f"DG-AUTO-{n:03}")
def test_catalog_auto_customer_answer(app, number):
    # Runtime routing of evidence-valid outputs; truthfulness of wording is a separate model test.
    automatic(app)
    from test_evidence import result

    category = "clarification" if number == 2 else "domain"
    required = () if number == 2 else ("knowledge",)
    value = output(category, required, required, status="cannot_answer")
    value["body"] = {
        2: "어느 센터인지 알려주시겠어요?",
        3: "해당 절차를 설명하는 자료는 찾지 못했습니다.",
        7: "자료의 안내 시간이 서로 달라 발송 시각을 확정할 수 없습니다.",
        8: "등록 위치는 확인했으나 처리 기한은 자료에 없어 확인할 수 없습니다.",
        10: "완료 예정 시각은 확인되지 않았습니다.",
    }[number]
    app.engine.results = [result(value)]
    ident = receive(app.store, app.cfg, event(), "UBOT")
    assert app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "posted"
    assert not app.slack.cards
    assert len([c for c in app.slack.calls if c[0] == "post"]) == 1


@pytest.mark.parametrize("number", [2, 7, 10], ids=lambda n: f"DG-IMAGE-{n:03}")
def test_catalog_image_boundaries(app, number):
    from dougle_bot.images import MAX_IMAGE_BYTES, MAX_TOTAL_BYTES, collect_images

    # Framing/size transport contract only, not a claim of semantic image decoding.
    count = 4 if number == 7 else 3 if number == 10 else 1
    size = MAX_TOTAL_BYTES // 3 if number == 10 else 16
    assert size <= MAX_IMAGE_BYTES
    raw = b"\xff\xd8\xff" + b"x" * (size - 5) + b"\xff\xd9"
    for n in range(count):
        app.slack.images[f"FCAT{n}"] = (raw, "image/jpeg")
    images = collect_images(
        app.slack, [{"ts": "1001.000001", "files": [file(f"FCAT{n}") for n in range(count)]}]
    )
    assert len(images) == count and all(i.data == raw for i in images)
    if number == 10:
        assert sum(len(i.data) for i in images) == MAX_TOTAL_BYTES


def test_catalog_thread_lock(app, monkeypatch):
    # Default capacity is two; the same thread must still wait and resume its session.
    original = app.engine.run
    queued = []

    def during_first(*args, **kwargs):
        if not queued:
            queued.append(
                receive(
                    app.store, app.cfg, event(ts="1002.000001", thread_ts="1001.000001"), "UBOT"
                )
            )
            assert app.store.inquiry(queued[0])["status"] == "queued"
            assert len(app.store.all("SELECT * FROM jobs WHERE status='running'")) == 1
            peer = Store(app.store.path, clock=app.store.clock)
            try:
                with peer.processing():
                    assert peer.claim_job() is None
            finally:
                peer.close()
        return original(*args, **kwargs)

    monkeypatch.setattr(app.engine, "run", during_first)
    receive(app.store, app.cfg, event(), "UBOT")
    with app.store.processing():
        assert app.worker.run_once()
    assert len(app.engine.calls) == 1
    with app.store.processing():
        assert app.worker.run_once()
    assert len(app.engine.calls) == 2 and app.engine.calls[-1][1] == SESSION
    assert app.cfg.engine.max_parallel == 4


def test_catalog_rejected_draft_not_public_history(app):
    from test_runtime import ready

    ident, *_ = ready(app)
    draft = app.store.inquiry(ident)["current_draft_id"]
    app.store.reject(ident, draft, "UAPPROVER")
    marker = "UNPOSTED_PRIVATE_DRAFT"
    with app.store.tx() as db:
        db.execute("UPDATE drafts SET body=? WHERE id=?", (marker, draft))
    receive(app.store, app.cfg, event(ts="1002.000001", thread_ts="1001.000001"), "UBOT")
    assert app.worker.run_once()
    assert marker not in app.engine.calls[-1][0]
    assert not any(c[0] == "post" for c in app.slack.calls)


@pytest.mark.parametrize("with_image", [False, True], ids=["DG-HANDOFF-024", "DG-HANDOFF-025"])
def test_catalog_unregistered_direct_messages(app, with_image):
    from slack_bolt.request import BoltRequest
    from test_boundaries import bolt

    built, executor = bolt(app)
    body = event(channel="DTEST", text="추가 결과입니다.")
    body.update(type="event_callback", event_id="EvDMTEST", api_app_id="ATEST")
    body["event"].update(type="message", channel_type="im")
    if with_image:
        body["event"]["files"] = [file()]
    built.dispatch(BoltRequest(body=body, mode="socket_mode"))
    assert not app.store.all("SELECT * FROM inquiries")
    assert not executor.calls and not app.engine.calls and not app.slack.calls


def test_catalog_long_line_delivery():
    from dougle_bot.cards import split_text

    original = "가" * 9000 + "입니다."
    with pytest.raises(BotError, match="answer_line_too_long"):
        split_text(original)
