# Isolated MySQL runtime smoke

Run from a checkout with Java 21, Node and the existing cached Connector/J and Spring annotation jars:

```sh
node scripts/mysql-read-runtime-smoke.mjs /absolute/path/to/mysqld
```

The runner does not run Maven, install packages, start an OS service, or read MySQL option files. It recompiles the current `MysqlClient` and `MysqlReadClient` sources plus the fixture and their four supporting application types into a private temporary directory. It no longer depends on `backend/target/classes`, so concurrent clean builds cannot remove its dependencies. This is an adapter/server smoke, not HTTP/UI integration or a release build test.

Every server invocation starts with `--no-defaults`. A new mode-0700 `/tmp/dougle-mysql-*` directory contains the datadir, socket, pid, log, HOME and generated init SQL. Secure initialization generates a temporary root password that the runner never reads. Startup locks root and creates a random-password SELECT-only fixture account. Credentials are sent to Java on stdin, never command arguments. The listener uses a randomly reserved/released loopback port, X Protocol is disabled, and TLS REQUIRED is used with the server's generated certificate (not identity verification). The small port-allocation race fails closed on server bind/login failure; no existing DB is intentionally contacted or modified.

Only synthetic schema/rows are created by the server's init file. Production adapters then exercise schema collection, selected rows, predicate injection-as-data, row/byte truncation, denied table/view/generated/binary columns, cooperative cancellation and elapsed-budget enforcement. A forwarding JDBC proxy records completed real rollback calls. Connector/J's server status verifies that an explicit transaction ends after rollback; a direct mutation attempt verifies the fixture's SELECT-only grant. The elapsed-budget test uses a synthetic checkpoint delay, not a simulated claim of server-side slow-query coverage.

Deadlines: version 60s, javac 30s, initialization 120s, readiness 60s, Java tests 45s, whole runner 300s. Cleanup sends TERM, then KILL after 5s, only to owned process groups, waits for exit and removes the private directory. SIGINT/SIGTERM also clean up; SIGKILL/power loss cannot be handled. Server diagnostics are suppressed because initialization logs contain generated secrets. Java fixture diagnostics contain synthetic data only. No credentials or server log are retained as evidence.

Initialization reference: [MySQL 8.4 data-directory initialization](https://dev.mysql.com/doc/refman/8.4/en/data-directory-initialization.html). The fixture init file is applied on normal startup, not bootstrap, because bootstrap limits account-management statements.

## Local result, 2026-10-25

The supplied Anaconda x86_64 MySQL 8.4.0 binary is viable. A bounded version probe succeeded after 27.6 seconds; an earlier sample showed time spent in dyld loading/mapping dependencies, not MySQL server initialization. This does not identify a definitive OS-level cause for the startup latency.

The initial real run exposed two production defects hidden by mocked JDBC: numeric `getCharacterStream()` raises Connector/J's `Unsupported conversion from LONG to java.io.InputStream`, and rollback before manual-transaction mode masks an early timeout with `Can't call rollback when autocommit=true`. `MysqlReadClient` now uses validated numeric/temporal column types for bounded scalar string conversion (128-character defensive cap); text remains streamed. Rollback runs only after manual-transaction mode starts and cannot replace a primary SQL timeout, cancellation or other failure.

After the source fix: **17/17 real-server checks passed**, including the numeric and early-timeout regressions. **22/22 `MysqlReadClientTest` unit tests passed** through direct javac/JUnit with cached dependencies. No Maven, package task or built JAR write was used. All owned server processes and temporary directories were removed. Local transient logs: `/tmp/dougle-mysql-read-runtime-smoke.log` and `/tmp/dougle-mysql-read-unit.log`; version diagnostic `/tmp/dougle-mysql-version-final.json`. These logs are not bundled release evidence and may disappear with temporary-file cleanup.

## Server-side field bounding follow-up

The earlier reader/output tests did not prove bounded JDBC memory: Connector/J can buffer a complete text field before exposing `getCharacterStream`. The current projection instead uses only internally generated `LEFT(CONVERT(quoted_column USING utf8mb4), character_limit)` expressions and boolean `CHAR_LENGTH(CONVERT(...)) > character_limit` flags for every selected value, including numeric/temporal columns. No raw scalar projection depends on previously observed metadata. All equality predicates still reference the original columns, with prepared values, never truncated expressions.

The character limit is `max(1, maxBytes / 4 / selectedColumnCount)`. With 1–16 columns and the existing minimum 64-byte output budget, UTF-8 value payload per row is at most `maxBytes`, plus one byte per truncation flag. Metadata/protocol framing is additional bounded overhead, not included in the output budget. At most `limit + 1` rows are returned (including the truncation sentinel). This bounds returned data, not server work scanning/converting source cells. Existing query/network/checkpoint time limits remain necessary.

Server-prefix truncation sets `truncated=true` even when the output fits. `rows` counts completed serialized rows, including rows with explicitly partial cells; a row cut mid-serialization by the output-byte cap is not counted. A completed JSON array can precede the existing truncation marker; truncated output is still notes, not a guaranteed standalone JSON document.

The expanded fixture stores separate 8 MiB ASCII and four-byte emoji LONGTEXT cells, verifies their size using `OCTET_LENGTH`, and measures returned-field bytes plus the session's `Bytes_sent` delta around the real production SELECT. Instrumentation forwards all JDBC operations and does not replace result values. The delta includes status-query/metadata overhead; it is a server protocol counter, not a TLS packet capture or heap profiler. Tests also distinguish original full-value equality from prefix equality and cover NULL/exact-boundary cells. See `docs/evaluations/mysql-runtime-bounded-20261025.json` for retained results and source hashes; the earlier report remains historical evidence, not current-source certification.

References: [MySQL string functions](https://dev.mysql.com/doc/refman/8.4/en/string-functions.html), [session status counters](https://dev.mysql.com/doc/refman/8.4/en/server-status-variables.html).

## Metadata drift follow-up

The metadata query returns `COALESCE(CHAR_LENGTH(GENERATION_EXPRESSION), 0) > 0`, not expression text. Generation checks still reject generated columns. Type/name validation remains separate from wire bounding: a concurrent numeric-to-LONGTEXT change cannot disable the uniform server-side cap. The standalone fixture uses a second generated account with ALTER/UPDATE/SELECT privileges only on its disposable `fixture.drift_items` table to inject this change after metadata checks and before the production SELECT. That account never replaces the SELECT-only investigation account. This regression does not prove immunity to every concurrent schema replacement (for example view/generated-column policy drift).

Numeric/temporal conversions retain the existing JSON string representation, and SQL NULL remains JSON null. Tiny byte budgets can truncate any value, including a scalar, with the same explicit partial-result flag. Exact-case allowlist and column/schema metadata comparisons remain deliberately fail-closed; valid alternate spellings may be rejected. Table/schema names must not be indiscriminately lowercased because MySQL platform/configuration affects their case behavior.

Final follow-up: **34/34 real checks and 26/26 unit tests passed**. MySQL rejected the injected concurrent DDL with error 1412 and no result payload; an explicit fresh transaction read the changed 8 MiB LONGTEXT with a 256-byte field and 681-byte session counter delta. This is not a claim that the live server returned stale-schema data: the uniform projection under stale numeric metadata is separately asserted by unit tests. Retained transcripts, hashes and limitations: `docs/evaluations/mysql-runtime-ddl-20261026.json`. No Maven or built JAR writes were used.
