# Isolated rendered-browser investigation

## Runtime and consent

This is a rendered Chromium capture, not URL ingestion and not a general browser agent.
The separate browser panel accepts one public HTTPS URL and up to 32 exact same-origin
GET paths, including the navigation path. Opening the panel checks availability/history;
preview does not navigate. Only explicit consent consumes a nonce-bound preview and
persists a job before launching Chromium. No AI call, credential lookup, default profile
access, evidence-bundle mutation, automatic follow-up, or answer generation occurs.

API base: `/api/workspaces/{workspaceId}/browser-investigations`.

Availability/history, preview, submission and cancellation actions have a ten-second UI
deadline. A stalled transport releases the controls even if it ignores abort; late
responses cannot replace current state. Submission timeout is an unknown outcome, not
proof of failure: explicit reception checking reuses the same request ID and approval,
with no automatic resubmission. Unmount aborts the action and clears its deadline.
This source change follows the locally installed 0.1.22 candidate and is not yet packaged.

- `GET /capability`: `{status: AVAILABLE|SETUP_REQUIRED,message}`.
- `POST /preview {bundleId,url,allowedGetPaths}`: `{bundleId,digest,scope,expiresAt,maxAiCalls:0,limits}`.
- `POST /jobs {requestId,bundleId,digest,approved:true}`: HTTP 202, Job.
- `GET /jobs?bundleId=...`: oldest-to-newest jobs.
- `GET /jobs/{id}` and `POST /jobs/{id}/cancel`: Job.
- Job: `{id,workspaceId,bundleId,digest,status,result,error,approvedAt,updatedAt}`.
- Result: `{url,capturedAt,title,text,observations,truncated,status}`.
- Observation: `{method,path,status,blocked,candidatePath?}`; no request/response headers or bodies.

Job states are QUEUED, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED.
Capture status distinguishes CAPTURED, AUTH_REQUIRED, NAVIGATION_BLOCKED.
Requests with the same ID and binding return the stored job; another request cannot
consume the same preview. Unfinished stored jobs fail on process initialization rather
than replay. Preview expires after ten minutes; retained records are capped at 100 per
workspace. DB v27 is owned by the core migration, not repository-constructor DDL.
Browser job storage is separate from existing answer/planning approvals.

The panel polls sequentially, waiting 1.5 seconds after a request settles, with a
10-second abort deadline per status request. Even a transport ignoring abort cannot
accumulate overlapping polls. Collapse/unmount or workspace/bundle changes abort the
current poll and clear timers. Pre-cancel responses and regressive/late terminal-state
updates are ignored. The dedicated panel regression suite now passes 13 tests, including
deadline, non-overlap, retry, cleanup and stale-status cases
(`/tmp/dougle-browser-polling-ui.log`); this supersedes the earlier six-panel-test count.

## Browser setup and dependencies

No new Maven/npm library, browser download, or copied third-party implementation was
added. Java 21 WebSocket/HttpClient implements the small fixed CDP command surface;
Apache HttpClient and Jackson are existing application dependencies. No Ego runtime,
Node runtime, Playwright installation, ChromeDriver, or shell command interpolation is used.

Current desktop packaging does **not** bundle Chromium. The transport checks an explicit
server setting `forge.browser.executable`, otherwise known executable locations:

- `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`
- `/Applications/Chromium.app/Contents/MacOS/Chromium`
- `/usr/bin/chromium`
- `/usr/bin/google-chrome`

On Mac, install Chrome into Applications and reopen the app. Missing executable or
database migration returns SETUP_REQUIRED with an explanation; it is not reported as
successful browser setup. Executable presence is AVAILABLE, **not** proof of protocol
compatibility. Existing installed Chrome remains vendor-distributed software; this
change does not redistribute it or add a browser license to the app bundle.

