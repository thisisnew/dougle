import assert from 'node:assert/strict'
import {mkdtempSync, writeFileSync, mkdirSync, rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join, resolve} from 'node:path'
import {spawnSync} from 'node:child_process'
import {createHash} from 'node:crypto'
const root=mkdtempSync(join(tmpdir(),'dougle-publication-')), stage=join(root,'stage')
mkdirSync(stage)
const text='Original evidence and conditions.\n'
writeFileSync(join(stage,'topic.md'),text)
const files={'topic.md':createHash('sha256').update(text).digest('hex')}
const snapshot=claims=>({format:'dougle-publication/v1',project:'p',claims,files})
const claim=(statement='confirmed after approval')=>({id:'source-a/C01',scope:'source-a',core:{statement,scope:'source-a',dependencies:[]},dependencies:[],evidence:[{source:'source-a',revision:'v1',locator:'section1',provenance:'observed'}],history:[],alternatives:[],status:'needs_recheck'})
let checks=0
function run(b,l,r,f,expected=null){
 for(const [name,claims] of Object.entries({b,l,r,f}))writeFileSync(join(root,name),JSON.stringify(snapshot(claims)))
 writeFileSync(join(stage,'publication.md'),'# Dougle publication\n\n```json\n'+JSON.stringify(snapshot(f))+'\n```\n')
 const p=spawnSync('perl',[resolve('skills/dougle-publish/scripts/check-publication.pl'),...['b','l','r','f'].map(n=>join(root,n)),stage],{encoding:'utf8'})
 if(expected) {assert.notEqual(p.status,0);assert.ok(p.stderr.includes(expected),p.stderr)}
 else {assert.equal(p.status,0,p.stderr);assert.equal(JSON.parse(p.stdout.split('```json\n')[1].split('\n```')[0]).format,'dougle-publication-ledger/v1')}
 if(!expected){
  writeFileSync(join(stage,'publication-ledger.md'),p.stdout)
  const scan=spawnSync('/bin/sh',[resolve('skills/dougle-publish/scripts/check-sensitive.sh'),stage],{encoding:'utf8'})
  assert.equal(scan.status,0,scan.stdout+scan.stderr)
 }
 checks++
}
try {
 const c=claim(), changed=claim('confirmed before approval')
 run([], [c], [], [c])
 run([c], [c], [c], [c])
 run([c], [], [], [c])
 run([c], [], [c], [],'missing_claim')
 run([], [c,c], [], [c],'duplicate_id')
 run([c], [changed], [c], [changed],'history_lost')
 changed.history=[c.core]
 run([c], [changed], [c], [changed])
 const other=claim('confirmed only after QA'), conflict=structuredClone(changed)
 conflict.history=[c.core,other.core];conflict.alternatives=[c.core,changed.core,other.core];conflict.status='unresolved'
 run([c],[changed],[other],[conflict])
 run([c],[changed],[other],[changed],'history_lost')
 const lost=structuredClone(c);lost.evidence=[{...c.evidence[0],revision:'v2'}]
 run([c],[lost],[c],[lost],'evidence_lost')
 const dep=structuredClone(c);dep.dependencies=['missing'];dep.core.dependencies=['missing']
 run([],[dep],[],[dep],'dependency_missing')
 const fabricated=structuredClone(c);fabricated.status='verified'
 run([],[c],[],[fabricated],'new_verification_claim')
 const collision=structuredClone(c);collision.scope='b';collision.core.scope='b'
 run([c],[collision],[c],[collision],'identity_scope_collision')
 const ordered=claim();ordered.core.steps=['a','b']
 const reversed=structuredClone(ordered);reversed.core.steps.reverse()
 run([ordered],[reversed],[ordered],[ordered],'history_lost')
 writeFileSync(join(stage,'topic.md'),'tampered')
 run([],[c],[],[c],'package_bytes_mismatch')
 console.log(JSON.stringify({status:'PASS',checks,meaningVerified:false}))
} finally {rmSync(root,{recursive:true})}
