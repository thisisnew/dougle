# E50 Java-only startup isolation

Authorized scope: installed .22 Java `-version` only, private HOME/cwd/scratch, clean environment, no application/JAR/DB/model inference. No full E50 attempt was made.

## Observations

The comparison probe launched Java once with the existing strict E50 file/network profile and once with the existing E35 network-only profile. Both used `-Djava.net.preferIPv4Stack=true`.

| Profile | Result | Safe diagnostic |
|---|---|---|
| Existing strict file rules | SIGABRT | No recognized version/native diagnostic |
| Existing E35 network-only control | exit 0 | OpenJDK 21.0.10, Corretto-21.0.10.7.1, 64-Bit Server VM |

A subsequent strict-only version probe added byte counts and native fatal-line recognition: SIGABRT, stdout **0 bytes**, stderr **0 bytes**. Thus the missing explanation is not merely a diagnostic regex excluding emitted text. No app was launched in any of these three Java invocations.

All probes removed their private temporary roots and verified the Java executable unchanged: `e5df547d287f27e7e8d4d78e8133c0ba9833c512f60de67bffa46c609a93ac8f`. No model assets were read. No user profile/environment or system diagnostic log was printed/read. The network-only control is a Java-version diagnostic, not permission to weaken the app sandbox.

Read-only `otool -L` inspection of the approved Java launcher and its `libjli.dylib` lists bundled `@rpath/libjli.dylib`, `/usr/lib` libraries, and `/System/Library` frameworks, already covered by the current read rules. This does not identify transitive runtime data requirements.

## Conclusion and boundary

The strict file policy reproduces startup abortion without the product or its DB: this is a harness/runtime-policy compatibility blocker, not evidence of an archive implementation defect. The exact denied resource is **not established**. The strict policy differs in both read and write restrictions, so these observations alone do not prove a missing system *read* allowance rather than a denied write.

No additional app allowance is verified or proposed as a fix. In particular, do not allow all `/Library`, `/private/var`, or user-home contents to make a smoke pass. The next useful diagnostic requires a narrowly scoped sandbox-denial/native-crash observation tied to a new version-only child PID; generic system/user crash-log browsing is outside this probe. Seek parent approval for that observation before proceeding. A concrete resource can then be tested with a single literal rule in a version-only probe, preserving network restrictions, before proposing an app-profile change.

Original E50 failure reports are untouched. No full E50 rerun, AI, credentials, Maven, package or JAR writes. Installed asset lease released after probe cleanup.

## Authorized PID-scoped capture follow-up

One additional strict version-only child, PID **18409**, exited SIGABRT with zero stdout/stderr bytes. Its private root was removed and Java executable hash remained unchanged. The child was already stopped before parent announced installation replacement; no further installed-runtime access or probe was performed after that announcement.

The initial unified-log query selected `java(18409)` only. Follow-up queries of the same stopped PID included `processID == 18409` or the exact `(18409)` marker, with results additionally restricted to that process or `java(18409)`/`sandbox-exec(18409)` references. The final query included info/debug, bounded to five minutes, 10 seconds, and 64 KiB. No broad log query or other PID's record was returned to the review output.

No file-denial record was obtained. The only returned relevant diagnostic was `sandbox compile: using compatibility definitions for version 1 (latest version is 3)`; one non-diagnostic target record was withheld. This compatibility notice does not identify the abort cause or justify changing the sandbox version. Native crash reports or actual user profile/credential files were not opened.

The exact denied resource remains unknown, and no system-path allowance has been verified. Do not convert this negative log observation into a claim that no denial occurred. Further probes are paused for the parent installation, and any later probe must use the parent-approved immutable bundle/runtime rather than the changing installed application. No full E50 rerun is authorized by these Java-only diagnostics.
