// Developer verification of the shipped decision table and local Git concurrency.
// Does not execute a host AI, prove semantic matching, or touch a real repository.
import assert from 'node:assert/strict'
import {readFile,mkdtemp,writeFile,rm} from 'node:fs/promises'
import {tmpdir} from 'node:os'
import {join,resolve} from 'node:path'
import {spawnSync} from 'node:child_process'
const text=await readFile(resolve('skills/dougle-publish/references/merge.md'),'utf8')
const rows=text.split('\n').filter(l=>/^\| [01] \|/.test(l)).map(l=>l.split('|').slice(1,-1).map(x=>x.trim()))
assert.equal(rows.length,12)
const present=x=>Number(x!==null), eq=(a,b)=>Number(a!==null&&b!==null&&a===b)
function decision(b,l,r) {
 const input=[present(b),present(l),present(r),eq(l,r),eq(l,b),eq(r,b)]
 const matches=rows.filter(row=>input.every((v,i)=>row[i]==='*'||row[i]===String(v)))
 assert.equal(matches.length,1,`exactly one decision for ${JSON.stringify([b,l,r])}`)
 return matches[0][6]
}
const mirror={ADD_LOCAL:'KEEP_REMOTE',KEEP_REMOTE:'ADD_LOCAL',KEEP_LOCAL:'KEEP_REMOTE',LOCAL_CHANGE:'REMOTE_CHANGE',REMOTE_CHANGE:'LOCAL_CHANGE'}
let cases=0
for(const b of [null,'07:00','08:00','09:00'])for(const l of [null,'07:00','08:00','09:00'])for(const r of [null,'07:00','08:00','09:00']) {
 const action=decision(b,l,r);cases++
 if(l!==null&&r!==null) {
  if(l===r)assert.equal(action,'SAME')
  else if(b===l)assert.equal(action,'REMOTE_CHANGE')
  else if(b===r)assert.equal(action,'LOCAL_CHANGE')
  else assert.equal(action,'CONFLICT','different simultaneous changes must retain alternatives')
 } else if(l===null&&r===null)assert.equal(action,b===null?'NO_CHANGE':'RETAIN_BASE')
 else assert(['ADD_LOCAL','KEEP_LOCAL','KEEP_REMOTE'].includes(action),'omission must not delete survivor')
 if(l!==null&&r!==null) assert.equal(decision(b,r,l),mirror[action]??action,'no author-role winner')
 assert.equal(decision(b,l,r),action,'repeat input has same decision')
}
const root=await mkdtemp(join(tmpdir(),'dougle-merge-'))
function git(cwd,args,ok=true) {
 const out=spawnSync('git',['-c','core.hooksPath=/dev/null','-c','user.name=Fixture','-c','user.email=fixture@example.test',...args],{cwd,encoding:'utf8',env:{...process.env,GIT_CONFIG_GLOBAL:'/dev/null',GIT_CONFIG_NOSYSTEM:'1',GIT_TERMINAL_PROMPT:'0'}})
 if(ok)assert.equal(out.status,0,out.stderr)
 return out
}
let transportChecks=0
try {
 git(root,['init','--bare','remote.git']);git(root,['init','-b','main','a'])
 const a=join(root,'a'),b=join(root,'b'),remote=join(root,'remote.git')
 await writeFile(join(a,'base.md'),'Stable domain baseline.\n');git(a,['add','base.md']);git(a,['commit','-m','base']);git(a,['remote','add','origin',remote]);git(a,['push','origin','HEAD:refs/heads/dougle-knowledge'])
 git(root,['clone','--branch','dougle-knowledge',remote,'b'])
 await writeFile(join(a,'a.md'),'Sender observation.\n');git(a,['add','a.md']);git(a,['commit','-m','author-a'])
 await writeFile(join(b,'b.md'),'Recipient observation.\n');git(b,['add','b.md']);git(b,['commit','-m','author-b'])
 git(a,['push','origin','HEAD:refs/heads/dougle-knowledge'])
 assert.notEqual(git(b,['push','origin','HEAD:refs/heads/dougle-knowledge'],false).status,0);transportChecks++
 assert.equal(git(remote,['show','dougle-knowledge:a.md']).stdout,'Sender observation.\n');transportChecks++
 assert.notEqual(git(remote,['show','dougle-knowledge:b.md'],false).status,0);transportChecks++
 git(b,['fetch','origin','dougle-knowledge']);git(b,['reset','--hard','FETCH_HEAD']) // isolated synthetic checkout only
 await writeFile(join(b,'b.md'),'Recipient observation.\n');git(b,['add','b.md']);git(b,['commit','-m','reconciled-b']);git(b,['push','origin','HEAD:refs/heads/dougle-knowledge'])
 assert.equal(git(remote,['show','dougle-knowledge:a.md']).stdout,'Sender observation.\n');transportChecks++
 assert.equal(git(remote,['show','dougle-knowledge:b.md']).stdout,'Recipient observation.\n');transportChecks++
 assert.equal(git(b,['status','--porcelain']).stdout,'');transportChecks++
} finally {await rm(root,{recursive:true,force:true})}
console.log(JSON.stringify({status:'PASS',decisionTableAssignments:cases,localGitTransportChecks:transportChecks,hostAiMergeEvaluation:false}))
