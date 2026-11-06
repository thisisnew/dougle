import hashlib
import json
import re

from .images import IMAGE_ERRORS
from .presentation import chat_text
from .progress import progress_text
from .safety import BotError, check_content, escape_slack

RESEARCH_ERRORS = {
    "invalid_verification": "AI가 반환한 근거 확인 기록의 형식이 올바르지 않습니다. 권한 오류로 확인된 것은 아니며 이 답변은 게시할 수 없습니다.",
    "verification_requirements_missing": "질문에 필요한 확인 항목이 답변 기록에서 누락되었습니다. 이 답변은 게시할 수 없습니다.",
    "required_read_not_verified": "답변이 인용한 코드·자료 조회를 이번 실행의 성공 기록과 연결하지 못했습니다. 실제 조회 누락 또는 기록 불일치일 수 있으며 이 답변은 게시할 수 없습니다.",
    "invalid_read_trace": "자료 조회 실행 기록이 중복되거나 잘못되어 근거를 검증하지 못했습니다. 이 답변은 게시할 수 없습니다.",
    "engine_timeout": "설정된 처리 시간을 초과해 답변 생성을 중단했습니다. 자동으로 다시 실행하지 않았으며 채널에 답변을 게시하지 않았습니다.",
}

LABELS = {
    "queued": "처리 대기",
    "drafting": "답변 작성 중",
    "awaiting_approval": "게시 승인 대기",
    "needs_lookup": "개발자 조회 필요",
    "posting": "게시 중",
    "posted": "게시 완료",
    "rejected": "거절됨",
    "cancelled": "중단됨",
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
    blocks = [
        {"type": "header", "text": {"type": "plain_text", "text": LABELS[status]}},
        {"type": "context", "elements": [{"type": "plain_text", "text": f"문의 #{inquiry['id']}"}]},
        section("*질문*"),
        {
            "type": "section",
            "text": {
                "type": "plain_text",
                "text": inquiry["question_text"][:1000] or "질문 내용이 없습니다.",
            },
        },
        {"type": "divider"},
    ]
    if inquiry.get("error"):
        blocks.append(
            section(
                (IMAGE_ERRORS | RESEARCH_ERRORS).get(
                    inquiry["error"], "확인이 필요합니다: `" + inquiry["error"] + "`"
                )
            )
        )
    if status in ("queued", "drafting"):
        blocks.append(
            section(
                "접수했습니다. 앞선 문의가 끝나면 답변을 준비하겠습니다."
                if status == "queued"
                else progress_text(inquiry["progress"])
                if inquiry.get("progress")
                else "답변을 준비하고 있습니다. 완료되면 이 카드에 답변과 게시 버튼이 표시됩니다."
            )
        )
        blocks.append(
            section("약 30초마다 갱신합니다. 아래 ‘중단’으로 이 문의만 멈출 수 있습니다.")
        )
    if status in ("queued", "drafting") and inquiry.get("job_id"):
        blocks.append(
            {
                "type": "actions",
                "elements": [
                    {
                        "type": "button",
                        "text": {"type": "plain_text", "text": "중단"},
                        "action_id": "cancel",
                        "value": json.dumps(
                            {"inquiry_id": inquiry["id"], "job_id": inquiry["job_id"]}
                        ),
                    }
                ],
            }
        )
    if status == "cancelled":
        blocks.append(
            section(
                "이 문의의 처리를 중단했습니다. 실행 중인 AI에는 종료를 요청하며, 늦게 도착하는 답변은 게시하지 않습니다. 다른 문의는 계속 처리합니다."
            )
        )
    if draft and status not in ("queued", "drafting", "cancelled"):
        if status == "needs_lookup":
            blocks.append(section("*확인이 필요한 내용*"))
            lookup = json.loads(draft["lookup_json"])
            display = f"{draft['body']}\n\n조회 대상: {lookup['target']}\n{lookup['reason']}\n\n{lookup['sql'] or '위 안내에 따라 확인하신 내용만 알려 주세요.'}\n\n최소한의 비민감 결과 요약과 조회 시각을 입력해 주세요."
            display = escape_slack(display)
        else:
            display = final_text(draft)
            blocks.append(section("*답변*"))
        for idx in range(0, len(display), 2800):
            blocks.append(section(display[idx : idx + 2800], f"d{draft['id']}-{idx}"))
        answer_end = len(blocks)
        sources = json.loads(draft["sources_json"])
        if sources:
            blocks.append({"type": "divider"})
            blocks.append(
                {
                    "type": "context",
                    "elements": [
                        {"type": "plain_text", "text": "확인한 자료 · 승인자에게만 표시됩니다"}
                    ],
                }
            )
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
            actions[-1]["style"] = "primary"
            if len(draft["body"]) <= 3000:
                button("edit", "직접 수정")
        if status in ("awaiting_approval", "needs_lookup"):
            if status == "needs_lookup":
                button("lookup", "조회 결과 입력")
            button("reject", "거절")
        if status == "failed":
            button("reject", "거절")
        if actions:
            blocks.insert(answer_end, {"type": "actions", "elements": actions})
    if permalink:
        blocks.append(section("게시된 답변: " + permalink))
    if len(blocks) > 45:
        raise BotError("card_too_large")
    return "dougle · " + LABELS[status] + " · " + inquiry["question_text"][:100], blocks


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
