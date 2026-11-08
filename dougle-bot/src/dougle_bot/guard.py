"""Claude PreToolUse hook. Deny every tool outside the explicit runtime policy."""

import json
import sys
from pathlib import Path


def permitted(policy, name, args):
    if policy.get("startup_probe"):
        return name == "StructuredOutput" or (
            name == "Skill" and args.get("skill") in policy["skills"]
        )
    # Claude-internal result formatting and tool discovery have no business write effect.
    if name in ("StructuredOutput", "ToolSearch"):
        return True
    if name not in policy["allowed"]:
        return False
    if name == "Skill":
        return args.get("skill") in policy["skills"]
    if name in ("Write", "Edit"):
        raw = args.get("file_path", "")
        if not isinstance(raw, str) or not raw:
            return False
        target = Path(raw).expanduser().resolve()
        memory_path = Path(policy["memory"]).expanduser()
        if memory_path.is_symlink():
            return False
        memory = memory_path.resolve()
        return target != memory and target.is_relative_to(memory) and not Path(raw).is_symlink()
    if name in ("Read", "Grep", "Glob"):
        raw = args.get("file_path", "") if name == "Read" else args.get("path", "")
        if not isinstance(raw, str) or not raw:
            return False
        p = Path(raw).expanduser().resolve()
        if p.is_relative_to(Path(policy["runtime_home"])):
            return False
        return not any(
            part
            in (
                "Keychains",
                ".ssh",
                ".aws",
                ".credentials.json",
                "auth.json",
                ".netrc",
                ".npmrc",
                ".pypirc",
                ".git-credentials",
            )
            or part.startswith(".env")
            for part in p.parts
        )
    return True


def main():
    try:
        policy = json.loads(Path(sys.argv[1]).read_text())
        raw = sys.stdin.buffer.read(1048577)
        if len(raw) > 1048576:
            raise ValueError()
        event = json.loads(raw)
        allow = permitted(policy, event.get("tool_name"), event.get("tool_input", {}))
    except Exception:
        allow = False
    print(
        json.dumps(
            {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "allow" if allow else "deny",
                    "permissionDecisionReason": "Dougle bot read-only tool policy",
                }
            }
        )
    )


if __name__ == "__main__":
    main()
