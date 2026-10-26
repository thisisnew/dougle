# Offline read-only runtime smoke

Status: **actual isolated runtime passed** at `2026-10-26T10:18:04.359Z` against authorized JAR SHA `5cd81269031769b8c65af4a3cfc136dcd562e30a0c3e91f255f6a959f4a9bd0d`. All old `.21` packages remain blocked. The known pre-policy JAR SHA `675a7c59e13edc8ee849b23977699e600331555d8f53c669ef8e7f10406e3489` is explicitly rejected. E35 inventory remains unchanged pending parent review; this is not whole-product acceptance.

## Checks planned against a separately approved new JAR

- Start the real packaged backend on an ephemeral loopback port with private temporary HOME/data/Java temp/profile paths, a generated session token, missing AI executables and no supplied credentials. No ambient JVM options or application environment are inherited.
- Create one synthetic workspace over HTTP. Require `403` with `READ_ONLY:` problem detail for Slack reply preview, execute and acknowledge routes, including valid old-shaped approved digests, malformed JSON, absent bodies, wrong types, missing receipts and a nonexistent workspace. Generic CSRF/404/400 failures are not accepted as policy evidence.
- Repeat identical request IDs, then close the owned backend before SQLite access. Assert no reply rows were created. Seed exactly three legacy synthetic PREVIEW/SENT/UNCERTAIN rows in the harness-created DB only. The synthetic SENT row does **not** represent a Slack send.
- Restart twice; repeat denied requests against each seeded receipt and nonexistent receipts. GET list/detail must retain history. Compare every stored receipt column before/after, including dispatch count and timestamps. Answer/generation/planning tables must remain empty.
- Require the input JAR hash to remain unchanged through startup/restarts/completion. No Maven, packaging, app installation, user DB, external AI, or actual Slack configuration is used.

The harness is macOS-specific and requires existing `/usr/bin/sandbox-exec`, `/usr/bin/sqlite3` and an explicit Java 21 executable. It refuses an unsandboxed fallback. A loopback-only network sandbox surrounds the backend, and a rejecting loopback HTTP(S) JVM proxy counts observed requests without collecting destinations/headers/bodies. Proxy count zero proves only that **this proxy observed no requests**; it is not a total socket/transport-attempt counter. Startup diagnostics retain only bounded recognized error lines with session/auth-token redaction; raw application output is not dumped. Synthetic SQL and outputs contain no passwords.

## Self-tests (no JAR, DB or network opened)

```sh
node --test scripts/readonly-runtime-smoke.test.mjs
node --check scripts/readonly-runtime-smoke.mjs
```

2026-10-26: initial six pure Node self-tests passed; diagnostics added one test and private bootstrap-environment validation added another. Final **eight self-tests passed**, plus syntax checking. These are overlapping runs, not 21 distinct tests. No runtime/JAR, fixture database, proxy listener or sandbox is executed by the self-tests.

## Actual run and preserved failures

- [Raw successful report](evaluations/readonly-runtime-20261026.json): 300 explicit `403 READ_ONLY` denials, two restarts, three unchanged synthetic legacy receipt rows, zero answer/generation/planning job rows, zero **observed proxy requests**. Harness exited 0 and removed its own temporary directory after stopping its child; unrelated Java processes were untouched.
- [Failed startup history](evaluations/readonly-runtime-20261026-failed-startup.json) remains separate. First the harness omitted the required absolute `FORGE_DATA_DIR` environment variable. After that correction, Tomcat's `Net.bind0` was denied under the sandbox. An explicit localhost-only bind rule alone did not fix it; forcing `-Djava.net.preferIPv4Stack=true` matched the enforced `127.0.0.1` listener and allowed attempt 6 to complete. No application source change or external-network permission was introduced.
- [Verification metadata](evaluations/readonly-runtime-20261026-verification.json) records exact harness/self-test/JAR hashes, timestamps, command prerequisites, cleanup and limitations. Original failure log is `/tmp/dougle-022-readonly-runtime.log`; success log is `/tmp/dougle-022-readonly-runtime-attempt6.log`; intermediate logs remain separate.
- Harness SHA-256: `6e8c0dbbadc0ad2757f6451f2a0ed93d71cb54938e34385539c18b31b9547747`. JAR hash verified unchanged after execution. Harness and documentation are now frozen.

## Future execution — separate approval required

```sh
node scripts/readonly-runtime-smoke.mjs --run-approved-artifact \
  --jar /absolute/path/to/new-approved.jar \
  --sha256 NEW_APPROVED_64_HEX_SHA256 \
  --java /absolute/path/to/java21/bin/java \
  > /tmp/dougle-readonly-runtime-report.json
```

The report contains fixture version, actual artifact hash, denial count, restart count, legacy preservation checks, job-row count and observed proxy count. Only a successful actual run followed by review can substantiate the changed E35 policy. Retain the reviewed report under `docs/evaluations/` separately; self-tests are not runtime evidence. Whole-product external-write prevention and real Slack transport-attempt absence are not claimed.

The harness terminates only its own detached backend process group, waits for exit before database access/removal, then removes its private directory. Failed shutdown preserves that isolated directory rather than deleting a live database. There is no caller-supplied DB path, default data directory fallback, OS service, credential read, or external account requirement.