Every capture creates and deletes a disposable profile. There is no login import or
authenticated-session persistence. An authentication-required result is a handoff
limitation, not an offer to bypass access controls. Authenticated investigation needs
a separately reviewed, user-mediated managed-profile login flow.

## Enforcement and bounds

- Explicit HTTPS origin, port 443 only (implicit), exact unencoded paths. URL credentials,
  query strings, fragments, traversal, wildcard paths and cross-origin requests rejected.
- Browser uses a loopback deny-proxy with implicit loopback bypass removed; host resolution
  is disabled, QUIC disabled and non-proxied WebRTC UDP disabled. No DIRECT fallback.
- CDP pauses requests before network delivery. It never issues Fetch.continueRequest.
  Approved GETs are fulfilled by the application's separate gateway, whose DNS resolver
  rejects non-public addresses at connection time.
- Gateway has no cookie/auth forwarding, browser header forwarding, proxy inheritance,
  redirects, automatic retries, or content decompression. Original response headers,
  including cookies and redirects, are not copied to the browser.
- Only Document, Script, Stylesheet, XHR and Fetch resources are fulfilled. Document
  navigation is restricted to the single approved main-frame URL, once. CSP denies frames,
  workers, forms, objects and external resources. Downloads are denied.
- Page JavaScript runs to render the screen, but cannot grant itself another request scope.
  No model-provided script, user-provided JavaScript, click, input, upload, or shell action.
- 30-second capture/executor deadline; browser/process cleanup has additional bounded
  waiting. 32 observed requests, 512 KiB per response body, 4 MiB aggregate body bytes,
  title 256 characters, rendered text 6,000 characters. CDP messages and event queue bounded.
- Render observation window is at most eight seconds, ending sooner after two seconds
  without intercepted requests. This is a timed snapshot, not proof of application readiness.
- Request queries/fragments/credentials are never stored in observations. Syntactically valid
  same-origin paths are shown without queries/fragments, including blocked paths; cross-origin,
  credential-bearing, encoded and ambiguous paths are replaced with placeholders. A blocked
  GET Script/Stylesheet/XHR/Fetch path up to 160 characters can be offered as a candidate,
  never as an automatically approved request. Paths may themselves contain identifiers;
  this is not a universal secret detector. Page text is also untrusted and not automatically redacted.

The candidate button only changes the local editable scope draft. The user must request
a new preview and explicitly approve its fresh digest before another browser job starts.
It does not change an existing job/preview, reuse an old approval, or forward the original
query-bearing request. Queries remain blocked even after the path is approved. Candidates
come from the untrusted page and must be reviewed as suggestions, not verified read APIs.

GET is a transport restriction, **not a guarantee that a remote server has no side
effects**. Users must approve known read endpoints. Browser network restrictions are
defense in depth, not a separately installed OS egress firewall. The Chromium sandbox
is not disabled. Arbitrary hostile pages can consume CPU/memory within the capture
window; there is no claimed per-renderer RSS quota or universal browser exploit defense.

Most authenticated SPAs, cross-origin CDNs, query-based APIs, eval-dependent applications,
fonts/images, websocket/worker-driven applications and redirects will not fully render.
Blocked observations and truncation must remain visible. Local observations are not
automatically ingested into an immutable evidence bundle and are not a final answer.

## Verification

Owned tests cover scope rejection, capability/setup, explicit tool binding, bounded
gateway output, consent consumption/retries/restart/cancellation, and escaped UI results.
The opt-in test `ManagedBrowserCaptureTest.actualChromiumRendersSyntheticApiDataAndBlocksMutationsWithoutRemoteTraffic`
launches real installed Chromium with a fresh profile, but fulfills every allowed response
from a mocked gateway. Its synthetic script fetches JSON and changes rendered title/text;
POST/query/external requests are not forwarded. It requires
`-Dforge.test.isolated-browser=true`; executable override is
`-Dforge.test.browser-executable=/absolute/path`.

Reproduce when the shared Maven slot is available (no package/clean/JAR write):

