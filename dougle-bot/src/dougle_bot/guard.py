"""Claude PreToolUse hook. Deny every tool outside the explicit runtime policy."""

import json
import sys
from pathlib import Path

from .memory import STAGED_FILES, read_input, redirect, safe_path, session_area


def registered_local_target(target, roots):
    """Return the canonical target within a canonical registered root, or None.

    Filesystem aliases such as macOS /tmp are valid. This is a point-in-time
    containment check, not protection against a symlink swap after the check.
    Callers recording evidence should retain this runtime-computed identity.
    """
    try:
        if not isinstance(target, (str, Path)) or not isinstance(roots, (list, tuple, set)):
            return None
        path = Path(target).expanduser()
        if not path.is_absolute() or ".." in path.parts:
            return None
        canonical = path.resolve()
        # Python 3.13 non-strict resolve may leave a symlink loop unresolved.
        if any(part.is_symlink() for part in (canonical, *canonical.parents)):
            return None
        for root in roots:
            if not isinstance(root, (str, Path)):
                continue
            registered = Path(root).expanduser()
            if not registered.is_absolute() or ".." in registered.parts:
                continue
            canonical_root = registered.resolve()
            if any(part.is_symlink() for part in (canonical_root, *canonical_root.parents)):
                continue
            if canonical.is_relative_to(canonical_root):
                return str(canonical)
    except (OSError, ValueError, RuntimeError):
        return None
    return None


def _original_code_allowed(policy, name, raw_path, resolved):
    source_policy = policy.get("source_policy")
    if name not in {"Read", "Grep"} or not isinstance(source_policy, dict):
        return True
    if not (
        source_policy.get("github")
        or source_policy.get("local")
        or not source_policy.get("complete")
    ):
        return True
    from .evidence import CODE_SUFFIXES

    if (
        raw_path.suffix.lower() not in CODE_SUFFIXES
        and resolved.suffix.lower() not in CODE_SUFFIXES
    ):
        return True
    memory = Path(policy["memory"]).expanduser().resolve()
    if resolved.is_relative_to(memory):
        return True  # Memory notes are not original-code proof; preserve authorized reads.
    return registered_local_target(raw_path, source_policy.get("local", [])) is not None


def _owned_files(policy, path):
    if not policy.get("memory_session"):
        return False
    files = Path(policy["memory_session"]) / STAGED_FILES
    if not path.is_relative_to(files):
        return False
    session_area(policy)
    if path == files:
        return True
    safe_path(files, path.relative_to(files))
    return True


def _sensitive_path(path):
    return any(
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
        for part in path.parts
    )


def permitted(policy, name, args):
    try:
        return _permitted(policy, name, args)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError):
        return False
    except Exception:
        # Memory policy failures contain only internal error codes; never expose paths.
        return False


def _permitted(policy, name, args):
    if policy.get("memory_writes_denied") and name in {"Write", "Edit"}:
        return False
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
        raw_path = Path(raw).expanduser()
        if not raw_path.is_absolute() or ".." in raw_path.parts or _sensitive_path(raw_path):
            return False
        if _owned_files(policy, raw_path):
            return raw_path != session_area(policy) / STAGED_FILES
        target = raw_path.resolve()
        memory_path = Path(policy["memory"]).expanduser()
        if memory_path.is_symlink():
            return False
        memory = memory_path.resolve()
        if target.is_relative_to(Path(policy["runtime_home"]).expanduser().resolve()):
            return False
        if not raw_path.is_relative_to(memory) or target == memory:
            return False
        safe_path(memory, raw_path.relative_to(memory))
        return True
    if name in ("Read", "Grep", "Glob"):
        raw = args.get("file_path", "") if name == "Read" else args.get("path", "")
        if not isinstance(raw, str) or not raw:
            return False
        raw_path = Path(raw).expanduser()
        if ".." in raw_path.parts:
            return False
        p = raw_path.resolve()
        if _sensitive_path(raw_path) or _sensitive_path(p):
            return False
        runtime = Path(policy["runtime_home"]).expanduser().resolve()
        if p.is_relative_to(runtime):
            return _owned_files(policy, raw_path)
        if name != "Read" and runtime.is_relative_to(p):
            return False
        memory = Path(policy["memory"]).expanduser()
        if raw_path.is_relative_to(memory):
            if memory.is_symlink():
                return False
            if raw_path != memory:
                safe_path(memory, raw_path.relative_to(memory))
        return _original_code_allowed(policy, name, raw_path, p)
    return True


def decision(policy, event):
    """Fail closed unless every memory mutation has an owned staging destination."""
    name, args = event.get("tool_name"), event.get("tool_input", {})
    result = {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": "Dougle bot read-only tool policy",
    }
    try:
        if policy.get("memory_writes_denied") and name in {"Write", "Edit"}:
            return {"hookSpecificOutput": result}
        if not permitted(policy, name, args):
            return {"hookSpecificOutput": result}
        if name in {"Write", "Edit"}:
            result["updatedInput"] = redirect(policy, name, args)
        elif name == "Read" and policy.get("memory_session"):
            path = Path(args["file_path"]).expanduser()
            memory = Path(policy["memory"]).expanduser().resolve()
            if path.is_relative_to(memory):
                result["updatedInput"] = read_input(policy, args)
            elif _owned_files(policy, path):
                result["updatedInput"] = redirect(policy, name, args)
        elif name == "Grep" and policy.get("memory_session"):
            path = Path(args["path"]).expanduser()
            memory = Path(policy["memory"]).expanduser().resolve()
            owned = _owned_files(policy, path)
            if path.is_relative_to(memory) or owned:
                if path.is_file():
                    reader = redirect if owned else read_input
                    updated = (
                        reader(policy, "Read", {"file_path": str(path)})
                        if owned
                        else reader(policy, {"file_path": str(path)})
                    )
                    result["updatedInput"] = {**args, "path": updated["file_path"]}
                elif (
                    not owned
                    and args.get("output_mode", "files_with_matches") != "files_with_matches"
                ):
                    # Directory discovery is not a snapshot of content. Require exact
                    # reads before relying on shared memory; never hash/copy the corpus.
                    return {"hookSpecificOutput": result}
        result["permissionDecision"] = "allow"
    except Exception:
        result.pop("updatedInput", None)
    return {"hookSpecificOutput": result}


def main():
    try:
        policy = json.loads(Path(sys.argv[1]).read_text())
        raw = sys.stdin.buffer.read(1048577)
        if len(raw) > 1048576:
            raise ValueError()
        event = json.loads(raw)
        result = decision(policy, event)
    except Exception:
        result = {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": "Dougle bot read-only tool policy",
            }
        }
    print(json.dumps(result))


if __name__ == "__main__":
    main()
