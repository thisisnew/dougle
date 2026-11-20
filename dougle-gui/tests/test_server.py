import http.client
import json

from dougle_gui.server import url

from .helpers import TOKEN, request


def test_binds_loopback_and_prints_token_url(server):
    app, _ = server
    assert url(app) == f"http://127.0.0.1:{app.port}/#token={TOKEN}"


def test_static_page_is_served_with_csp(server):
    app, _ = server
    status, body, response = request(app, "GET", "/", token=None)
    assert status == 200 and b"dougle" in body
    assert "frame-ancestors 'none'" in response.getheader("Content-Security-Policy")
    assert request(app, "GET", "/app.js", token=None)[0] == 200


def test_api_requires_token(server):
    app, _ = server
    assert request(app, "GET", "/api/overview", token=None)[:2] == (401, {"error": "invalid_token"})
    assert request(app, "GET", "/api/overview", token="wrong")[0] == 401
    assert request(app, "GET", f"/api/overview?token={TOKEN}", token=None)[0] == 200
    assert request(app, "POST", f"/api/runs?token={TOKEN}", token=None, body={"kind": "ask", "input": "x"})[0] == 401


def test_foreign_host_and_origin_are_rejected(server):
    app, calls = server
    assert request(app, "GET", "/api/overview", host="evil.example")[0] == 403
    status, data, _ = request(app, "POST", "/api/runs", body={"kind": "ask", "input": "x"},
                              headers={"Origin": "http://evil.example"})
    assert (status, data) == (403, {"error": "invalid_origin"})
    assert calls() == []


def test_knowledge_endpoints(server):
    app, _ = server
    status, overview, _ = request(app, "GET", "/api/overview")
    assert status == 200 and overview["topics"] == 3
    topics = request(app, "GET", "/api/topics")[1]
    receiving = next(t for t in topics if t["key"] == "receiving")
    assert receiving["freshness"] == {"needs_recheck": 2} and receiving["conflicts"] == 1
    topic = request(app, "GET", "/api/topics/receiving")[1]
    assert topic["claims"][0]["id"] == "C01" and "text" in topic
    assert request(app, "GET", "/api/topics/old~receiving")[1]["title"] == "옛 입하 노트"
    assert request(app, "GET", "/api/topics/missing")[0] == 404
    assert request(app, "GET", "/api/topics/..%2Findex")[0] in (400, 404)
    assert request(app, "GET", "/api/sources/S001")[1]["state"] == "partial"
    assert request(app, "GET", "/api/search?q=%ED%9A%8C%EC%86%A1")[1][0]["key"] == "rts-mail"
    bot = request(app, "GET", "/api/bot")[1]
    assert bot["bot"] == {"installed": False}
    assert bot["schedules"][0]["inquiries"]["awaiting_approval"] == 1


def test_run_lifecycle_over_http_and_sse(server):
    app, calls = server
    status, run, _ = request(app, "POST", "/api/runs", body={"kind": "ask", "input": "회송 흐름"})
    assert status == 201 and run["kind"] == "ask"
    conn = http.client.HTTPConnection("127.0.0.1", app.port, timeout=15)
    conn.request("GET", f"/api/runs/{run['id']}/events?token={TOKEN}", headers={"Host": f"127.0.0.1:{app.port}"})
    response = conn.getresponse()
    assert response.getheader("Content-Type").startswith("text/event-stream")
    stream = response.read().decode()
    conn.close()
    events = [json.loads(line[6:]) for line in stream.splitlines() if line.startswith("data: {\"")]
    assert [e["seq"] for e in events] == list(range(1, len(events) + 1))
    assert events[-1]["kind"] == "exit" and "event: end" in stream
    detail = request(app, "GET", f"/api/runs/{run['id']}")[1]
    assert detail["status"] == "done" and detail["result"] == "최종 답변입니다."
    assert request(app, "GET", "/api/runs")[1][0]["id"] == run["id"]
    assert calls()[0][calls()[0].index("-p") + 1] == "/dougle:dougle-ask 회송 흐름"


def test_run_request_validation(server):
    app, calls = server
    assert request(app, "POST", "/api/runs", body={"kind": "rm", "input": "x"})[1] == {"error": "invalid_kind"}
    assert request(app, "POST", "/api/runs", body={"kind": "ask", "input": "x", "session": "; rm -rf"})[1] == {
        "error": "invalid_session"}
    status, data, _ = request(app, "POST", "/api/runs", body=None, headers={"Content-Type": "text/plain"})
    assert status == 415
    assert request(app, "POST", "/api/runs/abcdefabcdef/confirm", body={})[0] == 404
    assert calls() == []


def test_default_mode_needs_no_token(open_server):
    app, _ = open_server
    assert url(app) == f"http://127.0.0.1:{app.port}/"
    assert request(app, "GET", "/api/overview", token=None)[0] == 200
    status, run, _ = request(app, "POST", "/api/runs", token=None, body={"kind": "ask", "input": "질문"})
    assert status == 201


def test_default_mode_still_blocks_cross_site_requests(open_server):
    app, calls = open_server
    assert request(app, "GET", "/api/overview", token=None, host="evil.example")[0] == 403
    status, _, response = request(app, "GET", "/api/overview", token=None, headers={"Origin": "http://evil.example"})
    assert response.getheader("Access-Control-Allow-Origin") is None
    assert request(app, "POST", "/api/runs", token=None, body={"kind": "ask", "input": "x"},
                   headers={"Origin": "http://evil.example"})[0] == 403
    # A cross-site form can only send simple content types; every POST requires JSON.
    for path in ("/api/runs", "/api/runs/abcdefabcdef/cancel", "/api/runs/abcdefabcdef/confirm"):
        assert request(app, "POST", path, token=None, headers={"Content-Type": "text/plain"})[0] == 415
    assert request(app, "OPTIONS", "/api/runs", token=None)[0] == 501
    assert calls() == []
