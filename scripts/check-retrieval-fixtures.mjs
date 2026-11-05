// Developer-only checks for isolated native-model write fixtures. Not a product runtime.
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {createHash} from 'node:crypto'
const root=resolve(process.argv[2])
const before=JSON.parse(await readFile(resolve(root,'before.json'),'utf8'))
const topic=await readFile(resolve(root,'store/knowledge/receiving.md'),'utf8')
const digest=b=>createHash('sha256').update(b).digest('hex')
assert.equal(digest(await readFile(resolve(root,'originals/ReceivingService.java'))),before.originalSha,'business original changed')
assert.equal(digest(await readFile(resolve(root,'store/knowledge/billing.md'))),before.unrelatedSha,'unrelated topic changed')
for(const id of ['C01','C02','C03','C90']) assert(topic.includes(`## ${id} —`),`lost stable claim ${id}`)
const section=(id,text)=>text.split(new RegExp(`^## ${id} —.*$`,'m'))[1]?.split(/^## /m)[0]??''
const guard=section('C02',topic)
for(const token of ['cancelled','returnOrder','archived']) assert(guard.includes(token),`lost guard ${token}`)
assert(section('C03',topic).includes('receiptCancel'),'lost cancellation behavior')
const reported=section('C90',topic)
for(const token of ['야간에는 담당자 확인이 필요하다는 사용자 설명. 원본 확인 전.','reported','2026-01-02T00:00:00Z']) assert(reported.includes(token),'lost user correction/provenance')
assert(topic.includes('legacy_receipt') && /unresolved/i.test(topic),'lost unresolved conflict')
if(process.argv.includes('--navigation')) {
 assert(/Navigation(?: state)?: complete/i.test(topic),'navigation not completed')
 assert(/Navigation (?:coverage|covered)|Covered sections/i.test(topic),'navigation coverage missing')
 assert(/Requires: C02/.test(topic),'lost claim dependency')
}
console.log(JSON.stringify({status:'PASS',originalUnchanged:true,unrelatedUnchanged:true,allGuards:true,stableClaims:true,correctionAndConflictPreserved:true}))
