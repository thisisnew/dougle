"""Local-only HTTP server for the Dougle GUI.

Binds 127.0.0.1 only. The Host header must name the local address (DNS
rebinding), a POST must carry a local or no Origin and a JSON body (which a
cross-site form cannot send without a CORS preflight this server never
approves), and no CORS headers are returned, so another web page in the
browser cannot read knowledge or start a run. An optional access token adds
protection against other local programs.
"""

from __future__ import annotations

import hmac
import json
import re
from dataclasses import asdict
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources
from urllib.parse import parse_qs, unquote, urlparse

from . import __version__
from .bot import bot_status
from .remote import Remote, RemoteError
from .runs import DEFAULT_ALLOWED_TOOLS, RunError, Runner
from .store import Store, StoreError

STATIC = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/app.css": ("app.css", "text/css; charset=utf-8"),
}
SSE_IDLE_SECONDS = 15
MAX_BODY = 64_000
_KEY = r"([A-Za-z0-9._~-]{1,260})"


class App:
    def __init__(
        self,
        store: Store,
        runner: Runner,
        *,
        bot_bin: str = "dougle-bot",
        token: str | None = None,
        remote: Remote | None = None,
        approvals: bool = True,
    ):
        self.store = store
        self.runner = runner
        self.bot_bin = bot_bin
        self.token = token
        self.remote = remote or Remote()
        self.approvals = approvals
        self.port = 0


def _topic_json(topic, full: bool) -> dict:
    data = {
        "key": topic.key,
        "title": topic.title,
        "about": topic.about,
        "collection": topic.collection,
        "imported": topic.imported,
        "claims": len(topic.claims),
        "freshness": {},
        "conflicts": sum(c.conflict for c in topic.claims),
    }
    for claim in topic.claims:
        data["freshness"][claim.freshness] = data["freshness"].get(claim.freshness, 0) + 1
    if full:
        data["header"] = topic.header
        data["claims"] = [asdict(claim) for claim in topic.claims]
    return data


