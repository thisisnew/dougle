"""Slack presentation only: never summarize or rewrite business facts in the renderer."""

import re

from .safety import escape_slack


def chat_text(body, source_count):
    # Legacy drafts may contain a footer or citation markers. Do not touch code or quotations.
    body = re.sub(r"\n?\s*\*{0,2}✦ dougle\*{0,2}\s*$", "", body).strip()
    blocks = re.split(
        r"(```[^\n]*\n.*?(?:```|\Z)|`[^`\n]+`|“[^”\n]*”|「[^」\n]*」|(?m:^>[^\n]*))",
        body,
        flags=re.S,
    )
    rendered = []
    for index, block in enumerate(blocks):
        if index % 2:
            rendered.append(escape_slack(block))
            continue

        # Strip only registered citation numbers in narrative position; arr[1] stays intact.
        def citations(match):
            return re.sub(
                r"\[(?:R)?([1-9]\d*)\]",
                lambda m: "" if int(m[1]) <= source_count else m[0],
                match[0],
            )

        block = re.sub(r"(?<![\w\]])(?:\[(?:R)?[1-9]\d*\])+(?=\s|[.,;:!?。]|$)", citations, block)
        block = escape_slack(block)
        block = re.sub(r"\*\*([^*\n]+)\*\*", r"*\1*", block)
        block = re.sub(r"(?m)^#{1,6}\s+(.+)$", lambda m: "*" + m[1].strip("*") + "*", block)
        block = re.sub(r"(?m)^([ \t]*)[-*]\s+", r"\1• ", block)
        block = re.sub(r"\n{3,}", "\n\n", block)
        rendered.append(block)
    return "".join(rendered).strip()


def reaction_name(status, configured):
    if not configured:
        return None
    active = "dougle" if configured in ("eyes", "hourglass_flowing_sand") else configured
    return {
        "queued": active,
        "drafting": active,
        "awaiting_approval": "memo",
        "needs_lookup": "mag",
        "posting": active,
        "posted": "white_check_mark",
        "failed": "warning",
        "post_unknown": "warning",
        "rejected": "no_entry_sign",
    }[status]
