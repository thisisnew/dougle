"""Read-only view of the common Dougle knowledge store (~/.dougle).

The GUI never writes knowledge. Skills own every memory write; this module
only parses the plain Markdown records so they can be browsed and searched.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

FRESHNESS = ("current_at_observation", "needs_recheck", "unavailable", "superseded")
PROVENANCE = ("observed", "reported", "inferred")
MAX_NOTE_BYTES = 2_000_000
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
_CLAIM = re.compile(r"^(#{2,4})\s+(C[0-9]+[A-Za-z0-9.-]*)\s*(?:[—–:-]\s*(.*))?$")
_FIELD = re.compile(r"^([A-Za-z][A-Za-z -]{1,40}):\s*(.+)$")
_PROV_SUFFIX = re.compile(r"\s*\(((?:observed|reported|inferred)[^()]*)\)\s*$")
_CONFLICT = re.compile(r"conflict|unresolved|충돌|상충", re.IGNORECASE)


class StoreError(Exception):
    """A request that cannot be served from the store (bad ID, missing note)."""


@dataclass
class Claim:
    id: str
    title: str
    provenance: str
    freshness: str
    conflict: bool
    body: str


@dataclass
class Topic:
    key: str
    title: str
    about: str
    collection: str
    imported: bool
    header: str
    claims: list[Claim] = field(default_factory=list)
    text: str = ""


def _freshness(text: str) -> str | None:
    lowered = text.lower()
    for value in FRESHNESS:
        if value in lowered:
            return value
    return None


def _provenance(title: str, body: str) -> str:
    match = _PROV_SUFFIX.search(title)
    if match:
        kinds = [k for k in PROVENANCE if k in match.group(1)]
        return " / ".join(kinds) if kinds else "unknown"
    for line in body.splitlines():
        field_match = _FIELD.match(line.strip().lstrip("-* "))
        if field_match and field_match.group(1).strip().lower() == "provenance":
            value = field_match.group(2).strip().lower()
            for candidate in PROVENANCE:
                if value.startswith(candidate):
                    return candidate
    return "unknown"


def _field_value(lines: list[str], name: str) -> str | None:
    wanted = name.lower()
    for line in lines:
        for part in re.split(r"(?<=\.)\s+(?=[A-Z][A-Za-z -]{1,40}:)", line.strip()):
            match = _FIELD.match(part.strip().lstrip("-* "))
            if match and match.group(1).strip().lower() == wanted:
                return match.group(2).strip()
    return None


def parse_topic(key: str, collection: str, text: str) -> Topic:
    lines = text.splitlines()
    title = key
    if lines and lines[0].startswith("#"):
        title = lines[0].lstrip("#").strip() or key
    header_lines: list[str] = []
    claims: list[Claim] = []
    current: dict | None = None

    def close():
        if current is None:
            return
        body = "\n".join(current["lines"]).strip()
        own = None
        for line in current["lines"]:
            value = _field_value([line], "Freshness")
            if value:
                own = _freshness(value)
                break
        claims.append(
            Claim(
                id=current["id"],
                title=_PROV_SUFFIX.sub("", current["title"]),
                provenance=_provenance(current["title"], body),
                freshness=own or "",
                conflict=bool(_CONFLICT.search(current["title"] + "\n" + body)),
                body=body,
            )
        )

    for line in lines[1:]:
        match = _CLAIM.match(line.strip())
        if match:
            close()
            current = {"id": match.group(2), "title": (match.group(3) or "").strip(), "lines": []}
        elif current is not None:
            if line.startswith("## ") and not _CLAIM.match(line.strip()):
                close()
                current = None
            else:
                current["lines"].append(line)
        elif not claims:
            header_lines.append(line)
    close()

    local = _field_value(header_lines, "Local-Freshness")
    topic_value = _field_value(header_lines, "Freshness")
    default = (_freshness(local) if local else None) or (
        _freshness(topic_value) if topic_value else None
    )
    for claim in claims:
        if not claim.freshness:
            claim.freshness = default or "unknown"
        elif local and _freshness(local) == "needs_recheck":
            claim.freshness = "needs_recheck"

    about = _field_value(header_lines, "About") or ""
    if not about:
        for line in header_lines:
            stripped = line.strip()
            if stripped and not stripped.startswith("#") and not _FIELD.match(stripped):
                about = stripped
                break
    return Topic(
        key=key,
        title=title,
        about=about[:300],
        collection=collection,
        imported=_field_value(header_lines, "Imported-From") is not None,
        header="\n".join(header_lines).strip(),
        claims=claims,
        text=text,
    )


def _repository(value: str) -> str:
    """Repository identity without free-form notes such as "(private; ...)"."""
    value = re.sub(r"\s*\(.*$", "", value).strip()
    return re.sub(r"^https?://", "", value).rstrip("/")


def _commit(value: str | None) -> str:
    match = re.match(r"\s*([0-9a-f]{7,40})\b", value or "")
    return match.group(1) if match else ""


def _source_summary(key: str, text: str) -> dict:
    lines = text.splitlines()
    title = lines[0].lstrip("#").strip() if lines and lines[0].startswith("#") else key
    return {
        "key": key,
        "title": title,
        "state": (_field_value(lines, "State") or "unknown").split()[0].strip(".,"),
        "location": _field_value(lines, "Location") or "",
        "observed": _field_value(lines, "Observed") or "",
        "imported": _field_value(lines, "Imported-From") is not None,
    }


class Store:
    """Locate and parse knowledge in the common store and legacy collections."""

    def __init__(self, root: Path):
        self.root = Path(root).expanduser()

    def exists(self) -> bool:
        return (self.root / "index.md").is_file() or (self.root / "knowledge").is_dir()

    def _collections(self) -> list[tuple[str, Path, Path]]:
        found = [("", self.root / "knowledge", self.root / "sources")]
        workspaces = self.root / "workspaces"
        if workspaces.is_dir():
            for path in sorted(workspaces.iterdir()):
                if not path.is_dir() or path.is_symlink() or not _ID.fullmatch(path.name):
                    continue
                notes = path / "knowledge" if (path / "knowledge").is_dir() else path / "topics"
                found.append((path.name, notes, path / "sources"))
        return found

    def _inside(self, path: Path) -> bool:
        try:
            path.resolve().relative_to(self.root.resolve())
        except (OSError, ValueError):
            return False
        return True

    def _read(self, path: Path) -> str:
        if path.is_symlink() or not path.is_file() or not self._inside(path):
            raise StoreError("not_found")
        if path.stat().st_size > MAX_NOTE_BYTES:
            raise StoreError("too_large")
        return path.read_text(encoding="utf-8", errors="replace")

    def _notes(self, kind: str):
        for collection, notes, sources in self._collections():
            directory = notes if kind == "topic" else sources
            if not directory.is_dir():
                continue
            for path in sorted(directory.glob("*.md")):
                stem = path.stem
                if not _ID.fullmatch(stem):
                    continue
                key = f"{collection}~{stem}" if collection else stem
                yield key, collection, path

    def _resolve(self, kind: str, key: str) -> tuple[str, Path]:
        collection, _, stem = key.rpartition("~")
        if not _ID.fullmatch(stem) or (collection and not _ID.fullmatch(collection)):
            raise StoreError("invalid_id")
        for name, notes, sources in self._collections():
            if name == collection:
                return collection, (notes if kind == "topic" else sources) / f"{stem}.md"
        raise StoreError("not_found")

    def topics(self) -> list[Topic]:
        result = []
        for key, collection, path in self._notes("topic"):
            try:
                result.append(parse_topic(key, collection, self._read(path)))
            except StoreError:
                continue
        return result

    def topic(self, key: str) -> Topic:
        collection, path = self._resolve("topic", key)
        return parse_topic(key, collection, self._read(path))

    def sources(self) -> list[dict]:
        result = []
        for key, collection, path in self._notes("source"):
            try:
                summary = _source_summary(key, self._read(path))
            except StoreError:
                continue
            summary["collection"] = collection
            result.append(summary)
        return result

    def source(self, key: str) -> dict:
        collection, path = self._resolve("source", key)
        text = self._read(path)
        summary = _source_summary(key, text)
        summary.update(collection=collection, text=text)
        return summary

    def index_text(self) -> str:
        path = self.root / "index.md"
        return self._read(path) if path.is_file() else ""

    def bindings(self) -> list[dict]:
        sharing = self.root / "sharing"
        result = []
        if not sharing.is_dir():
            return result
        for path in sorted(sharing.iterdir()):
            binding = path / "binding.md"
            if not path.is_dir() or path.is_symlink() or not binding.is_file():
                continue
            lines = self._read(binding).splitlines()
            operation = path / "operation.md"
            status = ""
            kind = ""
            if operation.is_file():
                op_lines = self._read(operation).splitlines()
                status = _field_value(op_lines, "Status") or ""
                heading = (op_lines[0] if op_lines else "").lower()
                kind = next((k for k in ("fetch", "publish") if k in heading), "")
            package = _field_value(lines, "Package path") or ""
            project = _field_value(lines, "Project-ID") or package.rstrip("/").rsplit("/", 1)[-1]
            state, _, at = status.partition(" ")
            pending = _field_value(lines, "Pending operation") or ""
            result.append(
                {
                    "id": path.name,
                    "repository": _repository(_field_value(lines, "Repository") or ""),
                    "branch": _field_value(lines, "Branch") or "",
                    "package": package,
                    "project": project,
                    "merged": _commit(_field_value(lines, "Last fully merged commit")),
                    "published": _commit(_field_value(lines, "Last published commit")),
                    "pending": "" if pending.lower().startswith("none") else pending,
                    "operation": state,
                    "operation_at": at.strip(),
                    "operation_kind": kind,
                }
            )
        return result

    def schedules(self) -> list[dict]:
        root = self.root / "schedules"
        result = []
        if not root.is_dir():
            return result
        for path in sorted(root.iterdir()):
            monitor = path / "monitor.md"
            if not path.is_dir() or path.is_symlink() or not monitor.is_file():
                continue
            lines = self._read(monitor).splitlines()
            states: dict[str, int] = {}
            inquiries = path / "inquiries"
            if inquiries.is_dir():
                for note in inquiries.glob("*.md"):
                    try:
                        value = _field_value(self._read(note).splitlines(), "State") or "unknown"
                    except StoreError:
                        continue
                    state = value.split()[0].strip(".,")
                    states[state] = states.get(state, 0) + 1
            result.append(
                {
                    "id": path.name,
                    "channel": _field_value(lines, "Channel") or "",
                    "status": (_field_value(lines, "Status") or "unknown").split()[0].strip(".,"),
                    "last_success": _field_value(lines, "Last successful scan") or "",
                    "inquiries": states,
                }
            )
        return result

    def overview(self) -> dict:
        topics = self.topics()
        freshness: dict[str, int] = {}
        conflicts = 0
        claims = 0
        for topic in topics:
            for claim in topic.claims:
                claims += 1
                freshness[claim.freshness] = freshness.get(claim.freshness, 0) + 1
                conflicts += claim.conflict
        sources = self.sources()
        states: dict[str, int] = {}
        for source in sources:
            states[source["state"]] = states.get(source["state"], 0) + 1
        return {
            "root": str(self.root),
            "exists": self.exists(),
            "topics": len(topics),
            "claims": claims,
            "sources": len(sources),
            "freshness": freshness,
            "conflicts": conflicts,
            "source_states": states,
            "bindings": self.bindings(),
        }

    def search(self, query: str, limit: int = 60) -> list[dict]:
        terms = [term for term in query.lower().split() if term]
        if not terms:
            return []
        hits: list[tuple[int, dict]] = []

        def score(text: str) -> int:
            lowered = text.lower()
            if not all(term in lowered for term in terms):
                return 0
            return sum(lowered.count(term) for term in terms)

        def snippet(text: str) -> str:
            lowered = text.lower()
            at = lowered.find(terms[0])
            start = max(0, at - 60)
            return ("…" if start else "") + " ".join(text[start : at + 140].split())

        for topic in self.topics():
            head = score(topic.title + " " + topic.about)
            if head:
                hits.append((head * 5, {"type": "topic", "key": topic.key, "title": topic.title,
                                        "snippet": topic.about}))
            for claim in topic.claims:
                value = score(claim.id + " " + claim.title + "\n" + claim.body)
                if value:
                    hits.append((value + 3 * score(claim.title), {
                        "type": "claim", "key": topic.key, "claim": claim.id,
                        "title": f"{topic.title} > {claim.id} {claim.title}",
                        "freshness": claim.freshness, "snippet": snippet(claim.body or claim.title),
                    }))
        for summary in self.sources():
            try:
                text = self.source(summary["key"])["text"]
            except StoreError:
                continue
            value = score(text)
            if value:
                hits.append((value, {"type": "source", "key": summary["key"],
                                     "title": summary["title"], "snippet": snippet(text)}))
        hits.sort(key=lambda item: -item[0])
        return [hit for _, hit in hits[:limit]]
