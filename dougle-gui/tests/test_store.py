import pytest

from dougle_gui.store import Store, StoreError, parse_topic


def test_overview_counts_topics_claims_sources_and_bindings(store_root):
    overview = Store(store_root).overview()
    assert overview["exists"] is True
    assert overview["topics"] == 3
    assert overview["claims"] == 4
    assert overview["sources"] == 2
    assert overview["source_states"] == {"partial": 1, "read": 1}
    binding = overview["bindings"][0]
    assert binding["repository"] == "github.com/example/knowledge"
    assert binding["project"] == "receiving"
    assert binding["merged"] == "abc1234def"
    assert binding["published"] == ""
    assert binding["pending"] == ""
    assert binding["operation"] == "completed"
    assert binding["operation_at"] == "2026-01-01T00:00:00Z"
    assert binding["operation_kind"] == "fetch"


def test_imported_topic_marks_every_claim_needs_recheck(store_root):
    topic = Store(store_root).topic("receiving")
    assert topic.imported is True
    assert topic.about == "입하 확정의 조건과 기록 흐름"
    assert [c.id for c in topic.claims] == ["C01", "C02"]
    assert {c.freshness for c in topic.claims} == {"needs_recheck"}
    assert topic.claims[0].title == "저장 위치"
    assert topic.claims[0].provenance == "observed"
    assert topic.claims[1].provenance == "reported"
    assert topic.claims[1].conflict is True
    assert topic.claims[0].conflict is False


def test_local_topic_uses_header_freshness_and_body_provenance(store_root):
    topic = Store(store_root).topic("rts-mail")
    assert topic.imported is False
    claim = topic.claims[0]
    assert claim.freshness == "current_at_observation"
    assert claim.provenance == "observed"
    assert "09:00" in claim.body


def test_mixed_provenance_suffix_is_kept():
    topic = parse_topic("x", "", "# X\n### C01 — 규칙 (observed: S001 / reported: S035)\n본문\n")
    assert topic.claims[0].provenance == "observed / reported"
    assert topic.claims[0].title == "규칙"


def test_legacy_collection_is_qualified(store_root):
    store = Store(store_root)
    keys = [t.key for t in store.topics()]
    assert "old~receiving" in keys and "receiving" in keys
    assert store.topic("old~receiving").title == "옛 입하 노트"


@pytest.mark.parametrize("key", ["../index", "a/b", "..", "", "x~../y", "~"])
def test_invalid_ids_are_rejected(store_root, key):
    with pytest.raises(StoreError):
        Store(store_root).topic(key)


def test_symlink_outside_store_is_not_read(store_root, tmp_path):
    secret = tmp_path / "secret.md"
    secret.write_text("# secret\n", encoding="utf-8")
    (store_root / "knowledge" / "leak.md").symlink_to(secret)
    store = Store(store_root)
    with pytest.raises(StoreError):
        store.topic("leak")
    assert "leak" not in [t.key for t in store.topics()]


def test_missing_store_is_empty_not_error(tmp_path):
    store = Store(tmp_path / "none")
    overview = store.overview()
    assert overview["exists"] is False
    assert overview["topics"] == 0
    assert store.index_text() == ""
    assert store.bindings() == []


def test_search_ranks_claims_and_sources(store_root):
    hits = Store(store_root).search("회송 메일")
    assert hits[0]["key"] == "rts-mail"
    hits = Store(store_root).search("receipt")
    assert any(h["type"] == "claim" and h["claim"] == "C01" for h in hits)
    assert any(h["type"] == "source" and h["key"] == "S001" for h in Store(store_root).search("Receiving service"))
    assert Store(store_root).search("   ") == []


def test_schedules_summarize_inquiry_states(store_root):
    schedules = Store(store_root).schedules()
    assert schedules == [
        {"id": "m1", "channel": "#qna", "status": "active", "last_success": "",
         "inquiries": {"awaiting_approval": 1, "sent": 1}}
    ]


def test_store_reading_never_writes(store_root):
    before = {p: p.stat().st_mtime_ns for p in store_root.rglob("*") if p.is_file()}
    store = Store(store_root)
    store.overview()
    store.search("입하")
    store.schedules()
    after = {p: p.stat().st_mtime_ns for p in store_root.rglob("*") if p.is_file()}
    assert before == after
