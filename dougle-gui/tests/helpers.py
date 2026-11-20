import http.client
import json

TOKEN = "test-token-123"


def request(app, method, path, *, token=TOKEN, body=None, host=None, headers=None):
    conn = http.client.HTTPConnection("127.0.0.1", app.port, timeout=15)
    sent = {"Host": host or f"127.0.0.1:{app.port}"}
    if token:
        sent["X-Dougle-Token"] = token
    payload = None
    if body is not None:
        payload = json.dumps(body).encode()
        sent["Content-Type"] = "application/json"
    sent.update(headers or {})
    conn.request(method, path, body=payload, headers=sent)
    response = conn.getresponse()
    data = response.read()
    conn.close()
    try:
        return response.status, json.loads(data), response
    except json.JSONDecodeError:
        return response.status, data, response
