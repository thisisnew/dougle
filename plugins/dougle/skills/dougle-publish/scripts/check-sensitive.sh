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
 if ! { if od -An -v -t u1 "$path" 2>/dev/null; then printf 'PRODUCER_OK\n'; else printf 'PRODUCER_ERROR\n'; fi; } | awk '$0=="PRODUCER_OK"{ok=1;next} $0=="PRODUCER_ERROR"{bad=1;next} {for(i=1;i<=NF;i++)if($i==0)bad=1} END{exit (!ok||bad)?1:0}'; then error binary_content; fi
 if ! awk -v file="F$n" -v path="$rel" '
 function emit(rule) { print file "\t" rowno "\t" rule }
 function clean(v, q, end) {
  gsub(/^[[:space:]]+|[[:space:]]+$/, "", v)
  q=substr(v,1,1); if(q=="\"" || q==sprintf("%c",39) || q=="`"){v=substr(v,2); end=index(v,q); if(end)v=substr(v,1,end-1)}
  gsub(/^[[:space:]]+|[[:space:],;]+$/, "", v)
  sub(/[[:space:]]+#.*/, "", v); return v
 }
 function redacted(v, l) {
  l=tolower(clean(v));
  return l=="redacted" || l=="[redacted]" || l=="<redacted>" || l=="masked" || l=="[masked]" || l ~ /^[*]+$/ || l ~ /^\$\{[a-z_][a-z0-9_]*\}$/
 }
 function prefixed(s, pat, minimum, rule, rest,t) {
  rest=s
  while(match(rest,pat)) { t=substr(rest,RSTART,RLENGTH); if(length(t)>=minimum)emit(rule); rest=substr(rest,RSTART+RLENGTH) }
 }

 function inspect(text, line_number, low, value, rest) {
  rowno=line_number
  low=tolower(text)
  if(low ~ /-----begin .*private key-----/) emit("private_key")
  prefixed(text,"gh[pousr]_[A-Za-z0-9]+",24,"github_token")
  prefixed(text,"github_pat_[A-Za-z0-9_]+",30,"github_token")
  prefixed(text,"xox[baprs]-[A-Za-z0-9-]+",18,"slack_token")
  prefixed(text,"xapp-[A-Za-z0-9-]+",18,"slack_token")
  prefixed(text,"glpat-[A-Za-z0-9_-]+",20,"gitlab_token")
  prefixed(text,"AIza[A-Za-z0-9_-]+",30,"google_api_key")
  prefixed(text,"hf_[A-Za-z0-9]+",25,"huggingface_token")
  prefixed(text,"npm_[A-Za-z0-9]+",25,"npm_token")
  prefixed(text,"sk_(live|test)_[A-Za-z0-9]+",25,"payment_api_key")
  prefixed(text,"(AKIA|ASIA)[A-Z0-9]+",20,"aws_access_key")
  prefixed(text,"sk-[A-Za-z0-9_-]+",20,"api_key")
  prefixed(text,"eyJ[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+[.][A-Za-z0-9_-]+",45,"jwt")
  rest=text
  while(match(tolower(rest), /bearer[[:space:]]+[a-z0-9._~+\/-]+/)) {
   value=substr(rest,RSTART,RLENGTH); rest=substr(rest,RSTART+RLENGTH)
   sub(/^[^[:space:]]+[[:space:]]+/,"",value)
   if(length(value)>=8 && !redacted(value))emit("bearer_credential")
  }
  rest=text
  while(match(tolower(rest), /(password|passwd|pwd|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|secret[_ -]?key|secret[_ -]?access[_ -]?key|token|secret|authorization|cookie|connection[_ -]?string|비밀번호|비번|토큰|비밀키)["\047`[:space:]]*[:=][[:space:]]*/)) {
   rest=substr(rest,RSTART+RLENGTH); value=clean(rest);
   if(!redacted(value))emit("credential_assignment")
  }
  if(text ~ /[A-Za-z][A-Za-z0-9+.-]*:\/\/[^[:space:]@\/]+:[^[:space:]@\/]+@/)emit("credential_uri")
  if(text ~ /[A-Za-z0-9.!#$%&*+\/=?^_`{|}~-]+@[A-Za-z0-9-]+([.][A-Za-z0-9-]+)+/) emit("email_address")
  if(text ~ /01[016789][- .]?[0-9][0-9][0-9][0-9]?[- .]?[0-9][0-9][0-9][0-9]/)emit("phone_number")
  if(text ~ /[+]82[- .]?10[- .]?[0-9][0-9][0-9][0-9]?[- .]?[0-9][0-9][0-9][0-9]/)emit("phone_number")
  if(text ~ rrn)emit("resident_id_candidate")
  if(low ~ /(성명|개인이름|고객명|직원명|사번|고객주소|직원주소|주민등록번호|주민번호|휴대폰번호|휴대전화|개인주소|계좌번호|카드번호|customer[_ -]?email|employee[_ -]?name|full[_ -]?name)["\047`[:space:]]*[:=]/) emit("personal_data_field")
 }
 BEGIN { digit="[0-9]"; six=digit digit digit digit digit digit; rrn=six "-?[1-8]" six; inspect(path,0) }
 { inspect($0,NR) }
 ' "$path" >> "$work/findings" 2>/dev/null; then error content_scan_failed; fi
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
work=$(mktemp -d "${TMPDIR:-/tmp}/dougle-publish-scan.XXXXXXXX" 2>/dev/null) || fail temporary_directory_failed
trap 'rm -rf "$work"' 0
trap 'exit 2' HUP INT TERM
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