def make_handler(app: App):
    class Handler(BaseHTTPRequestHandler):
        server_version = f"dougle-gui/{__version__}"
        protocol_version = "HTTP/1.1"

        def log_message(self, format, *args):  # noqa: A002 - stdlib signature
            return

        # ---- helpers -------------------------------------------------
        def _host_ok(self) -> bool:
            host = (self.headers.get("Host") or "").lower()
            return host in {f"127.0.0.1:{app.port}", f"localhost:{app.port}"}

        def _send(self, status: int, body: bytes, ctype: str, extra: dict | None = None):
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            for key, value in (extra or {}).items():
                self.send_header(key, value)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, status: int, data) -> None:
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self._send(status, body, "application/json; charset=utf-8")

        def _error(self, status: int, code: str) -> None:
            self._json(status, {"error": code})

        def _authorized(self, query: dict) -> bool:
            if app.token is None:
                return True
            given = self.headers.get("X-Dougle-Token") or ""
            if not given and self.command == "GET":
                given = (query.get("token") or [""])[0]
            return hmac.compare_digest(given.encode(), app.token.encode())

        def _body(self) -> dict:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                raise RunError("body_too_large", 413)
            if (self.headers.get("Content-Type") or "").split(";")[0] != "application/json":
                raise RunError("json_required", 415)
            try:
                data = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError as exc:
                raise RunError("invalid_json") from exc
            if not isinstance(data, dict):
                raise RunError("invalid_json")
            return data

        # ---- routing -------------------------------------------------
        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            if not self._host_ok():
                return self._error(HTTPStatus.FORBIDDEN, "invalid_host")
            url = urlparse(self.path)
            if url.path in STATIC:
                name, ctype = STATIC[url.path]
                body = resources.files("dougle_gui").joinpath("static", name).read_bytes()
                csp = ("default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                       "script-src 'self'; connect-src 'self'; frame-ancestors 'none'")
                return self._send(HTTPStatus.OK, body, ctype, {"Content-Security-Policy": csp})
            if not url.path.startswith("/api/"):
                return self._error(HTTPStatus.NOT_FOUND, "not_found")
            query = parse_qs(url.query)
            if not self._authorized(query):
                return self._error(HTTPStatus.UNAUTHORIZED, "invalid_token")
            try:
                self._get(url.path, query)
            except StoreError as exc:
                self._error(HTTPStatus.NOT_FOUND if str(exc) == "not_found" else HTTPStatus.BAD_REQUEST, str(exc))
            except (RunError, RemoteError) as exc:
                self._error(exc.status, exc.code)

        def _get(self, path: str, query: dict):
            store = app.store
            if path == "/api/config":
                return self._json(200, {
                    "version": __version__,
                    "store": str(store.root),
                    "claude": app.runner.claude_path(),
                    "allowed_tools": list(app.runner.allowed_tools),
                    "default_tools": app.runner.allowed_tools == DEFAULT_ALLOWED_TOOLS,
                })
            if path == "/api/overview":
                return self._json(200, store.overview())
            if path == "/api/index":
                return self._json(200, {"text": store.index_text()})
            if path == "/api/topics":
                return self._json(200, [_topic_json(t, False) for t in store.topics()])
            if match := re.fullmatch(r"/api/topics/" + _KEY, path):
                topic = store.topic(unquote(match.group(1)))
                return self._json(200, _topic_json(topic, True) | {"text": topic.text})
            if path == "/api/sources":
                return self._json(200, store.sources())
            if match := re.fullmatch(r"/api/sources/" + _KEY, path):
                return self._json(200, store.source(unquote(match.group(1))))
            if path == "/api/search":
                return self._json(200, store.search((query.get("q") or [""])[0][:200]))
            if path == "/api/remote/packages":
                return self._json(200, app.remote.packages((query.get("repo") or [""])[0][:300]))
            if path == "/api/bot":
                return self._json(200, {"bot": bot_status(app.bot_bin), "schedules": store.schedules()})
            if path == "/api/runs":
                runs = sorted(app.runner.runs.values(), key=lambda r: -r.created)
                return self._json(200, [r.summary() for r in runs])
            if match := re.fullmatch(r"/api/runs/([0-9a-f]{12})", path):
                run = app.runner.runs.get(match.group(1))
                if run is None:
                    raise RunError("run_not_found", 404)
                return self._json(200, run.summary() | {"log": run.events})
            if match := re.fullmatch(r"/api/runs/([0-9a-f]{12})/events", path):
                return self._events(match.group(1), int((query.get("after") or ["0"])[0] or 0))
            raise RunError("not_found", 404)

        def _events(self, run_id: str, after: int):
            run = app.runner.runs.get(run_id)
            if run is None:
                raise RunError("run_not_found", 404)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            index = max(0, after)
            try:
                while True:
                    with run.cond:
                        if index >= len(run.events) and not run.closed:
                            run.cond.wait(SSE_IDLE_SECONDS)
                        pending = run.events[index:]
                        finished = run.closed
                    for event in pending:
                        index += 1
                        payload = json.dumps(event | {"seq": index}, ensure_ascii=False)
                        self.wfile.write(f"id: {index}\ndata: {payload}\n\n".encode())
                    if not pending:
                        self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
                    if finished and index >= len(run.events):
                        self.wfile.write(b"event: end\ndata: {}\n\n")
                        self.wfile.flush()
                        return
            except (BrokenPipeError, ConnectionResetError):
                return

        def do_POST(self):
            if not self._host_ok():
                return self._error(HTTPStatus.FORBIDDEN, "invalid_host")
            origin = self.headers.get("Origin")
            if origin and origin not in {f"http://127.0.0.1:{app.port}", f"http://localhost:{app.port}"}:
                return self._error(HTTPStatus.FORBIDDEN, "invalid_origin")
            url = urlparse(self.path)
            if url.path == "/api/internal/approvals":
                # Called by the approval MCP server, which proves itself with the per-run secret.
                try:
                    data = self._body()
                    decision = app.runner.request_approval(
                        str(data.get("run", "")), str(data.get("secret", "")), str(data.get("tool", "")),
                        data.get("input") if isinstance(data.get("input"), dict) else {},
                    )
                    return self._json(200, decision)
                except RunError as exc:
                    return self._error(exc.status, exc.code)
            if not self._authorized({}):
                return self._error(HTTPStatus.UNAUTHORIZED, "invalid_token")
            try:
                data = self._body()
                if match := re.fullmatch(r"/api/runs/([0-9a-f]{12})/approvals/([0-9a-f]{8})", url.path):
                    return self._json(200, app.runner.decide(match.group(1), match.group(2), str(data.get("decision", ""))))
                if url.path == "/api/runs":
                    session = data.get("session")
                    if session is not None and not (isinstance(session, str) and re.fullmatch(r"[0-9a-f-]{8,64}", session)):
                        raise RunError("invalid_session")
                    run = app.runner.start(str(data.get("kind", "")), str(data.get("input", "")), session)
                    return self._json(201, run.summary())
                if match := re.fullmatch(r"/api/runs/([0-9a-f]{12})/cancel", url.path):
                    return self._json(200, app.runner.cancel(match.group(1)).summary())
                if match := re.fullmatch(r"/api/runs/([0-9a-f]{12})/confirm", url.path):
                    return self._json(201, app.runner.confirm_publish(match.group(1)).summary())
                raise RunError("not_found", 404)
            except RunError as exc:
                self._error(exc.status, exc.code)

    return Handler


def serve(app: App, port: int = 0) -> ThreadingHTTPServer:
    httpd = ThreadingHTTPServer(("127.0.0.1", port), make_handler(app))
    httpd.daemon_threads = True
    app.port = httpd.server_address[1]
    if app.approvals:
        app.runner.approval_url = f"http://127.0.0.1:{app.port}/api/internal/approvals"
    return httpd


def url(app: App) -> str:
    base = f"http://127.0.0.1:{app.port}/"
    return f"{base}#token={app.token}" if app.token else base


def wait_forever(httpd: ThreadingHTTPServer) -> None:
    try:
        httpd.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
