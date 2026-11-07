import json
from dataclasses import replace

import pytest
from test_runtime import event

from dougle_bot.intake import receive
from dougle_bot.monitor import Monitor
from dougle_bot.progress import Progress, StreamProgress, safe_profile


def test_pipeline_logs_stages_counts_and_final_state_without_payload(app):
    cfg = replace(app.cfg, slack=replace(app.cfg.slack, require_approval=False))
    app.cfg = app.worker.config = app.actions.config = cfg
    ident = receive(app.store, cfg, event(), "UBOT")
    app.worker.notify(ident)
    app.worker.run_once()
    text = (cfg.home / "progress.log").read_text()
    assert f"문의 #{ident}" in text and "처리 대기" in text and "답변 게시 완료" in text
    assert app.store.inquiry(ident)["question_text"] not in text
    assert (cfg.home / "progress.log").stat().st_mode & 0o777 == 0o600


def test_stream_reports_code_live_and_no_content(tmp_path):
    now = [0.0]
    monitor = Monitor(tmp_path, clock=lambda: now[0])
    progress = Progress(clock=lambda: now[0], observer=lambda x: monitor.emit(42, x))
    stream = StreamProgress(progress.activity)
    for name, args, label in [
        ("Read", {"file_path": "/secret/Email.java"}, "원본 코드 확인"),
        ("mcp__example__execute_sql_readonly", {"query": "SECRET SQL"}, "운영 데이터 조회"),
    ]:
        now[0] += 2
        stream.feed(
            (
                json.dumps(
                    {
                        "type": "assistant",
                        "message": {"content": [{"type": "tool_use", "name": name, "input": args}]},
                    }
                )
                + "\n"
            ).encode()
        )
        assert label in (tmp_path / "progress.log").read_text()
    text = (tmp_path / "progress.log").read_text()
    assert "/secret" not in text and "SECRET" not in text


def test_quiet_heartbeat_is_honest_and_rate_limited(tmp_path):
    now = [0.0]
    monitor = Monitor(tmp_path, clock=lambda: now[0])
    progress = Progress(clock=lambda: now[0])
    for n in [0, 1, 2, 9, 10, 130]:
        now[0] = n
        monitor.emit(1, progress.snapshot())
    lines = (tmp_path / "progress.log").read_text().splitlines()
    assert len(lines) == 3 and "새 진행 신호 없음" in lines[-1] and "130초 전" in lines[-1]


def test_rotation_retains_bounded_files(tmp_path):
    monitor = Monitor(tmp_path)
    monitor.max_bytes = 220
    for n in range(20):
        monitor.emit(n + 1, Progress().snapshot(), "posted")
    assert {p.name for p in tmp_path.iterdir()} == {
        "progress.log",
        "progress.log.1",
        "progress.log.2",
    }
    assert all(p.stat().st_size <= 220 for p in tmp_path.iterdir())


@pytest.mark.parametrize("name", ["progress.log", "progress.log.1", "progress.log.2"])
def test_log_symlink_is_not_followed(tmp_path, name):
    original = tmp_path / "original"
    original.write_text("unchanged")
    (tmp_path / name).symlink_to(original)
    Monitor(tmp_path).emit(1, Progress().snapshot())
    assert original.read_text() == "unchanged"


def test_invalid_arbitrary_text_does_not_enter_log(tmp_path):
    monitor = Monitor(tmp_path)
    value = Progress().snapshot()
    value["stage"] = "secret\nforged"
    monitor.emit(1, value)
    assert not (tmp_path / "progress.log").exists()
    monitor.emit(1, Progress().snapshot(), "secret\nforged")
    assert "secret" not in (tmp_path / "progress.log").read_text()


def test_observer_failure_never_breaks_progress():
    def broken(value):
        raise OSError("disk full")

    progress = Progress(observer=broken)
    progress.phase("code")
    progress.activity("tool_result")
    assert progress.snapshot()["completed_tools"] == 1


def test_old_timing_profiles_remain_readable():
    value = Progress().profile()
    del value["observed_stage_ms"]["code"]
    del value["observed_stage_ms"]["live"]
    assert safe_profile(json.dumps(value))["observed_stage_ms"]["code"] == 0


def test_unwritable_log_does_not_prevent_answer(app):
    (app.cfg.home / "progress.log").mkdir()
    ident = receive(app.store, app.cfg, event(), "UBOT")
    app.worker.run_once()
    assert app.store.inquiry(ident)["status"] == "awaiting_approval"


def test_parallel_lines_keep_inquiry_identity(tmp_path):
    from concurrent.futures import ThreadPoolExecutor

    monitor = Monitor(tmp_path)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda n: monitor.emit(n, Progress().snapshot(), "posted"), range(1, 31)))
    lines = (tmp_path / "progress.log").read_text().splitlines()
    assert len(lines) == 30
    assert {int(line.split("문의 #")[1].split(" |")[0]) for line in lines} == set(range(1, 31))