```sh
bash scripts/maven.sh -o \
  -Dtest=BrowserCaptureScopeTest,BrowserInvestigationServiceTest,BrowserHttpGatewayTest,ManagedBrowserCaptureTest \
  -Dforge.test.isolated-browser=true \
  -Dforge.test.browser-evidence=/tmp/dougle-browser-fixture-evidence.json test
```

The optional evidence file is written only after the real-render fixture's assertions
pass. It contains the actual rendered snapshot and exact mocked-gateway requests, plus
synthetic-fixture/no-user-profile labels. It is JSON evidence, **not a screenshot**.
It contains no credentials or actual remote-site capture. During the coordinated build,
cached JUnit with separately compiled `/tmp` classes can run these same tests without
modifying Maven output; the launcher used locally is `/tmp/DougleBrowserTestRunner.java`.

Actual no-Maven command used for the candidate-path verification, from the repository root
(the local launcher above selects its class-name arguments through cached JUnit Platform):

```sh
BUILD=$(mktemp -d /tmp/dougle-browser-candidates.XXXXXX)
CP="backend/target/classes:$(find .m2 -name '*.jar' -print | rg -v '/(1\.12\.2|5\.12\.2)/' | paste -sd ':' -)"
JAVA="$(/usr/libexec/java_home -v 21)/bin"
"$JAVA/javac" -cp "$CP" -d "$BUILD" backend/src/main/java/dev/dougle/investigation/application/Browser*.java backend/src/main/java/dev/dougle/investigation/adapter/Browser*.java backend/src/main/java/dev/dougle/investigation/adapter/ManagedBrowserCapture.java backend/src/main/java/dev/dougle/investigation/adapter/JdbcBrowserInvestigationStore.java
"$JAVA/javac" -cp "$BUILD:$CP" -d "$BUILD" /tmp/DougleBrowserTestRunner.java backend/src/test/java/dev/dougle/investigation/application/Browser*.java backend/src/test/java/dev/dougle/investigation/adapter/Browser*.java backend/src/test/java/dev/dougle/investigation/adapter/ManagedBrowserCaptureTest.java
"$JAVA/java" -Dforge.test.isolated-browser=true -Dforge.test.browser-evidence=/tmp/dougle-browser-fixture-evidence.json -cp "$BUILD:$CP" DougleBrowserTestRunner dev.dougle.investigation.application.BrowserCaptureScopeTest dev.dougle.investigation.application.BrowserInvestigationServiceTest dev.dougle.investigation.adapter.BrowserHttpGatewayTest dev.dougle.investigation.adapter.ManagedBrowserCaptureTest > /tmp/dougle-browser-candidate-tests.log 2>&1
```

2026-10-25 candidate-path verification: 38 backend tests passed, zero skipped, including
the real isolated Chromium fixture; six dedicated panel tests passed. Logs:
`/tmp/dougle-browser-candidate-tests.log` and `/tmp/dougle-browser-candidate-ui.log`.
Do not add these counts to overlapping parent regression totals.

This fixture is not a real-site, authenticated-session, external AI, packaged-browser,
or ARM installation acceptance test. Maven execution is serialized with the parent.

## Protocol references

- [Chrome DevTools Protocol Fetch](https://chromedevtools.github.io/devtools-protocol/tot/Fetch/):
  request interception and response fulfillment.
- [Chrome DevTools Protocol Runtime](https://chromedevtools.github.io/devtools-protocol/tot/Runtime/):
  fixed rendered-text evaluation.
- [Chromium proxy documentation](https://chromium.googlesource.com/chromium/src/+/HEAD/net/docs/proxy.md):
  explicit proxy configuration, no DIRECT fallback, and loopback bypass subtraction.
- [Chrome remote-debugging profile changes](https://developer.chrome.com/blog/remote-debugging-port):
  remote debugging uses a non-default profile.
