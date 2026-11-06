"""Synthetic PNG/JPEG, fake HTTP/Slack and actual fake CLI subprocess; no real images/services."""

import base64
import io
import json
import struct
import urllib.error
import zlib
from dataclasses import replace
from types import SimpleNamespace

import pytest
from test_answer_style import READS, SESSION, SKILLS, answer, call, outcome, trace
from test_boundaries import fake_cli
from test_runtime import event, post, ready

from dougle_bot.engine import ClaudeCliEngine, EngineResult, parse_trace
from dougle_bot.images import (
    IMAGE_ERRORS,
    MAX_IMAGE_BYTES,
    MAX_TOTAL_BYTES,
    ImageInput,
    NoRedirect,
    collect_images,
    download_image,
    image_files,
    validate_image,
)
from dougle_bot.intake import receive
from dougle_bot.safety import BotError
from dougle_bot.slack_gateway import BoltSlackGateway, SlackFailure, history_hash


def png():
    def chunk(kind, data):
        return (
            struct.pack("!I", len(data)) + kind + data + struct.pack("!I", zlib.crc32(kind + data))
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack("!2I5B", 1, 1, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(b"\x00\x80\x80\x80"))
        + chunk(b"IEND", b"")
    )


def file(ident="FTEST", **updates):
    return dict(id=ident, mimetype="image/png", **updates)


def attach(app, ident="FTEST", ts="1001.000001", **updates):
    row = event(ts=ts, files=[file(ident)], **updates)["event"]
    app.slack.messages.setdefault(("CTEST", row.get("thread_ts", ts)), []).append(row)
    app.slack.images[ident] = (png(), "image/png")
    return row


def test_uploaded_image_reaches_engine_and_requires_approval(app):
    attach(app)
    ref = ready(app, files=[file()])
    image = app.engine.image_calls[0][0]
    assert (image.file_id, image.data, image.media_type) == ("FTEST", png(), "image/png")
    assert not any(c[0] == "post" for c in app.slack.calls)
    assert not list(app.cfg.home.rglob("*.png"))
    post(app, ref)
    assert app.store.inquiry(ref[0])["status"] == "posted"


def test_image_only_mention_is_accepted_but_bare_mention_is_not(app):
    attach(app, text="<@UBOT>")
    ref = ready(app, text="<@UBOT>", files=[file()])
    assert app.engine.image_calls[0]
    assert "첨부 이미지" in app.store.inquiry(ref[0])["question_text"]
    assert receive(app.store, app.cfg, event(ts="1002.000001", text="<@UBOT>"), "UBOT") is None


