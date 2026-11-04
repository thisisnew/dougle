// Developer packaging checks only. Not shipped or needed by skill users.
import assert from 'node:assert/strict'
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
  check(p.name === 'dougle' && p.version === '0.3.0', `${host} manifest`)
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
for (const name of ['dougle-connect','dougle-ask']) {
 const dir=resolve(root,'skills',name)
 const all=await files(dir)
 check(all.length===2,'only instruction and organization reference')
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
console.log(JSON.stringify({status:'PASS',structuralChecks:checks,semanticModelEvaluation:false}))
