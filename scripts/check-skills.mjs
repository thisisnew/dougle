// Developer packaging checks only. Not shipped or needed by skill users.
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import {readFile, readdir, stat} from 'node:fs/promises'
import {resolve, dirname} from 'node:path'
const root = resolve(import.meta.dirname, '..')
let checks = 0
function check(value, message) { assert.ok(value, message); checks++ }
const json = async path => JSON.parse(await readFile(resolve(root,path),'utf8'))
const market = await json('.claude-plugin/marketplace.json')
check(market.plugins.length === 1 && market.plugins[0].source === './plugins/dougle', 'marketplace source')
for (const host of ['claude','codex']) {
  const p = await json(`plugins/dougle/.${host}-plugin/plugin.json`)
  check(p.name === 'dougle' && p.version === '0.8.23', `${host} manifest`)
}
async function files(dir) {
 const out=[]
 for (const item of await readdir(dir,{withFileTypes:true})) {
  const p=resolve(dir,item.name)
  check(!item.isSymbolicLink(),`no link ${p}`)
  if(item.isDirectory()) out.push(...await files(p)); else out.push(p)
 }
 return out
}
for (const name of ['dougle-connect','dougle-ask','dougle-publish','dougle-fetch','dougle-schedule','dougle-bot','i-have-adhd','plain-language']) {
 const dir=resolve(root,'skills',name)
 const all=await files(dir)
 check(all.length===(name==='dougle-bot'?7:name==='dougle-schedule'?5:['i-have-adhd','plain-language'].includes(name)?2:name==='dougle-ask'?9:name==='dougle-publish'?13:name==='dougle-fetch'?9:7),'only declared Markdown skill resources')
 for(const p of all) {
  const isPublishScanner = name==='dougle-publish' && ['scripts/check-sensitive.sh','scripts/check-sensitive-fast.pl','scripts/sensitive-rules.awk'].includes(p.slice(dir.length+1))
  const isBotResource = name==='dougle-bot' && ['scripts/bot.sh','assets/slack-manifest.yaml','assets/requirements.txt','assets/runtime.sha256','assets/dougle_bot-0.1.20-py3-none-any.whl'].includes(p.slice(dir.length+1))
  check(p.endsWith('.md') || isPublishScanner || isBotResource,'only declared skill resources and optional bot runtime')
  if(p.endsWith('.whl')) {
    const relative=p.slice(resolve(root,'skills').length+1)
    check((await readFile(p)).equals(await readFile(resolve(root,'plugins/dougle/skills',relative))),'wheel bytes identical')
    continue
  }
  const text=await readFile(p,'utf8')
  const relative=p.slice(resolve(root,'skills').length+1)
  check(text===await readFile(resolve(root,'plugins/dougle/skills',relative),'utf8'),'plugin copy current')
  for(const match of (p.endsWith('.md') ? text : '').matchAll(/\]\(([^)]+)\)/g)) {
   if(match[1].includes('://')) continue
   check((await stat(resolve(dirname(p),match[1]))).isFile(),'reference resolves')
  }
 }
 const skill=await readFile(resolve(dir,'SKILL.md'),'utf8')
 check(skill.startsWith(`---\nname: ${name}\ndescription: `),'skill metadata')
}
check(await readFile(resolve(root,'skills/dougle-connect/references/organization.md'),'utf8')===await readFile(resolve(root,'skills/dougle-ask/references/organization.md'),'utf8'),'shared protocol')
for (const name of ['dougle-publish','dougle-fetch']) check(await readFile(resolve(root,'skills/dougle-connect/references/organization.md'),'utf8')===await readFile(resolve(root,`skills/${name}/references/organization.md`),'utf8'),'shared organization')
check(await readFile(resolve(root,'skills/dougle-publish/references/sharing.md'),'utf8')===await readFile(resolve(root,'skills/dougle-fetch/references/sharing.md'),'utf8'),'shared merge protocol')
for (const [file, expected] of [
 ['i-have-adhd.md','88955c019bbb1419ff4b49dbb1f80a867467de6233d7865af2e06a7d7b76c7d3'],
 ['i-have-adhd-LICENSE.md','8acbb618089b738404f76ac60fca1aaa995d9a87995eddf95f0b777acd7f7d2c']
]) {
 const bytes=await readFile(resolve(root,'skills/dougle-ask/references',file))
 check(createHash('sha256').update(bytes).digest('hex')===expected, 'pinned upstream guide/license bytes')
}
for (const resource of ['store.md','retrieval.md','records.md','freshness.md','operations.md']) {
 const canonical=await readFile(resolve(root,'skills/dougle-ask/references',resource),'utf8')
 for (const name of ['connect','publish','fetch']) check(canonical===await readFile(resolve(root,`skills/dougle-${name}/references`,resource),'utf8'),'shared detail identical')
}
const plain=await readFile(resolve(root,'skills/plain-language/SKILL.md'),'utf8')
check(plain.includes('user-invocable: false'),'plain-language hidden from user commands')
check(!plain.includes('disable-model-invocation:'),'plain-language model callable')
check(await readFile(resolve(root,'skills/dougle-publish/references/merge.md'),'utf8')===await readFile(resolve(root,'skills/dougle-fetch/references/merge.md'),'utf8'),'shared ordered merge rules')
const companion=await readFile(resolve(root,'skills/i-have-adhd/SKILL.md'),'utf8')
check(!companion.includes('disable-model-invocation:'),'companion model invocation enabled')
check(companion.includes('user-invocable: false'),'companion hidden from Claude user commands')
check(await readFile(resolve(root,'skills/i-have-adhd/LICENSE.md'),'utf8')===await readFile(resolve(root,'skills/dougle-ask/references/i-have-adhd-LICENSE.md'),'utf8'),'companion license retained')
const expected=['dougle-connect','dougle-ask','dougle-publish','dougle-fetch','dougle-schedule','dougle-bot','i-have-adhd','plain-language'].sort()
for(const base of ['skills','plugins/dougle/skills']) check(JSON.stringify((await readdir(resolve(root,base))).sort())===JSON.stringify(expected),'only current skills distributed')
console.log(JSON.stringify({status:'PASS',structuralChecks:checks,semanticModelEvaluation:false}))
