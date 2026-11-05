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
  check(p.name === 'dougle' && p.version === '0.5.0', `${host} manifest`)
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
for (const name of ['dougle-connect','dougle-ask','dougle-reload','dougle-publish','dougle-fetch']) {
 const dir=resolve(root,'skills',name)
 const all=await files(dir)
 check(all.length===(name==='dougle-ask'?4:['dougle-publish','dougle-fetch'].includes(name)?3:2),'only declared Markdown skill resources')
 for(const p of all) {
  check(p.endsWith('.md'),'no executable/runtime dependency')
  const text=await readFile(p,'utf8')
  const relative=p.slice(resolve(root,'skills').length+1)
  check(text===await readFile(resolve(root,'plugins/dougle/skills',relative),'utf8'),'plugin copy current')
  for(const match of text.matchAll(/\]\(([^)]+)\)/g)) {
   if(match[1].includes('://')) continue
   check((await stat(resolve(dirname(p),match[1]))).isFile(),'reference resolves')
  }
 }
 const skill=await readFile(resolve(dir,'SKILL.md'),'utf8')
 check(skill.startsWith(`---\nname: ${name}\ndescription: `),'skill metadata')
}
check(await readFile(resolve(root,'skills/dougle-connect/references/organization.md'),'utf8')===await readFile(resolve(root,'skills/dougle-ask/references/organization.md'),'utf8'),'shared protocol')
check(await readFile(resolve(root,'skills/dougle-connect/references/organization.md'),'utf8')===await readFile(resolve(root,'skills/dougle-reload/references/organization.md'),'utf8'),'reload shared protocol')
for (const name of ['dougle-publish','dougle-fetch']) check(await readFile(resolve(root,'skills/dougle-connect/references/organization.md'),'utf8')===await readFile(resolve(root,`skills/${name}/references/organization.md`),'utf8'),'shared organization')
check(await readFile(resolve(root,'skills/dougle-publish/references/sharing.md'),'utf8')===await readFile(resolve(root,'skills/dougle-fetch/references/sharing.md'),'utf8'),'shared merge protocol')
for (const [file, expected] of [
 ['i-have-adhd.md','88955c019bbb1419ff4b49dbb1f80a867467de6233d7865af2e06a7d7b76c7d3'],
 ['i-have-adhd-LICENSE.md','8acbb618089b738404f76ac60fca1aaa995d9a87995eddf95f0b777acd7f7d2c']
]) {
 const bytes=await readFile(resolve(root,'skills/dougle-ask/references',file))
 check(createHash('sha256').update(bytes).digest('hex')===expected, 'pinned upstream guide/license bytes')
}
console.log(JSON.stringify({status:'PASS',structuralChecks:checks,semanticModelEvaluation:false}))
