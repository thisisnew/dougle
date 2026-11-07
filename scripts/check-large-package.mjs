// Developer-only transport experiment, not a semantic merge implementation.
// Real Git + 10,000 synthetic Markdown files; no network, model or user knowledge.
import assert from 'node:assert/strict'
import {mkdtemp, writeFile, mkdir, rm} from 'node:fs/promises'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {spawnSync} from 'node:child_process'
const root=await mkdtemp(join(tmpdir(),'dougle-inventory-'))
const started=performance.now()
let checks=0
function git(args) {
 const out=spawnSync('git',['-c','core.hooksPath=/dev/null','-c','core.autocrlf=false','-c','user.name=Fixture','-c','user.email=fixture@example.test',...args],{
  cwd:root,encoding:'utf8',maxBuffer:4*1024*1024,
  env:{...process.env,GIT_CONFIG_GLOBAL:'/dev/null',GIT_CONFIG_NOSYSTEM:'1',GIT_TERMINAL_PROMPT:'0'}
 })
 assert.equal(out.status,0,out.stderr)
 return out.stdout
}
function tree(commit) {
 const entries=git(['ls-tree','-r','-z',commit,'--','knowledge/']).split('\0').filter(Boolean)
 return new Map(entries.map(entry=>{
  const [metadata,path]=entry.split('\t');const [mode,type,oid]=metadata.split(' ')
  assert.equal(mode,'100644');assert.equal(type,'blob')
  return [path,oid]
 }))
}
try {
 git(['init','-b','main'])
 await mkdir(join(root,'knowledge'))
 const path=i=>`knowledge/topic-${String(i).padStart(5,'0')}.md`
 for(let start=0;start<10000;start+=100)await Promise.all(Array.from({length:100},(_,j)=>{
  const i=start+j;return writeFile(join(root,path(i)),`Claim: C${i}\nCondition: only after approval.\n`)
 }))
 git(['add','knowledge']);git(['commit','-m','synthetic baseline'])
 const base=git(['rev-parse','HEAD']).trim();const before=tree(base)
 assert.equal(before.size,10000);checks++
 for(let i=0;i<5;i++)await writeFile(join(root,path(i)),`Claim: C${i}\nCondition: after independent review and approval.\n`)
 for(let i=10000;i<10003;i++)await writeFile(join(root,path(i)),`Claim: C${i}\nNew supported observation.\n`)
 for(let i=5;i<7;i++)await rm(join(root,path(i)))
 git(['add','knowledge']);git(['commit','-m','ten changed paths'])
 const pinned=git(['rev-parse','HEAD']).trim();const after=tree(pinned)
 assert.equal(after.size,10001);checks++
 const changed=new Set([...before.keys(),...after.keys()].filter(path=>before.get(path)!==after.get(path)))
 assert.equal(changed.size,10);checks++
 assert.equal([...after].filter(([path,oid])=>before.get(path)===oid).length,9993);checks++
 assert.equal([...before].filter(([path])=>!after.has(path)).length,2);checks++
 assert.deepEqual(new Set(git(['diff','--name-only',base,pinned,'--','knowledge/']).trim().split('\n')),changed);checks++
 // Same path is not enough for cache reuse: check actual bytes against the recorded blob.
 await writeFile(join(root,path(10)),'Corrupted cached content.\n')
 assert.notEqual(git(['hash-object','--',path(10)]).trim(),before.get(path(10)));checks++
 assert.equal(git(['show',`${base}:${path(10)}`]),'Claim: C10\nCondition: only after approval.\n');checks++
 // A moving branch must not mix new bytes into the already pinned package.
 await writeFile(join(root,path(0)),'Later remote observation.\n')
 git(['add','knowledge']);git(['commit','-m','head moves after pin'])
 assert.equal(git(['show',`${pinned}:${path(0)}`]),'Claim: C0\nCondition: after independent review and approval.\n');checks++
 assert.equal(tree(pinned).get(path(0)),after.get(path(0)));checks++
 // Unrelated remote commits must not force package-body reprocessing.
 const beforeUnrelated=tree('HEAD')
 await writeFile(join(root,'outside.md'),'Unrelated branch update.\n')
 git(['add','outside.md']);git(['commit','-m','outside package'])
 assert.deepEqual(tree('HEAD'),beforeUnrelated);checks++
 // Large real change set, followed by repeated comparison without rewriting files.
 const massBase=git(['rev-parse','HEAD']).trim()
 for(let start=100;start<2600;start+=100)await Promise.all(Array.from({length:100},(_,j)=>{
  const i=start+j;return writeFile(join(root,path(i)),`Claim: C${i}\nChanged condition, evidence retained.\n`)
 }))
 git(['add','knowledge']);git(['commit','-m','mass change'])
 const massHead=git(['rev-parse','HEAD']).trim()
 assert.equal(git(['diff','--name-only',massBase,massHead,'--','knowledge/']).trim().split('\n').length,2500);checks++
 const massTree=tree(massHead)
 for(let i=0;i<3;i++){assert.deepEqual(tree(massHead),massTree);checks++}
 assert.equal(git(['rev-parse','HEAD']).trim(),massHead);checks++
 // Rename has the same bytes but different inventory paths, both must be considered.
 git(['mv',path(3000),'knowledge/한글 자료.md']);git(['commit','-m','rename stable knowledge'])
 const renamed=tree('HEAD')
 assert.equal(renamed.get('knowledge/한글 자료.md'),massTree.get(path(3000)));checks++
 assert(!renamed.has(path(3000)));checks++
 assert.notEqual(git(['diff','--name-status','--no-renames',massHead,'HEAD','--','knowledge/']).trim(),'');checks++
 assert.equal(git(['status','--porcelain']).trim(),'');checks++
 console.log(JSON.stringify({status:'PASS',filesAtBaseline:10000,changedPaths:10,unchangedBlobs:9993,checks,seconds:Math.round((performance.now()-started)/10)/100,hostAiMergeEvaluation:false,massChangedPaths:2500,repeatedInventoryComparisons:3}))
} finally {await rm(root,{recursive:true,force:true})}
