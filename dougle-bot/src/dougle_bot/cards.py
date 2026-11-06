import hashlib
import json
import re

from .images import IMAGE_ERRORS
from .presentation import chat_text
from .safety import BotError, check_content, escape_slack

LABELS = {
    "queued": "처리 대기",
    "drafting": "답변 작성 중",
    "awaiting_approval": "게시 승인 대기",
    "needs_lookup": "개발자 조회 필요",
    "posting": "게시 중",
    "posted": "게시 완료",
    "rejected": "거절됨",
    "failed": "처리 실패",
    "post_unknown": "전송 여부 확인 필요",
}


def final_text(draft):
    body = draft["body"]
    sources = json.loads(draft["sources_json"])
    check_content(body, personal=True)
    text = chat_text(body, len(sources))
    if not text:
        raise BotError("empty_public_answer")
    return text


def preview_receipt(draft):
    return json.dumps(
        {"draft_id": draft["id"], "sha256": hashlib.sha256(final_text(draft).encode()).hexdigest()},
        sort_keys=True,
    )


def split_text(text, limit=3500):
    if len(text) <= limit:
        return [text]
    result = []
    current = ""
    fence = None
    for line in text.splitlines(keepends=True):
        if len(line) > limit - 40:
            raise BotError("answer_line_too_long")
        if len(current) + len(line) > limit - 40:
            result.append(current.rstrip() + ("\n```" if fence else ""))
            current = fence + "\n" if fence else ""
        current += line
        if line.lstrip().startswith("```"):
            fence = None if fence else line.strip()
    if current.strip():
        result.append(current.rstrip() + ("\n```" if fence else ""))
    if any(len(p) > limit for p in result):
        raise BotError("answer_too_long")
    return result


def section(text, block_id=None):
    result = {"type": "section", "text": {"type": "mrkdwn", "text": text or "내용이 없습니다."}}
    if block_id:
        result["block_id"] = block_id
    return result


def render(inquiry, draft, permalink=None):
    status = inquiry["status"]
    revision = draft["revision"] if draft else 0
    blocks = [
        section(f"*✦ dougle · {LABELS[status]}*\n문의 #{inquiry['id']} · 초안 {revision}"),
        section("질문: " + escape_slack(inquiry["question_text"][:1000])),
    ]
    if inquiry.get("error"):
        blocks.append(
            section(
                IMAGE_ERRORS.get(inquiry["error"], "확인이 필요합니다: `" + inquiry["error"] + "`")
            )
        )
    if status in ("queued", "drafting"):
        blocks.append(
            section(
                "접수했습니다. 앞선 문의가 끝나면 답변을 준비하겠습니다."
                if status == "queued"
                else "답변을 준비하고 있습니다. 완료되면 이 카드에 초안과 게시 버튼이 표시됩니다."
            )
        )
        blocks.append(
            section(
                "상태는 단계가 바뀔 때 갱신됩니다. 연결 상태는 더글 봇 메뉴의 ‘상태 확인’에서 확인하실 수 있습니다."
            )
        )
    if draft:
        if status == "needs_lookup":
            lookup = json.loads(draft["lookup_json"])
            display = f"{draft['body']}\n\n조회 대상: {lookup['target']}\n{lookup['reason']}\n\n{lookup['sql'] or '위 안내에 따라 확인하신 내용만 알려 주세요.'}\n\n최소한의 비민감 결과 요약과 조회 시각을 입력해 주세요."
            display = escape_slack(display)
        else:
            display = final_text(draft)
            blocks.append(section("*Slack에 게시될 답변*"))
        for idx in range(0, len(display), 2800):
            blocks.append(section(display[idx : idx + 2800], f"d{draft['id']}-{idx}"))
        sources = json.loads(draft["sources_json"])
        if sources:
            blocks.append({"type": "divider"})
            blocks.append(section("*검토용 출처 · Slack 답글에는 포함되지 않습니다*"))
            source_block = ""
            for idx, source in enumerate(sources, 1):
                title = re.sub(r"^(?:\s*\[(?:R)?\d+\]\s*)+", "", source["title"])
                line = escape_slack(f"[{idx}] {title} · {source['location']}")
                # Escaped URLs can grow; split safely within Slack's per-section limit.
                for offset in range(0, len(line), 2700):
                    piece = line[offset : offset + 2700]
                    if len(source_block) + len(piece) + 2 > 2800:
                        blocks.append(section(source_block))
                        source_block = ""
                    source_block += ("\n\n" if source_block else "") + piece
            if source_block:
                blocks.append(section(source_block))
        value = json.dumps(
            {"inquiry_id": inquiry["id"], "draft_id": draft["id"]}, separators=(",", ":")
        )
        actions = []

        def button(action, label):
            actions.append(
                {
                    "type": "button",
                    "text": {"type": "plain_text", "text": label},
                    "action_id": action,
                    "value": value,
                }
            )

        if status == "awaiting_approval":
            button("post", "게시")
            if len(draft["body"]) <= 3000:
                button("edit", "직접 수정")
        if status in ("awaiting_approval", "needs_lookup"):
            if status == "needs_lookup":
                button("lookup", "조회 결과 입력")
            button("reject", "거절")
        if status in ("queued", "drafting", "failed"):
            button("reject", "거절")
        if actions:
            blocks.append({"type": "actions", "elements": actions})
    if permalink:
        blocks.append(section("게시된 답변: " + permalink))
    if len(blocks) > 45:
        raise BotError("card_too_large")
    return "✦ dougle · " + LABELS[status], blocks


def modal(action, inquiry, draft):
    if action not in ("edit", "lookup"):
        raise BotError("action_unavailable")
    labels = {
        "edit": "직접 수정",
        "lookup": "조회 결과 입력",
    }
    block = {
        "type": "input",
        "block_id": "content",
        "label": {"type": "plain_text", "text": labels[action]},
        "optional": False,
        "element": {
            "type": "plain_text_input",
            "action_id": "value",
            "multiline": True,
            "max_length": 3000,
        },
    }
    if action == "edit":
        if len(draft["body"]) > 3000:
            raise BotError("edit_too_long")
        block["element"]["initial_value"] = draft["body"]
    blocks = [block]
    if action == "lookup":
        blocks.append(
            {
                "type": "input",
                "block_id": "observed_at",
                "label": {
                    "type": "plain_text",
                    "text": "조회 시각 · 예: 2026-11-06T15:00:00+09:00",
                },
                "element": {"type": "plain_text_input", "action_id": "value", "max_length": 40},
            }
        )
    return {
        "type": "modal",
        "callback_id": "dougle_" + action,
        "private_metadata": json.dumps(
            {
                "inquiry_id": inquiry["id"],
                "draft_id": draft["id"],
                "channel": inquiry["card_channel_id"],
                "ts": inquiry["card_ts"],
            }
        ),
        "title": {"type": "plain_text", "text": labels[action]},
        "submit": {"type": "plain_text", "text": "확인"},
        "close": {"type": "plain_text", "text": "취소"},
        "blocks": blocks,
    }
