import json
import re

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
    body = re.sub(r"\n?\s*\*?✦ dougle\*?\s*$", "", draft["body"]).strip()
    sources = json.loads(draft["sources_json"])
    if sources:
        body += "\n\n출처\n" + "\n".join(
            f"[{i}] {s['title']} · {s['location']}" for i, s in enumerate(sources, 1)
        )
    check_content(body, personal=True)
    return escape_slack(body) + "\n\n✦ dougle"


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
        blocks.append(section("확인이 필요합니다: `" + inquiry["error"] + "`"))
    if draft:
        if status == "needs_lookup":
            lookup = json.loads(draft["lookup_json"])
            display = f"{draft['body']}\n\n조회 대상: {lookup['target']}\n{lookup['reason']}\n\n{lookup['sql'] or '스키마를 확인한 뒤 조회 방법을 결정해 주세요.'}\n\n최소한의 비민감 결과 요약과 조회 시각을 입력해 주세요."
            display = escape_slack(display)
        else:
            display = final_text(draft)
        for idx in range(0, len(display), 2800):
            blocks.append(section(display[idx : idx + 2800], f"d{draft['id']}-{idx}"))
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
            button("regenerate", "다시 생성")
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
    labels = {
        "edit": "직접 수정",
        "regenerate": "다시 생성",
        "lookup": "조회 결과 입력",
        "reject": "거절 사유",
    }
    block = {
        "type": "input",
        "block_id": "content",
        "label": {"type": "plain_text", "text": labels[action]},
        "optional": action == "reject",
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
