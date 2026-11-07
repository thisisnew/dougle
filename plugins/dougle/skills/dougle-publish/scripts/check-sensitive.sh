#!/bin/sh
# Read-only staged Markdown inspection; stdout never includes content or filenames.
set -eu
export LC_ALL=C
fail() { printf '{"status":"ERROR","reason":"%s"}\n' "$1"; exit 2; }
hash_file() {
 if [ "$hash_tool" = sha256sum ]; then
  raw=$(sha256sum < "$1" 2>/dev/null) || return 1
 else
  raw=$(shasum -a 256 < "$1" 2>/dev/null) || return 1
 fi
 value=${raw%% *}
 [ "${#value}" = 64 ] || return 1
 case "$value" in *[!0-9a-f]*) return 1;; esac
 printf '%s\n' "$value"
}
if [ "${1-}" = --scan ]; then
 [ "${DOUGLE_SCAN_INTERNAL-}" = 1 ] || fail internal_only
 root=$2; work=$3; hash_tool=$4; shift 4
 n=$(cat "$work/count")
 scan_file() {
 path=$1; n=$((n + 1)); printf '%s\n' "$n" > "$work/count"
 issue() { printf 'F%s\t%s\t%s\n' "$n" "$1" "$2" >> "$work/findings"; }
 error() { issue 0 "$1"; printf 'x\n' >> "$work/errors"; exit 0; }
 [ -f "$path" ] && [ ! -L "$path" ] && [ -r "$path" ] || error unreadable_or_nonregular
 rel=${path#"$root"/}
 tab=$(printf '\t'); nl='
'
 case "$rel" in *"$tab"*|*"$nl"*) error unsupported_path;; esac
 case "$rel" in *.md) :;; *) error non_markdown_file;; esac
 before=$(hash_file "$path") || error hash_failed; [ "${#before}" = 64 ] || error hash_failed
 if ! iconv -f UTF-8 -t UTF-8 "$path" >/dev/null 2>&1; then error invalid_utf8; fi
 if ! { if od -An -v -t u1 "$path" 2>/dev/null; then printf 'PRODUCER_OK\n'; else printf 'PRODUCER_ERROR\n'; fi; } | awk '$0=="PRODUCER_OK"{ok=1;next} $0=="PRODUCER_ERROR"{bad=1;next} {for(i=1;i<=NF;i++){b=$i+0;if(need){if(b<low||b>high)bad=1;need--;low=128;high=191}else if(b==0)bad=1;else if(b<=127){}else if(b>=194&&b<=223){need=1;low=128;high=191}else if(b>=224&&b<=239){need=2;low=(b==224?160:128);high=(b==237?159:191)}else if(b>=240&&b<=244){need=3;low=(b==240?144:128);high=(b==244?143:191)}else bad=1}} END{exit (!ok||bad||need)?1:0}'; then error binary_content; fi
 if ! awk -v file="F$n" -v path="$rel" -f "$(dirname "$0")/sensitive-rules.awk" "$path" >> "$work/findings" 2>/dev/null; then error content_scan_failed; fi
 after=$(hash_file "$path") || error hash_failed; [ "$before" = "$after" ] || error changed_during_scan
 printf '%s\t%s\n' "$rel" "$after" >> "$work/manifest"
 printf 'x\n' >> "$work/completed"
 }
 for path do scan_file "$path"; done
 exit 0
fi
if [ "${1-}" = --digest ]; then
 [ "${DOUGLE_SCAN_INTERNAL-}" = 1 ] || fail internal_only
 root=$2; work=$3; hash_tool=$4; shift 4
 for path do
 [ -f "$path" ] && [ ! -L "$path" ] && [ -r "$path" ] || { printf 'x\n' >> "$work/errors"; exit 0; }
 rel=${path#"$root"/}; tab=$(printf '\t'); nl='
'
 case "$rel" in *"$tab"*|*"$nl"*) printf 'x\n' >> "$work/errors"; exit 0;; esac
 value=$(hash_file "$path") || { printf 'x\n' >> "$work/errors"; exit 0; }; [ "${#value}" = 64 ] || { printf 'x\n' >> "$work/errors"; exit 0; }
 printf '%s\t%s\n' "$rel" "$value" >> "$work/recheck"
 done
 exit 0
