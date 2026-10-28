# Transfer archive integrity groundwork

This package has no application, persistence, filesystem extraction, credential access or API integration. The only payload type is a document identified by UUID, with byte length and SHA-256. There is no settings, authentication, provider, connection or arbitrary metadata field. This excludes settings structurally; arbitrary document bytes can still contain secrets. Callers must select/sanitize exportable content. Integrity hashes do not authenticate the sender or encrypt data.

## Initial limits, not product transfer requirements

- At most 64 documents plus exactly one `manifest.bin`.
- At most 1,048,576 bytes per document and 8,388,608 document bytes total. The 1 MiB document cap is an intentionally conservative initial foundation limit, not a full product-transfer requirement.
- At most 4,096 manifest bytes and 9,437,184 archive bytes including headers, central directory and trailing input. The v1 manifest actually occupies 12 + 52 × document count bytes, at most 3,340 bytes.
- Reader accepts STORED and DEFLATED entries through standard JDK `ZipInputStream`. Maximum per-entry decompressed/compressed size ratio is 100:1, checked after the entry completes and data descriptors have been consumed. Empty output is permitted; nonempty output with zero or unknown compressed size is rejected. Hard per-entry and aggregate decompressed limits apply during reading, even before compressed size is known. Writer uses STORED entries (1:1) to avoid generating archives it cannot read.
- Names are exactly `manifest.bin` or `documents/<canonical lowercase UUID>.bin`. No arbitrary filenames, directories, traversal, absolute paths, backslashes, Unicode aliases or alternate streams. Duplicate local ZIP names and manifest UUIDs are rejected.

## Integrity scope

The JDK handles ZIP parsing, decompression and CRC checks. Every document must additionally match its typed manifest SHA-256 and length. Manifest and locally streamed ZIP document sets must match exactly. Unknown manifest versions/layouts, unsupported compression and JDK-detected malformed/encrypted entries fail.

This is local-entry integrity validation, **not full ZIP container validation**. `ZipInputStream` does not validate the central directory or require a complete end-of-central-directory record. The codec does not interpret central metadata or detect central-only duplicate names, directory/header disagreements, or trailing concatenated ZIP content. Remaining underlying bytes are drained only to enforce the archive byte limit. Tests explicitly document that a complete local-entry stream without the central directory can be accepted. Do not pass validated archive bytes to another ZIP extractor/parser and assume the same interpretation.

**No filename is ever materialized on disk.** Symlink attributes are not inspected or promised to be rejected; their bytes cannot create or follow a link in this codec. A UUID document carrying symlink-like metadata remains an in-memory document payload whose bytes must match the manifest. Future filesystem import requires a separate design.

## Manifest wire format

`manifest.bin` is fixed binary typed data, not JSON or Java object serialization. All manifest integers are big-endian: four-byte magic `0x4b465441` (KFTA), four-byte version `1`, four-byte document count, then count records of UUID most/least-significant 64-bit halves, four-byte nonnegative length, and 32 raw SHA-256 bytes. There are no settings fields or alternative record types. Trailing fields and unknown layouts are rejected.

## Ownership and resource behavior

`write(Map<UUID, byte[]>)` checks limits before cloning, sorts IDs and uses fixed timestamps. `read(byte[])` snapshots input; `read(InputStream)` incrementally reads through a counting wrapper and leaves the caller-owned stream open. At most the archive limit + one byte is consumed before rejecting oversized input. Document buffers and returned snapshots remain in memory, so several bounded copies coexist; archive/decompressed limits are not JVM heap caps. ZIP name/extra-field parsing allocations belong to the bounded JDK ZIP format. The caller must supply cancellation/timeouts for blocked input. Callers must not mutate input collections concurrently.

Invalid archive input is reported as `IOException`; direct invalid manifest construction uses `IllegalArgumentException`. The returned documents are defensively copied. No real-data transfer or runtime/API readiness is claimed.

## Verification

27 tests passed with `bash scripts/maven.sh -o -Dtest=dev.dougle.transfer.domain.TransferArchiveCodecTest test` on 2026-10-25. These synthetic tests cover round trips, exact limits, size/count rejection, path exclusion, duplicate/membership rejection, compressed ratio, CRC/SHA corruption, manifest layout, truncated payloads, unsupported compression, stream bounds/ownership and the explicit central-directory/symlink limitations. No new dependencies or network access were used. The full application test suite was not run by this task.
