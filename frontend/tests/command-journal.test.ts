import {test} from 'node:test';
import assert from 'node:assert/strict';
import {CommandJournal,commandKind} from '../src/shared/command-journal';

class MemoryStorage implements Storage {
 rows=new Map<string,string>();get length(){return this.rows.size;}key(i:number){return [...this.rows.keys()][i]??null;}getItem(k:string){return this.rows.get(k)??null;}setItem(k:string,v:string){this.rows.set(k,v);}removeItem(k:string){this.rows.delete(k);}clear(){this.rows.clear();}
}
const alice={subject:'alice',subjectId:'alice-id'},bob={subject:'bob',subjectId:'bob-id'};
const body={clientMessageId:'message-1',text:'private text',expectedRevision:4};const path='/conversations/c1/messages';
test('lost committed reply reload queries same receipt without another message POST',async()=>{
 const store=new MemoryStorage();let posts=0;let key='';
 const first=new CommandJournal(()=>store,async(_subject,_path,init)=>{posts++;key=JSON.parse(init!.body as string).idempotencyKey;throw new TypeError('response lost');});first.activate(alice);await assert.rejects(first.command('alice',path,body));
 const recovered=new CommandJournal(()=>store,async(_s,p,init)=>{assert.equal(p,'/command-lookups');assert.equal(JSON.parse(init!.body as string).idempotencyKey,key);return {id:'receipt'};});recovered.activate(alice);
 assert.equal((await recovered.resume()).result.id,'receipt');assert.equal(posts,1);assert.equal(recovered.pending(),null);
});
test('request never arrived uses same command and clientMessageId after lookup not_found',async()=>{
 const store=new MemoryStorage();const requests:any[]=[];let dropped=true;
 const transport=async(_s:string,p:string,init?:RequestInit)=>{const b=JSON.parse(init!.body as string);requests.push({p,b});if(p==='/command-lookups')return {status:'not_found',authoritativeNonAcceptance:false};if(dropped){dropped=false;throw new TypeError('not sent');}return {id:'saved'};};
 const j=new CommandJournal(()=>store,transport);j.activate(alice);await assert.rejects(j.command('alice',path,body));const reload=new CommandJournal(()=>store,transport);reload.activate(alice);await reload.resume();
 assert.equal(requests.length,3);assert.equal(requests[0].b.idempotencyKey,requests[2].b.idempotencyKey);assert.equal(requests[0].b.clientMessageId,requests[2].b.clientMessageId);
});
test('identity switch redacts body, never replays and equal re-entry preserves frozen IDs',async()=>{
 const store=new MemoryStorage();const calls:any[]=[];let fail=true;
 const j=new CommandJournal(()=>store,async(s,p,init)=>{calls.push({s,p,b:JSON.parse(init!.body as string)});if(p==='/command-lookups')return {status:'not_found'};if(fail){fail=false;throw new TypeError('offline');}return {id:'saved'};});j.activate(alice);await assert.rejects(j.command('alice',path,body));const key=j.pending()!.key;
 j.lock();j.activate(bob);assert.equal(j.pending(),null);assert(!JSON.stringify([...store.rows.values()]).includes('private text'));assert.equal(calls.length,1);
 j.lock();j.activate(alice);await assert.rejects(j.resume(),/重新填写相同内容/);await assert.rejects(j.command('alice',path,{...body,text:'different'}),/不能更改内容/);
 await j.command('alice',path,{...body,expectedRevision:99,clientMessageId:'would-be-new'});const last=calls.at(-1);assert.equal(last.b.idempotencyKey,key);assert.equal(last.b.clientMessageId,body.clientMessageId);assert.equal(last.b.expectedRevision,4);
});
test('scope change cannot substitute a new logical command for unresolved original',async()=>{
 const j=new CommandJournal(()=>new MemoryStorage(),async()=>{throw new Error();});
 const store=new MemoryStorage();const pending=new CommandJournal(()=>store,async()=>{throw new TypeError('unknown');});pending.activate(alice);await assert.rejects(pending.command('alice',path,body));await assert.rejects(pending.command('alice','/conversations',{environmentId:'other'}),/另有待核查/);assert.equal(pending.pending()!.path,path);
});
test('definite CAS rejection permits a distinct corrected command while unknown 5xx does not',async()=>{
 const store=new MemoryStorage();let conflict=true;const keys:string[]=[];
 const j=new CommandJournal(()=>store,async(_s,_p,init)=>{keys.push(JSON.parse(init!.body as string).idempotencyKey);if(conflict){conflict=false;throw Object.assign(new Error('CAS'),{status:409});}return {ok:true};});j.activate(alice);await assert.rejects(j.command('alice',path,body));assert.equal(j.pending(),null);await j.command('alice',path,{...body,expectedRevision:5});assert.notEqual(keys[0],keys[1]);
 const unknown=new CommandJournal(()=>store,async()=>{throw Object.assign(new Error('maybe committed'),{status:503});});unknown.activate(alice);await assert.rejects(unknown.command('alice',path,body));assert(unknown.pending());
});
test('storage unavailable fails before sending and concurrent clicks cannot replace one ID',async()=>{
 let sends=0;const disabled=new CommandJournal(()=>{throw new Error('denied');},async()=>{sends++;});disabled.activate(alice);await assert.rejects(disabled.command('alice',path,body));assert.equal(sends,0);
 const store=new MemoryStorage();let release!:()=>void;const j=new CommandJournal(()=>store,async()=>{sends++;await new Promise<void>(r=>release=r);return {ok:true};});j.activate(alice);const first=j.command('alice',path,body);await assert.rejects(j.command('alice',path,body),/提交仍在进行/);await new Promise(r=>setTimeout(r,10));assert.equal(sends,1);release();await first;
});
test('route namespace matches actual title, decision and configuration command kinds',()=>{assert.equal(commandKind('/conversations/c/title','PATCH'),'conversation.title');assert.equal(commandKind('/approvals/a/decision','POST'),'approval.decide');assert.equal(commandKind('/admin/configurations/c/draft','PATCH'),'configuration.draft');assert.throws(()=>commandKind('/unknown','POST'));});
test('revoked lookup keeps original identity reference but removes sensitive retry body',async()=>{
 const store=new MemoryStorage();let denied=false;const j=new CommandJournal(()=>store,async()=>{if(denied)throw Object.assign(new Error('revoked'),{status:403});throw new TypeError('reply lost');});j.activate(alice);await assert.rejects(j.command('alice',path,body));const key=j.pending()!.key;denied=true;await assert.rejects(j.resume());assert.equal(j.pending()!.key,key);assert.equal(j.pending()!.body,undefined);assert(!JSON.stringify([...store.rows.values()]).includes('private text'));
});
test('switch away and back while lookup is in flight does not resume the old request',async()=>{
 const store=new MemoryStorage();let release!:(value:any)=>void;let calls=0;
 const j=new CommandJournal(()=>store,async(_s,p)=>{calls++;if(p==='/command-lookups')return new Promise(resolve=>release=resolve);throw new TypeError('unknown');});j.activate(alice);await assert.rejects(j.command('alice',path,body));const recovering=j.resume();j.lock();j.activate(bob);j.lock();j.activate(alice);release({status:'not_found'});await assert.rejects(recovering,/身份已切换/);assert.equal(calls,2);assert(j.pending());
});
