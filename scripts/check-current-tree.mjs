// Current repository boundaries and documentation links; never starts an app.
import assert from 'node:assert/strict'
import {access,readFile,readdir} from 'node:fs/promises'
import {resolve,dirname} from 'node:path'
const root=resolve(import.meta.dirname,'..')
for(const name of ['backend','frontend','desktop','fixtures','third-party']) {
 let exists=true;try{await access(resolve(root,name))}catch{exists=false}
 assert(!exists,`retired source returned: ${name}`)
}
const workflows=await readdir(resolve(root,'.github/workflows'))
assert.deepEqual(workflows,['verify-skills-and-bot.yml'])
async function markdown(dir) {
 const result=[]
 for(const entry of await readdir(dir,{withFileTypes:true})) {
  const path=resolve(dir,entry.name)
  if(entry.isDirectory())result.push(...await markdown(path))
  else if(path.endsWith('.md'))result.push(path)
 }
 return result
}
let links=0;const errors=[]
for(const file of [resolve(root,'README.md'),...await markdown(resolve(root,'docs')),...await markdown(resolve(root,'skills'))]) {
 const text=(await readFile(file,'utf8')).replace(/```[\s\S]*?```/g,'')
 for(const [,target] of text.matchAll(/\]\(([^)]+)\)/g)) {
  if(/^[a-z]+:|^#/i.test(target))continue
  const path=decodeURIComponent(target.split('#')[0])
  try {await access(resolve(dirname(file),path));links++}catch {errors.push(`${file.slice(root.length+1)} -> ${target}`)}
 }
}
assert.deepEqual(errors,[],'broken local documentation links')
console.log(JSON.stringify({status:'PASS',retiredRoots:5,currentWorkflows:1,localLinks:links}))
