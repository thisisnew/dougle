// Developer-only synthetic fixtures. No actual credentials or user memory.
import assert from 'node:assert/strict'
import {mkdtemp,writeFile,mkdir,rm,symlink,chmod} from 'node:fs/promises'
import {tmpdir} from 'node:os'
import {join,resolve} from 'node:path'
import {spawnSync} from 'node:child_process'
const script=resolve('skills/dougle-publish/scripts/check-sensitive.sh')
const root=await mkdtemp(join(tmpdir(),'dougle-sensitive-fixtures-'))
const tests=[]
async function run(name,text,expected,{file='manifest.md',extra,env}={}) {
 const dir=join(root,String(tests.length));await mkdir(dir)
 const path=join(dir,file);await writeFile(path,text)
 if(extra)await extra(dir,path)
 const result=spawnSync('/bin/sh',[script,dir],{encoding:'utf8',env:env??process.env})
 let report;try{report=JSON.parse(result.stdout)}catch{assert.fail(name+': no structured result')}
 assert.equal(result.status,expected,name+': wrong exit')
 assert.equal(report.status,expected===0?'PASS':expected===1?'BLOCK':'ERROR',name+': wrong status')
 assert.equal(result.stderr,'',name+': unexpected stderr')
 assert(!result.stdout.includes(dir),name+': path leaked')
 if(expected===1) {
  assert(report.findingCount>0,name+': findings absent')
  for(const item of report.findings)assert.deepEqual(Object.keys(item).sort(),['fileId','line','rule'])
 }
 tests.push({name,status:'PASS'});return{dir,path,report}
}
try {
 await run('ordinary domain claim','Statement: receiving confirms a receipt.\n',0)
 await run('Korean text and code names','입하 확정: receipt\nFeatureFlagClient is FliptClient.\n',0,{file:'입하 지식.md'})
 await run('literal backslash and leading dash path','ordinary claim\n',0,{file:'-claim\\name.md'})
 const batch=await run('batched files retain unique finding IDs','safe\n',1,{extra:async d=>{
  for(let i=0;i<24;i++)await writeFile(join(d,`claim-${i}.md`),'password: synthetic-batch-value\n')
 }})
 assert.equal(batch.report.filesChecked,25)
 assert.equal(batch.report.findingCount,24)
 assert.equal(new Set(batch.report.findings.map(f=>f.fileId)).size,20) // report deliberately caps details
 for(const key of ['X-Amz-Signature','X-Goog-Signature','X-Amz-Credential','sig','signature','access_token','refresh_token','api_key']) {
  await run('signed URL '+key,'https://example.invalid/object?'+key+'='+'A'.repeat(40)+'\n',1)
 }
 await run('HTML escaped signed URL','https://example.invalid/object?q=1&amp;sig='+'A'.repeat(40)+'\n',1)
 await run('redacted URL token','https://example.invalid/object?sig=REDACTED\n',0)
 await run('signed URL in filename','safe\n',1,{file:'link?sig='+'A'.repeat(40)+'.md'})
 await run('non-sensitive URL query','https://example.invalid/object?page=2&limit=30\n',0)
 await run('unterminated final line','password: synthetic-final-line',1)
 await run('CRLF secret line','safe\r\npassword: synthetic-crlf\r\n',1)
 await run('late binary invalidates whole batch','safe\n',2,{extra:async d=>{
  for(let i=0;i<25;i++)await writeFile(join(d,`late-${i}.md`),'safe\n')
  await writeFile(join(d,'late-binary.md'),Buffer.from([65,0,66]))
 }})
 await run('unlabelled commit hashes','Revision: '+ 'a'.repeat(40)+'\nClaim ID: C01\n',0)
 await run('explicit redaction','password: <redacted>\napi_key = [REDACTED]\nsecret: ***\ntoken: ${TOKEN}\n',0)
 await run('quoted redacted JSON','{"password":"REDACTED"}\n',0)
 await run('password assignment','password: '+ 'fake'+'-password'+'-fixture\n',1)
 await run('case mixed password','PaSsWoRd = "'+ 'not'+'aRealSecret'+'"\n',1)
 await run('JSON client secret','{"client_secret":"'+ 'fixture'+'12345'+'"}\n',1)
 await run('Korean password','비밀번호: '+ '가상'+'암호'+'입니다\n',1)
 await run('multiline credential key','password:\n  "'+ 'fixture'+'secret'+'"\n',1)
 await run('generic token assignment','token = '+ 'fixture'+'-value\n',1)
 await run('database credential URI','mysql://synthetic:fixture-password@db.invalid/schema\n',1)
 await run('GitHub token','gh'+'p_'+ 'A'.repeat(36)+'\n',1)
 await run('GitHub fine grained token','github_'+'pat_'+ 'B'.repeat(60)+'\n',1)
 await run('Slack token','xo'+'xb-'+ '1'.repeat(12)+'-'+ 'A'.repeat(24)+'\n',1)
 await run('Slack app token','xapp-'+ 'A'.repeat(30)+'\n',1)
 await run('GitLab token','glpat-'+ 'G'.repeat(30)+'\n',1)
 await run('Google key','AIza'+ 'H'.repeat(35)+'\n',1)
 await run('Huggingface token','hf_'+ 'J'.repeat(30)+'\n',1)
 await run('NPM token','npm_'+ 'K'.repeat(30)+'\n',1)
 await run('Payment provider key','sk_'+'live_'+ 'L'.repeat(30)+'\n',1)
 await run('AWS secret assignment','AWS_SECRET_ACCESS_KEY = '+ 'M'.repeat(40)+'\n',1)
 await run('AWS access key','AK'+'IA'+ 'B'.repeat(16)+'\n',1)
 await run('API key','s'+'k-'+ 'C'.repeat(45)+'\n',1)
 await run('bearer value','Authorization: Bearer '+ 'D'.repeat(40)+'\n',1)
 await run('JWT candidate','ey'+'J'+ 'A'.repeat(20)+'.'+ 'B'.repeat(30)+'.'+ 'C'.repeat(30)+'\n',1)
 await run('private key marker','-----BEGIN OPENSSH PRIVATE KEY-----\nsynthetic-only\n',1)
 await run('email','synthetic.person'+'@'+'example.invalid\n',1)
 await run('Korean mobile','01'+'0-12'+'34-56'+'78\n',1)
 await run('international mobile','+82-'+'10-12'+'34-56'+'78\n',1)
 await run('resident identifier candidate','90'+'0101-'+'1'+'2'.repeat(6)+'\n',1)
 await run('personal field','employee_name: synthetic person\n',1)
 const hidden='SECRET_SENTINEL_NOT_FOR_LOGS'
 const leaking=await run('finding report contains no value','password: '+hidden+'\n',1)
 const out=spawnSync('/bin/sh',[script,leaking.dir],{encoding:'utf8'})
 assert(!out.stdout.includes(hidden)&&!out.stderr.includes(hidden),'secret leaked')
 await run('non-Markdown blocked','synthetic\n',2,{file:'data.txt'})
 await run('binary Markdown blocked',Buffer.from([65,0,66]),2)
 await run('invalid encoding blocked',Buffer.from([0xff,0xfe,0x80]),2)
 await run('symlink blocked','safe\n',2,{extra:async d=>symlink('/does-not-exist',join(d,'linked.md'))})
 await run('tab in filename blocked','safe\n',2,{file:'bad\tname.md'})
 await run('newline in filename blocked','safe\n',2,{file:'bad\nname.md'})
 if(process.geteuid?.()!==0)await run('unreadable file blocked','safe\n',2,{extra:async(d,p)=>chmod(p,0)})
 await run('missing tool blocked','safe\n',2,{env:{PATH:'/nonexistent',TMPDIR:tmpdir()}})
 await run('later JSON assignment is checked','{"password":"REDACTED","client_secret":"synthetic-sensitive-value"}\n',1)
 await run('later bearer is checked','Bearer REDACTED; Bearer synthetic-sensitive-value\n',1)
 await run('email filename blocked','safe domain knowledge\n',1,{file:'synthetic.person@example.invalid.md'})
 await run('phone directory blocked','safe\n',1,{extra:async d=>{await mkdir(join(d,'010-1234-5678'));await writeFile(join(d,'010-1234-5678','claim.md'),'safe\n')}})
 await run('invalid identifying TMPDIR no stderr','safe\n',2,{env:{...process.env,TMPDIR:join(root,'synthetic.person@example.invalid','missing')}})
 for(const tool of ['od','sha256sum','find','sort','awk','iconv']) {
  const bin=join(root,'fault-'+tool);await mkdir(bin);await writeFile(join(bin,tool),'#!/bin/sh\nexit 1\n',{mode:0o755})
  await run('failed '+tool+' is blocked','safe\n',2,{env:{...process.env,PATH:bin+':'+process.env.PATH}})
 }
 for(const action of ['content','rename','addition']) {
  const bin=join(root,'mutate-'+action);await mkdir(bin)
  const hook=`#!/bin/sh
if [ ! -f "$DOUGLE_MUTATE_ROOT/.mutation-marker" ]; then
 : > "$DOUGLE_MUTATE_ROOT/.mutation-marker"
 case "$DOUGLE_MUTATE_ACTION" in
 content) printf 'changed domain text\\n' > "$DOUGLE_MUTATE_ROOT/manifest.md";;
 rename) mv "$DOUGLE_MUTATE_ROOT/manifest.md" "$DOUGLE_MUTATE_ROOT/renamed.md";;
 addition) printf 'extra domain text\\n' > "$DOUGLE_MUTATE_ROOT/extra.md";;
 esac
 rm -f "$DOUGLE_MUTATE_ROOT/.mutation-marker"
fi
exec /usr/bin/od "$@"
`
  await writeFile(join(bin,'od'),hook,{mode:0o755})
  const mutateEnv={...process.env,PATH:bin+':'+process.env.PATH,DOUGLE_MUTATE_ACTION:action}
  await run('during-scan '+action+' change blocked','safe domain text\n',2,{env:mutateEnv,extra:async d=>{mutateEnv.DOUGLE_MUTATE_ROOT=d}})
 }
 const changed=await run('clean digest emitted','original domain statement\n',0)
 await writeFile(changed.path,'changed domain statement\n')
 const changedReport=JSON.parse(spawnSync('/bin/sh',[script,changed.dir],{encoding:'utf8'}).stdout)
 assert.notEqual(changedReport.packageDigest,changed.report.packageDigest,'digest ignored edit')
 tests.push({name:'digest changes when staged content changes',status:'PASS'})
  await run('nested file is scanned','safe\n',1,{extra:async d=>{await mkdir(join(d,'topics'));await writeFile(join(d,'topics','nested.md'),'password: synthetic-nested\n')}})
 const empty=join(root,'empty');await mkdir(empty)
 assert.equal(spawnSync('/bin/sh',[script,empty],{encoding:'utf8'}).status,2);tests.push({name:'empty package blocked',status:'PASS'})
 assert.equal(spawnSync('/bin/sh',[script,join(root,'missing')],{encoding:'utf8'}).status,2);tests.push({name:'missing package blocked',status:'PASS'})
 console.log(JSON.stringify({status:'PASS',cases:tests.length,tests},null,2))
}finally{await rm(root,{recursive:true,force:true})}
