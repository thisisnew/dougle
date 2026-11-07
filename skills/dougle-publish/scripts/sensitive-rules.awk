# Shared rules for both scanner paths. No content or filenames are emitted.
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
  rest=text
  while(match(tolower(rest), /[?&](amp;)?(x-amz-signature|x-goog-signature|x-amz-credential|signature|sig|access_token|refresh_token|api_key)[=][^&[:space:]"<>]+/)) {
   value=substr(rest,RSTART,RLENGTH); rest=substr(rest,RSTART+RLENGTH)
   sub(/^[^=]*=/,"",value)
   if(!redacted(value))emit("signed_or_credential_url")
  }
  if(text ~ /[A-Za-z0-9.!#$%&*+\/=?^_`{|}~-]+@[A-Za-z0-9-]+([.][A-Za-z0-9-]+)+/) emit("email_address")
  if(text ~ /01[016789][- .]?[0-9][0-9][0-9][0-9]?[- .]?[0-9][0-9][0-9][0-9]/)emit("phone_number")
  if(text ~ /[+]82[- .]?10[- .]?[0-9][0-9][0-9][0-9]?[- .]?[0-9][0-9][0-9][0-9]/)emit("phone_number")
  if(text ~ rrn)emit("resident_id_candidate")
  if(low ~ /(성명|개인이름|고객명|직원명|사번|고객주소|직원주소|주민등록번호|주민번호|휴대폰번호|휴대전화|개인주소|계좌번호|카드번호|customer[_ -]?email|employee[_ -]?name|full[_ -]?name)["\047`[:space:]]*[:=]/) emit("personal_data_field")
 }
 BEGIN {
 digit="[0-9]"; six=digit digit digit digit digit digit; rrn=six "-?[1-8]" six
 if(batch_root!="") {
  for(j=1;j<ARGC;j++) { ids[ARGV[j]]=offset+j; file="F" ids[ARGV[j]]; inspect(substr(ARGV[j],length(batch_root)+2),0) }
 } else inspect(path,0)
 }
 { if(batch_root!="")file="F" ids[FILENAME]; inspect($0,FNR) }
