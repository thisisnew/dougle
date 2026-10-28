# Native desktop host

The preview baseline is x86_64 macOS 11.3 or later. Rust 1.99.1, Tauri 2.12.5, tauri-build 2.7.4 and npm CLI 2.12.4 are pinned; both dependency lockfiles are included. The CLI and Cargo wrappers use only this repository's `.tools/cargo` and `.tools/rustup`.

## Commands

Run from the repository root:

```sh
npm --prefix desktop ci
bash scripts/desktop-cargo.sh check --locked
bash scripts/desktop-cargo.sh test --locked
bash scripts/desktop-cargo.sh clippy --locked --all-targets -- -D warnings
TAURI_CONFIG='{"bundle":{"resources":[]}}' bash scripts/desktop-cargo.sh run --locked --example webview_smoke
```

The synthetic smoke requires a logged-in macOS GUI session. It starts only a temporary loopback fixture, checks document-start injection under `script-src 'self'`, authenticated fetch, blob download bytes, blocked navigation and blocked popups, then removes its temporary download. It does not start Java or any AI process.

`scripts/verify-macos-package.sh` additionally mounts the generated DMG read-only, copies its app to an isolated installation directory, verifies signatures, and runs `examples/runtime_smoke.rs` against the actual bundled Java/JAR/React. `scripts/native-host-smoke.mjs` exercises the production native executable with an isolated HOME, minimal PATH, duplicate launch and parent-exit recovery. Neither sends real AI requests. [Korean verification report](../docs/desktop-report.md).

Validation on the x86_64 macOS development machine: Cargo check passed; 12 Rust tests passed; Clippy with `--all-targets -- -D warnings` and rustfmt check passed. The native WebKit smoke passed all five checks. Tests cover exact readiness parsing, output bounds, runtime log discarding, origin/blob policy, random tokens, authenticated readiness with redirect/body rejection, graceful EOF, forced termination and descendant cleanup without affecting another process group. Installer and staged-JAR integration validation are recorded by the parent workflow separately.

`check`, `test` and `clippy` deliberately override only the bundle resource list so they work before staging. Normal `build`, `run`, and the npm Tauri wrapper require staged resources. Do not use that override to build a distributable.

After the parent packaging workflow stages resources:

```sh
APPLE_SIGNING_IDENTITY=- npm --prefix desktop run tauri -- build --target x86_64-apple-darwin --bundles app,dmg -- --locked
```

For reproducible local packaging use `bash scripts/package-macos.sh` instead: its DMG helper uses `hdiutil` without Finder-prettifying AppleScript, which failed in this development environment.

`-` selects ad-hoc signing, not Developer ID signing or notarization. Packaging, runtime licenses, checksums and installation verification belong to the parent workflow.

## Resource and backend contract

Staging must provide these files, retaining Java executable permissions and the complete Java 21 runtime tree:

```text
desktop/src-tauri/resources/runtime/bin/java
desktop/src-tauri/resources/runtime/lib/...
desktop/src-tauri/resources/dougle.jar
desktop/src-tauri/resources/ai/codex
desktop/src-tauri/resources/git/bin/git
desktop/src-tauri/resources/git/libexec/git-core/...
desktop/src-tauri/resources/git/COPYING
desktop/src-tauri/resources/git/source/...
```

The JAR must embed the built UI. Tauri preserves the `resources/` prefix under `app.path().resource_dir()` (in the macOS bundle: `Contents/Resources/resources/`). The host canonicalizes Java and JAR paths before directly spawning Java, without a shell or PATH-based Java lookup. It removes Java option injection environment variables but retains the user's existing CLI environment. It does not install or call AI itself.

From 0.1.5, the host requires bundled Git and passes its canonical root through `--forge.git-home`. Git and HTTPS helper paths must remain in the bundle. `GitRunner` invokes the absolute executable with its own helper path, clears inherited `GIT_*` overrides, and keeps collection limits. Missing bundled Git never falls back to system Git. The complete corresponding source, GPL license and build recipe ship with both CPU packages. [Build and verification](../docs/bundled-git.md).