fi
[ "$#" = 1 ] || fail expected_one_staged_directory
case "$1" in /*) :;; *) fail absolute_directory_required;; esac
[ -d "$1" ] && [ ! -L "$1" ] || fail invalid_directory
root=$(CDPATH='' cd "$1" 2>/dev/null && pwd -P) || fail invalid_directory
for tool in awk find sort cmp iconv od mktemp dirname basename wc tr rm sh cat; do command -v "$tool" >/dev/null 2>&1 || fail missing_system_tool; done
if command -v sha256sum >/dev/null 2>&1; then hash_tool=sha256sum
elif command -v shasum >/dev/null 2>&1; then hash_tool=shasum
else fail missing_sha256_tool; fi
self_dir=$(CDPATH='' cd "$(dirname "$0")" && pwd -P); self="$self_dir/$(basename "$0")"
[ -r "$self_dir/sensitive-rules.awk" ] && [ -r "$self_dir/check-sensitive-fast.pl" ] || fail missing_scanner_resource
work=$(mktemp -d "${TMPDIR:-/tmp}/dougle-publish-scan.XXXXXXXX" 2>/dev/null) || fail temporary_directory_failed
trap 'rm -rf "$work"' 0
trap 'exit 2' HUP INT TERM
# Optional standard-system Perl batches I/O; both paths use the exact same AWK rules.
# Explicit legacy mode supports old hosts and differential verification, never a weaker scan.
if [ "${DOUGLE_SCAN_ENGINE-}" != legacy ] && command -v perl >/dev/null 2>&1 &&
   perl -MDigest::SHA -MEncode -MJSON::PP -MFcntl=O_NOFOLLOW -e 1 >/dev/null 2>&1; then
 if perl "$self_dir/check-sensitive-fast.pl" "$root" "$self_dir/sensitive-rules.awk" 2>/dev/null; then exit 0; else exit $?; fi
fi
for name in findings errors manifest completed recheck; do : > "$work/$name"; done
printf '0\n' > "$work/count"
export DOUGLE_SCAN_INTERNAL=1
if ! find "$root" ! -type d ! -type f -exec sh -c 'printf "x\n" >> "$1/errors"' sh "$work" \; 2>/dev/null; then fail traversal_failed; fi
if ! find "$root" -type f -exec sh -c 'for path do printf "x\n"; done' sh {} + > "$work/expected" 2>/dev/null; then fail enumeration_failed; fi
expected=$(awk 'END{print NR}' "$work/expected") || fail enumeration_failed
[ "$expected" -gt 0 ] || fail empty_package
if ! find "$root" -type f -exec sh "$self" --scan "$root" "$work" "$hash_tool" {} + >/dev/null 2>&1; then fail traversal_failed; fi
completed=$(awk 'END{print NR}' "$work/completed") || fail completion_count_failed
if ! find "$root" -type f -exec sh "$self" --digest "$root" "$work" "$hash_tool" {} + >/dev/null 2>&1; then fail traversal_failed; fi
if ! find "$root" ! -type d ! -type f -exec sh -c 'printf "x\n" >> "$1/errors"' sh "$work" \; 2>/dev/null; then fail traversal_failed; fi
sort "$work/manifest" > "$work/manifest.sorted" 2>/dev/null || fail manifest_sort_failed
sort "$work/recheck" > "$work/recheck.sorted" 2>/dev/null || fail manifest_sort_failed
if [ "$expected" != "$completed" ] || [ -s "$work/errors" ] || ! cmp -s "$work/manifest.sorted" "$work/recheck.sorted"; then fail incomplete_or_changed_package; fi
digest=$(hash_file "$work/manifest.sorted") || fail digest_failed; [ "${#digest}" = 64 ] || fail digest_failed
if [ -s "$work/findings" ]; then
 awk -F '\t' -v digest="$digest" -v files="$completed" 'BEGIN{printf "{\"status\":\"BLOCK\",\"filesChecked\":%d,\"packageDigest\":\"%s\",\"findings\":[",files,digest} {if(NR<=20){if(NR>1)printf ",";printf "{\"fileId\":\"%s\",\"line\":%d,\"rule\":\"%s\"}",$1,$2,$3}} END{printf "],\"findingCount\":%d}\n",NR}' "$work/findings"
 exit 1
fi
printf '{"status":"PASS","filesChecked":%s,"packageDigest":"%s","findingCount":0}\n' "$completed" "$digest"