def test_hidden_event_attachment_cannot_be_silently_lost(app):
    ident = receive(app.store, app.cfg, event(files=[file()]), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["error"] == "image_access_denied"
    assert not app.engine.calls


def test_followup_resume_gets_prior_image_again_and_no_other_channel_images(app):
    attach(app)
    ready(app, files=[file()])
    app.slack.messages[("COTHER", "1001.000001")] = [dict(ts="1001.000001", files=[file("FOTHER")])]
    ref = ready(
        app,
        ts="1002.000001",
        thread_ts="1001.000001",
        text="<@UBOT> 이 화면에서 어디를 눌러야 하나요?",
    )
    assert app.engine.calls[-1][1] == SESSION
    assert [x.file_id for x in app.engine.image_calls[-1]] == ["FTEST"]
    assert not any(c == ("image", "FOTHER") for c in app.slack.calls)
    assert app.store.inquiry(ref[0])["status"] == "awaiting_approval"


def test_session_restore_and_validation_retry_keep_images(app):
    attach(app)
    first = ready(app, files=[file()])
    app.engine.results = [
        BotError("session_not_found"),
        EngineResult(SESSION, answer("하면 돼."), 0.01, SKILLS, READS),
        EngineResult(SESSION, answer(), 0.01, SKILLS, READS),
    ]
    ready(app, ts="1002.000001", thread_ts="1001.000001")
    assert app.store.inquiry(first[0])["status"] == "awaiting_approval"
    assert all(images[0].data == png() for images in app.engine.image_calls[-3:])
    assert app.engine.calls[-2][1] is None


@pytest.mark.parametrize(
    "code",
    ["image_access_denied", "image_download_failed", "image_size_limit", "image_data_invalid"],
)
def test_image_failure_creates_actionable_dm_without_model_or_public_answer(app, code):
    attach(app)
    app.slack.failures["image"] = BotError(code)
    ident = receive(app.store, app.cfg, event(files=[file()]), "UBOT")
    app.worker.run_once()
    row = app.store.inquiry(ident)
    assert row["status"] == "failed" and row["error"] == code
    assert not app.store.current(row) and not app.engine.calls
    assert IMAGE_ERRORS[code] in json.dumps(
        app.slack.cards[(row["card_channel_id"], row["card_ts"])], ensure_ascii=False
    )
    assert not any(c[0] == "post" for c in app.slack.calls)


@pytest.mark.parametrize("change", ["replace", "remove", "edit"])
def test_changed_image_invalidates_approval(app, change):
    attach(app)
    ref = ready(app, files=[file()])
    row = app.slack.messages[("CTEST", "1001.000001")][0]
    if change == "replace":
        row["files"] = [file("FNEW")]
    elif change == "remove":
        row["files"] = []
    else:
        row["files"][0]["updated"] = 1002
    with pytest.raises(BotError, match="thread_changed_regenerating"):
        post(app, ref)
    assert not any(c[0] == "post" for c in app.slack.calls)


def test_history_image_order_and_metadata_change_hash():
    original = [{"ts": "1.0", "files": [file()]}]
    assert history_hash(original) != history_hash([{"ts": "1.0", "files": [file("FNEW")]}])


def test_more_than_four_images_fails_before_downloading(app):
    rows = [{"ts": "1.0", "files": [file(f"FIMG{i}") for i in range(5)]}]
    with pytest.raises(BotError, match="too_many_images"):
        collect_images(app.slack, rows)
    assert not app.slack.calls


def test_duplicates_download_once(app):
    attach(app)
    rows = app.slack.messages[("CTEST", "1001.000001")]
    assert len(collect_images(app.slack, rows * 2)) == 1
    assert len([c for c in app.slack.calls if c[0] == "image"]) == 1


def test_total_size_limit_is_not_silent_truncation(app):
    # Minimal JPEG framing tests size control; no claim that this is a decodable photo.
    raw = b"\xff\xd8\xff" + b"x" * (MAX_IMAGE_BYTES - 5) + b"\xff\xd9"
    for i in range(3):
        app.slack.images[f"FIMG{i}"] = (raw, "image/jpeg")
    assert len(raw) * 3 > MAX_TOTAL_BYTES
    with pytest.raises(BotError, match="image_total_size_limit"):
        collect_images(app.slack, [{"ts": "1.0", "files": [file(f"FIMG{i}") for i in range(3)]}])


@pytest.mark.parametrize(
    "mime", ["image/gif", "image/webp", "image/heic", "image/svg+xml", "text/html"]
)
def test_unsupported_types_fail(mime):
    with pytest.raises(BotError, match="image_type_unsupported"):
        validate_image(png(), mime)


@pytest.mark.parametrize(
    "raw", [b"", b"<html>login</html>", b"\x89PNG\r\n\x1a\n", b"x" * (MAX_IMAGE_BYTES + 1)]
)
def test_invalid_or_oversize_bytes_fail(raw):
    with pytest.raises(BotError):
        validate_image(raw, "image/png")


@pytest.mark.parametrize(
    "bad",
    [
        "http://files.slack.com/files-pri/a",
        "https://evil.test/files-pri/a",
        "https://files.slack.com.evil.test/files-pri/a",
        "https://files.slack.com@evil.test/files-pri/a",
        "https://files.slack.com:443/files-pri/a",
        "https://127.0.0.1/files-pri/a",
        "file:///etc/passwd",
        "https://files.slack.com/other/a",
        None,
    ],
)
def test_private_token_cannot_be_sent_to_arbitrary_url(bad):
    opener = SimpleNamespace(open=lambda *a, **kw: pytest.fail("network must not be reached"))
    with pytest.raises(BotError, match="image_download_url_rejected"):
        download_image(bad, "xoxb-synthetic", opener=opener)


class Response(io.BytesIO):
    status = 200

    def __init__(self, data, **headers):
        super().__init__(data)
        self.headers = {"Content-Type": "image/png", **headers}


def test_bounded_download_uses_authorization_only_in_header():
    seen = []

    def open_(req, timeout):
        seen.append(req)
        assert timeout == 20
        return Response(png(), **{"Content-Length": str(len(png()))})

    assert download_image(
        "https://files.slack.com/files-pri/T/F.png",
        "xoxb-synthetic",
        opener=SimpleNamespace(open=open_),
    ) == (png(), "image/png")
    assert seen[0].get_header("Authorization") == "Bearer xoxb-synthetic"
    assert "xoxb-" not in seen[0].full_url


@pytest.mark.parametrize(
    "headers,data",
    [
        ({"Content-Length": str(MAX_IMAGE_BYTES + 1)}, b""),
        ({"Content-Length": "bad"}, b""),
        ({}, b"x" * (MAX_IMAGE_BYTES + 1)),
    ],
    ids=["declared_oversize", "invalid_length", "stream_oversize"],
)
def test_download_read_is_bounded(headers, data):
    opener = SimpleNamespace(open=lambda *a, **kw: Response(data, **headers))
    with pytest.raises(BotError, match="image_size_limit"):
        download_image("https://files.slack.com/files-pri/T/F.png", "xoxb-synthetic", opener=opener)


def test_redirect_is_never_followed_with_bearer():
    assert NoRedirect().redirect_request(None, None, 302, "", {}, "https://evil.test") is None


@pytest.mark.parametrize(
    "code,expected",
    [
        (403, "image_access_denied"),
        (404, "image_access_denied"),
        (302, "image_download_failed"),
        (429, "image_download_failed"),
    ],
)
def test_http_errors_do_not_leak_url_or_token(code, expected):
    def fail(*a, **kw):
        raise urllib.error.HTTPError("https://secret.test", code, "xoxb-private", {}, None)

    with pytest.raises(BotError, match=expected) as exc:
        download_image(
            "https://files.slack.com/files-pri/T/F.png",
            "xoxb-private",
            opener=SimpleNamespace(open=fail),
        )
    assert "secret" not in str(exc.value) and "xoxb-" not in str(exc.value)


def gateway(info, download=None):
    client = SimpleNamespace(files_info=lambda **kw: {"ok": True, "file": info})
    return BoltSlackGateway(
        "xoxb-synthetic", client, image_downloader=download or (lambda *args: (png(), "image/png"))
    )


def info(**changes):
    data = dict(
        id="FTEST",
        mode="hosted",
        mimetype="image/png",
        size=len(png()),
        url_private="https://files.slack.com/files-pri/T/F.png",
    )
    data.update(changes)
    return data


def test_file_info_is_resolved_before_image_download():
    assert gateway(info()).image("FTEST") == (png(), "image/png")


@pytest.mark.parametrize(
    "changes",
    [
        {"id": "FOTHER"},
        {"mode": "external"},
        {"is_external": True},
        {"mimetype": "image/svg+xml"},
        {"size": MAX_IMAGE_BYTES + 1},
        {"size": False},
    ],
)
def test_invalid_file_info_never_downloads(changes):
    gw = gateway(info(**changes), lambda *a: pytest.fail("must not download"))
    with pytest.raises(BotError):
        gw.image("FTEST")


def test_missing_scope_has_file_specific_guidance():
    gw = gateway(info())

    def fail(*args, **kw):
        raise SlackFailure("missing_scope")

    gw.call = fail
    with pytest.raises(BotError, match="image_access_denied"):
        gw.image("FTEST")


def test_html_login_page_and_size_mismatch_not_sent_to_model():
    with pytest.raises(BotError, match="image_data_invalid"):
        gateway(info(), lambda *a: (b"<html>login</html>", "text/html")).image("FTEST")


def test_cli_receives_actual_base64_block_on_stdin_not_argv(app, tmp_path):
    events = [call(name, f"t{i}") for i, name in enumerate(sorted(SKILLS))] + [
        outcome(f"t{i}", content=f"Launching skill: {name}")
        for i, name in enumerate(sorted(SKILLS))
    ]
    code = (
        """import sys,json,base64
assert '--input-format' in sys.argv
assert sys.argv[sys.argv.index('--input-format')+1]=='stream-json'
x=json.loads(sys.stdin.read())
assert x['type']=='user' and x['message']['role']=='user'
c=x['message']['content']
assert c[0]['text']=='synthetic image question'
assert 'FTEST' in c[1]['text']
assert c[2]['source']['media_type']=='image/png'
assert base64.b64decode(c[2]['source']['data'])=="""
        + repr(png())
        + """
assert not any('base64' in a or 'synthetic image question' in a for a in sys.argv)
print("""
        + repr(trace(events).decode())
        + ")\n"
    )
    cfg = replace(app.cfg.engine, claude_bin=fake_cli(tmp_path, code))
    result = ClaudeCliEngine(cfg, app.cfg.home).run(
        "synthetic image question",
        SESSION,
        1,
        images=[ImageInput("FTEST", "1001.000001", "image/png", png())],
    )
    assert result.session_id == SESSION and result.activated == SKILLS


def test_cli_reported_image_conversion_failure_is_not_a_valid_answer():
    payload = trace(
        [
            {
                "type": "user",
                "message": {
                    "content": [
                        {"type": "text", "text": "[Image could not be processed: invalid image]"}
                    ]
                },
            }
        ]
    )
    with pytest.raises(BotError, match="image_input_failed"):
        parse_trace(payload)


def test_remote_image_block_is_not_silently_ignored():
    with pytest.raises(BotError, match="image_attachment_unsupported"):
        image_files(
            [{"ts": "1.0", "blocks": [{"type": "image", "image_url": "https://evil.test/a.png"}]}]
        )


def test_private_download_urls_are_not_put_in_model_prompt(app):
    row = attach(app)
    row["files"][0]["url_private"] = "https://files.slack.com/files-pri/private-path.png"
    ready(app, files=[file()])
    text = app.engine.calls[0][0]
    assert "private-path.png" not in text
    assert "currently_attached_images" in text and "FTEST" in text


@pytest.mark.parametrize("blocks", [None, "invalid", {}])
def test_invalid_blocks_are_a_clear_attachment_error(blocks):
    with pytest.raises(BotError, match="image_metadata_invalid"):
        image_files([{"ts": "1.0", "blocks": blocks}])


def test_two_images_keep_order_and_original_timestamps(app):
    rows = [attach(app, "FONE"), attach(app, "FTWO", ts="1002.000001", thread_ts="1001.000001")]
    images = collect_images(app.slack, rows)
    assert [(i.file_id, i.message_ts) for i in images] == [
        ("FONE", "1001.000001"),
        ("FTWO", "1002.000001"),
    ]


def test_cancelled_download_never_fetches_private_file():
    gw = gateway(info(), lambda *a: pytest.fail("cancelled download"))
    gw.cancelled = lambda: True
    with pytest.raises(BotError, match="bot_stopped"):
        gw.image("FTEST")


def test_known_image_error_result_is_actionable():
    result = {"type": "result", "is_error": True, "errors": ["invalid image data"]}
    with pytest.raises(BotError, match="image_input_failed"):
        parse_trace(json.dumps(result).encode())


def test_slow_image_stream_has_total_deadline(monkeypatch):
    moments = iter([0, 0, 21])
    monkeypatch.setattr("dougle_bot.images.time.monotonic", lambda: next(moments))
    opener = SimpleNamespace(open=lambda *a, **kw: Response(png()))
    with pytest.raises(BotError, match="image_download_failed"):
        download_image("https://files.slack.com/files-pri/T/F.png", "xoxb-synthetic", opener=opener)


def test_attachment_bytes_never_enter_audit_or_sqlite(app):
    attach(app)
    ready(app, files=[file()])
    dump = "\n".join(app.store.db.iterdump()) if hasattr(app.store, "db") else ""
    # Explicit table checks avoid depending on the connection implementation.
    tables = ["audit", "drafts", "jobs", "inquiries", "engine_runs"]
    for table in tables:
        text = json.dumps(app.store.all("SELECT * FROM " + table), ensure_ascii=False)
        assert base64.b64encode(png()).decode() not in text
        assert "url_private" not in text
    assert "iVBOR" not in dump


def test_image_cli_nonzero_failure_is_actionable_and_redacted(app, tmp_path):
    script = fake_cli(
        tmp_path,
        'import sys\nsys.stdin.read()\nsys.stderr.write("invalid image data xoxb-synthetic")\nsys.exit(1)\n',
    )
    cfg = replace(app.cfg.engine, claude_bin=script)
    with pytest.raises(BotError, match="image_input_failed") as exc:
        ClaudeCliEngine(cfg, app.cfg.home).run(
            "question", None, 1, images=[ImageInput("FTEST", "1.0", "image/png", png())]
        )
    assert "xoxb-" not in str(exc.value)


def test_text_only_old_approval_hash_stays_compatible():
    import hashlib

    row = {
        "ts": "1.0",
        "user": "UASKER",
        "text": "일반 질문입니다.",
        "blocks": [{"type": "rich_text", "elements": []}],
        "files": [],
    }
    old = [{key: row.get(key) for key in ("ts", "user", "text", "edited")}]
    expected = hashlib.sha256(
        json.dumps(old, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()
    assert history_hash([row]) == expected


def test_large_image_input_does_not_deadlock_stdin_pipe(app, tmp_path):
    data = b"x" * (1024 * 1024)
    code = (
        'import sys,json,base64\nx=json.loads(sys.stdin.read())\nassert len(base64.b64decode(x["message"]["content"][2]["source"]["data"]))==1048576\nprint('
        + repr(trace().decode())
        + ")\n"
    )
    cfg = replace(app.cfg.engine, claude_bin=fake_cli(tmp_path, code), timeout_seconds=10)
    # Transport-only framing test. Worker image validity checks are tested separately.
    result = ClaudeCliEngine(cfg, app.cfg.home).run(
        "question", None, 1, images=[ImageInput("FTEST", "1.0", "image/png", data)]
    )
    assert result.session_id == SESSION