The stable application identifier is `dev.dougle.desktop`; `app_data_dir()` is the sole data path (normally `~/Library/Application Support/dev.dougle.desktop` on macOS). The host passes absolute `FORGE_DATA_DIR`, `FORGE_DESKTOP=true` and uses the data directory as the child working directory. It does not import development data. The backend's data lock rejects another app instance using the same directory.

Each process gets a fresh OS-random 32-byte token encoded as 64 lowercase hexadecimal characters. The first stdin line is the token, followed by a newline; the host retains stdin until shutdown. The backend must force `127.0.0.1:0`, print `FORGE_DESKTOP_READY:<port>` as its own stdout line after readiness, require `X-Forge-Session` on `/api/desktop/ready`, and return HTTP 200 with JSON `{"ready":true}`. Backend EOF supervision is required for host-crash cleanup. The host probes readiness without proxies or redirects before opening `WebviewUrl::External` at the exact owned HTTP origin.

## Security and lifecycle limits

- Native WKUserScript document-start injection runs before page scripts under the backend CSP. Only the main frame at the exact owned HTTP origin receives the nonwritable, nonconfigurable, nonenumerable `window.__FORGE_DESKTOP_SESSION__`. No token is stored in a URL, environment variable, file or host log. The webview uses an ephemeral data store and disables devtools.
- No custom IPC commands, shell/filesystem plugins or remote capabilities are registered. Navigation stays on the owned origin; new windows and browser permission requests are denied. Backend CSP remains authoritative for the externally served UI. Native host pages use a separate restrictive CSP.
- Blob downloads from the owned origin are allowed; blob navigation is denied. Wry 0.57 selects Downloads and adds numbered suffixes on existing names. The host additionally rejects destinations outside Downloads and any existing file or symlink. macOS 11.3 is required for WebKit download handling. This is not a defense against another malicious process running as the same OS user.
- Startup waits up to 60 seconds plus an in-flight readiness request of at most 2 seconds. The initial page-load watchdog is 20 seconds; an empty UI root gets a friendly message after 15 seconds. A readiness health probe runs every 2 seconds. A failed probe or backend exit ends this session without restarting or replaying work.
- Stdout/stderr are drained without retaining or printing backend text. Combined startup output is capped at 8 MiB; parsed lines at 4096 bytes; readiness JSON at 1024 bytes. After authenticated readiness, output is discarded in fixed-size buffers without a cumulative lifetime limit.
- Java starts in a new owned Unix process group. Closing the app drops child stdin and allows 8 seconds for graceful backend shutdown. On timeout, while the owned leader is still unreaped, the host confirms its group identity, kills that group, then reaps Java. No global Java/process-name kill is used. Host crashes close the pipe at the OS level; backend EOF supervision owns that shutdown. Descendants that deliberately detach from the group, or survive an already-exited leader, remain the backend's cleanup responsibility.
- Finder may omit `/opt/homebrew/bin`, `/usr/local/bin` and `~/.local/bin` from PATH. Bundled Git and Codex use absolute app paths. Existing Claude tools can use the persisted CLI path setting; the host does not read credentials or shell profiles.
- Friendly startup/runtime errors stay in a local host window. Failure messages intentionally omit raw backend output. Native signed updates are available from 0.1.3; see [update flow and signing keys](../docs/automatic-updates.md). Apple Silicon packages are cross-built and statically checked, not runtime-verified on this Intel host. Automatic failure restart, development-data migration, AI replay and Windows packaging remain outside this preview.

API references: [Tauri resource resolution](https://v2.tauri.app/develop/resources/) and [Tauri WebviewWindowBuilder](https://docs.rs/tauri/2.12.5/tauri/webview/struct.WebviewWindowBuilder.html). The resolved Wry source uses `WKUserScriptInjectionTime::AtDocumentStart` and dispatches download actions before navigation checks.
