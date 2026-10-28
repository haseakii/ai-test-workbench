import {test,expect,APIRequestContext,Page,Download} from '@playwright/test';
import {createHash} from 'node:crypto';
import fs from 'node:fs/promises';
import canonicalize from 'canonicalize';

type RecordValue=Record<string,any>;
const headers=(subject:string)=>({'X-Test-Subject':subject});
const sha=(value:unknown)=>createHash('sha256').update(canonicalize(value)!).digest('hex');
async function get(api:APIRequestContext,subject:string,path:string){const r=await api.get('/api'+path,{headers:headers(subject)});expect(r.status()).toBe(200);return r.json();}
async function command(api:APIRequestContext,subject:string,path:string,body:object){const r=await api.post('/api'+path,{headers:headers(subject),data:{...body,idempotencyKey:crypto.randomUUID()}});expect(r.status()).toBe(200);return r.json();}
async function budget(api:APIRequestContext){
 const current=await get(api,'alice','/quota'),today=new Date().toISOString().slice(0,10),accounts=current.accounts.filter((r:RecordValue)=>r.period===today);
 const used=Math.max(0,...accounts.map((r:RecordValue)=>r.consumed+r.reserved));let limit=accounts.length?Math.min(...accounts.map((r:RecordValue)=>r.turnLimit)):100;
 if(limit-used<20){
  expect(accounts.every((r:RecordValue)=>r.reserved===0)&&current.slots.every((r:RecordValue)=>r.occupied===0)).toBe(true);
  const identity=await get(api,'alice','/session'),before=limit;limit=used+100;
  await command(api,'admin','/admin/quotas',{alias:'alice',turnLimit:limit,slotLimit:current.slots[0]?.slotLimit??1,expectedAuthorizationRevision:identity.authorizationRevision});
  const after=await get(api,'alice','/quota');const counters=(rows:RecordValue[])=>rows.map(r=>[r.account,r.period,r.consumed,r.reserved]);expect(counters(after.accounts)).toEqual(counters(current.accounts));
  test.info().annotations.push({type:'fixture-quota',description:JSON.stringify({subject:'alice',beforeLimit:before,afterLimit:limit,countersUnchanged:true})});
 }
 return limit;
}
async function fixture(api:APIRequestContext,mode='normal'){
 const turnLimit=await budget(api),name='接管导出-'+crypto.randomUUID().slice(0,8),body='独立合成正文不得写入接管包-'+crypto.randomUUID();
 const model=await command(api,'admin','/admin/configurations',{kind:'model',name,content:{backend:'persistent_fixture',modelCalls:false}});
 const workflow=await command(api,'admin','/admin/configurations',{kind:'workflow',name,content:{operation:'reconcile_csv_readonly_v1'}});
 const environment=await command(api,'admin','/admin/configurations',{kind:'environment',name,content:{modelRef:model.id,workflowRef:workflow.id,timeZone:'UTC',turnLimit,testMode:mode}});
 await command(api,'admin','/admin/config-publications',{objects:[{id:model.id,expectedRevision:0,dependencyRevisions:{}},{id:workflow.id,expectedRevision:0,dependencyRevisions:{}},{id:environment.id,expectedRevision:0,dependencyRevisions:{[model.id]:0,[workflow.id]:0}}]});
 for(const [alias,role] of [['alice','member'],['reviewer','reviewer']])await command(api,'admin','/admin/grants',{alias,environmentId:environment.id,role});
 const conversation=await command(api,'alice','/conversations',{environmentId:environment.id});
 const rename=await api.patch('/api/conversations/'+conversation.id+'/title',{headers:headers('alice'),data:{title:name,expectedTitleRevision:1,idempotencyKey:crypto.randomUUID()}});expect(rename.status()).toBe(200);
 await command(api,'alice','/conversations/'+conversation.id+'/messages',{clientMessageId:crypto.randomUUID(),text:body,expectedRevision:1});
 const attachments:RecordValue[]=[];
 for(const [fileName,amount] of [['detail.csv','1.00'],['report.csv','2.00']]){
  const csv=Buffer.from('order_id,amount,currency,status,occurred_at\r\nO1,'+amount+',EUR,completed,2026-10-25T12:00:00Z\r\n');
  const r=await api.post('/api/conversations/'+conversation.id+'/attachments',{headers:headers('alice'),multipart:{file:{name:fileName,mimeType:'text/csv',buffer:csv},idempotencyKey:crypto.randomUUID()}});expect(r.status()).toBe(200);attachments.push(await r.json());
 }
 const plan=await command(api,'alice','/conversations/'+conversation.id+'/prepare',{scope:{detailAttachmentId:attachments[0].id,reportAttachmentId:attachments[1].id,from:'2026-10-25T00:00:00Z',to:'2026-10-26T00:00:00Z',timeZone:'UTC',currency:'EUR',status:'completed',expectedInputRevision:3,validForSeconds:600}});
 await command(api,'reviewer','/approvals/'+plan.approvalId+'/decision',{approve:true,operationHash:plan.operationHash,expectedRevision:plan.revision,reason:'已核对合成 CSV 和本次固定检查范围'});
 if(mode==='normal')await expect.poll(async()=>{const s=await get(api,'alice','/conversations/'+conversation.id);return s.state;},{timeout:15000}).toBe('awaiting_verification');
 else await expect.poll(async()=>{const s=await get(api,'alice','/conversations/'+conversation.id);return s.executions.find((e:RecordValue)=>e.id===plan.bindingId)?.state;},{timeout:15000}).toBe('accepted');
 return {name,body,environmentId:environment.id,conversationId:conversation.id,attachments,plan};
}
type Fixture=Awaited<ReturnType<typeof fixture>>;
async function open(page:Page,f:Fixture){await page.goto('/');await page.getByLabel('测试身份').selectOption('alice');await page.locator('.recent-item').filter({hasText:f.name}).click();await expect(page.getByRole('button',{name:'导出接管包',exact:true})).toBeVisible();}
async function contents(download:Download){expect(await download.failure()).toBeNull();const file=await download.path();expect(file).not.toBeNull();return JSON.parse(await fs.readFile(file!,'utf8'));}
function noSensitiveContent(value:RecordValue,body:string){
 const json=JSON.stringify(value);expect(json).not.toContain(body);expect(json).not.toMatch(/\/workspace\/|jdbc:|https?:\/\/|BEGIN (?:RSA |EC )?PRIVATE KEY/);
 const forbidden=new Set(['text','textContent','text_content','messages','password','secret','token','accessToken','blobRoot','sourcePath','jdbcUrl','connectionString','rawBytes']);
 function visit(node:unknown){if(Array.isArray(node)){node.forEach(visit);return;}if(node&&typeof node==='object')for(const [key,field] of Object.entries(node)){expect(forbidden.has(key),key).toBe(false);visit(field);}}
 visit(value);
}
async function noLateDownload(page:Page,downloads:Download[]){
 // The held response headers arrived; allow the hash/identity continuation to settle.
 // An absence check needs a bounded observation window and never drives execution.
 const late=await page.waitForEvent('download',{timeout:800}).then(()=>true,()=>false);expect(late).toBe(false);expect(downloads).toHaveLength(0);
}

test('已批准 CSV 闭环和用户确认后导出可复算且不含正文的接管包',async({page,request})=>{
 const f=await fixture(request);await open(page,f);await page.getByLabel('验证说明').fill('已核对一条差异，确认本合成问题解决');await page.getByRole('button',{name:'确认已解决',exact:true}).click();await expect(page.locator('.chat-head .badge')).toHaveText('已解决');
 let serverEnvelope:RecordValue|undefined;
 await page.route('**/api/conversations/'+f.conversationId+'/handover',async route=>{const r=await route.fetch();expect(r.status()).toBe(200);serverEnvelope=await r.json();await route.fulfill({response:r});});
 const received=page.waitForEvent('download');await page.getByRole('button',{name:'导出接管包',exact:true}).click();const download=await received;expect(download.suggestedFilename()).toBe('handover-'+f.conversationId+'.json');const envelope=await contents(download);
 expect(envelope).toEqual(serverEnvelope);expect(Object.keys(envelope).sort()).toEqual(['package','packageSha256']);expect(envelope.packageSha256).toBe(sha(envelope.package));
 const pkg=envelope.package;expect(pkg.schemaVersion).toBe('fixed-csv-handover-v1');expect(pkg.conversation.conversationId).toBe(f.conversationId);expect(pkg.conversation.environmentId).toBe(f.environmentId);expect(pkg.conversation.state).toBe('resolved');expect(pkg.conversation.inputRevision).toBe(3);
 expect(pkg.context.reference).toBe(f.plan.plan.contextManifestRef);expect(pkg.context.sha256).toBe(f.plan.plan.contextManifestHash);expect(pkg.context.state).toBe('active');expect(pkg.context.purpose).toBe('investigation');
 expect(pkg.operation.operationHash).toBe(f.plan.operationHash);expect(pkg.operation.approvalId).toBe(f.plan.approvalId);expect(pkg.operation.authority.kind).toBe('human');expect(pkg.operation.operationHash).not.toBe(sha(pkg.operation.scope));expect(envelope.packageSha256).not.toBe(pkg.operation.operationHash);
 expect(pkg.inputs.map((i:RecordValue)=>i.sha256).sort()).toEqual(f.attachments.map(a=>a.sha256).sort());expect(pkg.artifacts).toHaveLength(1);expect(pkg.artifacts[0].result.totalDifference).toBe('1.00');expect(pkg.verifications).toHaveLength(1);expect(pkg.verifications[0].resolved).toBe(true);
 expect(pkg.executions).toHaveLength(1);expect(pkg.executions[0].ownership).toBe('inactive');expect(pkg.executions[0].outcome).toBe('succeeded');expect(pkg.executions[0].stopRequested).toBe(false);expect(pkg.executions[0].providerCancelRequested).toBe(false);
 // Natural completion is proved by its durable authoritative terminal snapshot.
 // It must not invent a closeSubmission or cancellation acknowledgement.
 expect(pkg.executions[0].closureProofRef).toBeNull();expect(pkg.executions[0].authoritativeSnapshotHash).toMatch(/^[a-f0-9]{64}$/);expect(pkg.executions[0].snapshotRevision).toBeGreaterThan(0);expect(pkg.executions[0].providerEventWatermark).toBeGreaterThan(0);expect(pkg.executions[0].providerResumeCursor).toEqual(expect.any(String));expect(pkg.executions[0].providerResumeCursor.length).toBeGreaterThan(0);
 expect(pkg.reservations[0].state).toBe('settled');expect(pkg.reservations[0].totalAccountKey).toBe('total');expect(pkg.reservations[0].modelAccountKey).toBe('model:fixture-v1');expect(pkg.responsibility.status).toBe('unassigned');expect(pkg.responsibility.nextSubjectId).toBeNull();noSensitiveContent(envelope,f.body);
 expect(await page.evaluate(body=>JSON.stringify({...sessionStorage,...localStorage}).includes(body),JSON.stringify(envelope))).toBe(false);
});

test('接管包响应被改动而摘要未变时浏览器拒绝下载',async({page,request})=>{
 const f=await fixture(request);await open(page,f);const downloads:Download[]=[];page.on('download',d=>downloads.push(d));
 await page.route('**/api/conversations/'+f.conversationId+'/handover',async route=>{const r=await route.fetch();expect(r.status()).toBe(200);const body=await r.json();body.package.conversation.inputRevision++;await route.fulfill({response:r,json:body});});
 await page.getByRole('button',{name:'导出接管包',exact:true}).click();await expect(page.getByRole('alert')).toBeVisible();await noLateDownload(page,downloads);
});

for(const returnToAlice of [false,true])test(returnToAlice?'接管包晚响应在身份切走再返回后也不能下载旧请求':'接管包晚响应在切换身份后不能替旧主体下载',async({page,request})=>{
 const f=await fixture(request);await open(page,f);const downloads:Download[]=[];page.on('download',d=>downloads.push(d));let release!:()=>void;const gate=new Promise<void>(r=>release=r);let paused=false;
 await page.route('**/api/conversations/'+f.conversationId+'/handover',async route=>{const r=await route.fetch();expect(r.status()).toBe(200);paused=true;await gate;await route.fulfill({response:r});});
 try{
  await page.getByRole('button',{name:'导出接管包',exact:true}).click();await expect.poll(()=>paused).toBe(true);await page.getByLabel('测试身份').selectOption('bob');if(returnToAlice)await page.getByLabel('测试身份').selectOption('alice');
  const response=page.waitForResponse(r=>r.url().endsWith('/conversations/'+f.conversationId+'/handover'));release();expect((await response).status()).toBe(200);await noLateDownload(page,downloads);
  expect(await page.evaluate(body=>JSON.stringify({...sessionStorage,...localStorage}).includes(body),f.body)).toBe(false);
 }finally{release();}
});

test('来源撤权后接管导出重新鉴权并拒绝下载旧派生证据',async({page,request})=>{
 const f=await fixture(request);await open(page,f);const downloads:Download[]=[];page.on('download',d=>downloads.push(d));
 await command(request,'alice','/attachments/'+f.attachments[0].id+'/revoke',{});await expect(page.locator('.file-row').filter({hasText:'detail.csv'})).toHaveCount(0);
 const denied=page.waitForResponse(r=>r.url().endsWith('/conversations/'+f.conversationId+'/handover'));await page.getByRole('button',{name:'导出接管包',exact:true}).click();expect([403,404,409]).toContain((await denied).status());await expect(page.getByRole('alert')).toBeVisible();await noLateDownload(page,downloads);
});

test('同身份已看到来源撤权事件后丢弃先前授权的晚接管响应',async({page,request})=>{
 const f=await fixture(request);await open(page,f);const downloads:Download[]=[];page.on('download',d=>downloads.push(d));let release!:()=>void;const gate=new Promise<void>(r=>release=r);let paused=false;
 await page.route('**/api/conversations/'+f.conversationId+'/handover',async route=>{const r=await route.fetch();expect(r.status()).toBe(200);paused=true;await gate;await route.fulfill({response:r});});
 try{
  await page.getByRole('button',{name:'导出接管包',exact:true}).click();await expect.poll(()=>paused).toBe(true);
  await command(request,'alice','/attachments/'+f.attachments[0].id+'/revoke',{});await expect(page.locator('.file-row').filter({hasText:'detail.csv'})).toHaveCount(0);
  const response=page.waitForResponse(r=>r.url().endsWith('/conversations/'+f.conversationId+'/handover'));release();expect((await response).status()).toBe(200);await noLateDownload(page,downloads);
 }finally{release();}
});

test('未关闭占用导出保持原执行与冻结账本并在明确停止后收口',async({page,request})=>{
 const f=await fixture(request,'hold_after_accept');try{
  await open(page,f);const before=await get(request,'alice','/conversations/'+f.conversationId),quota=await get(request,'alice','/quota');expect(quota.slots[0].occupied).toBe(1);
  const received=page.waitForEvent('download');await page.getByRole('button',{name:'导出接管包',exact:true}).click();const pkg=(await contents(await received)).package;expect(pkg.executions).toHaveLength(1);expect(pkg.executions[0].bindingId).toBe(f.plan.bindingId);expect(pkg.executions[0].closedAt).toBeNull();expect(pkg.executions[0].ownership).toBe('unknown');expect(pkg.executions[0].closureProofRef).toBeNull();expect(pkg.reservations[0].state).toBe('consumed');expect(pkg.unresolved.length).toBeGreaterThan(0);
  const after=await get(request,'alice','/conversations/'+f.conversationId);expect(after.executions).toEqual(before.executions);expect(after.artifacts).toEqual([]);expect(await get(request,'alice','/quota')).toEqual(quota);noSensitiveContent(pkg,f.body);
 }finally{
  await command(request,'alice','/conversations/'+f.conversationId+'/stop',{});await expect.poll(async()=>{const s=await get(request,'alice','/conversations/'+f.conversationId);return s.executions.every((e:RecordValue)=>e.closed_at!==null);},{timeout:15000}).toBe(true);
 }
});


for(const returnToAlice of [false,true])test(returnToAlice?'差异文件晚响应在身份ABA切换后也不能下载':'差异文件晚响应不能跨身份下载',async({page,request})=>{
 const f=await fixture(request);await open(page,f);const snapshot=await get(request,'alice','/conversations/'+f.conversationId);const artifact=snapshot.artifacts[0];const downloads:Download[]=[];page.on('download',d=>downloads.push(d));
 let release!:()=>void;const gate=new Promise<void>(r=>release=r);let paused=false;
 await page.route('**/api/artifacts/'+artifact.id+'/download',async route=>{const response=await route.fetch();expect(response.status()).toBe(200);paused=true;await gate;await route.fulfill({response});});
 try{
  await page.getByRole('button',{name:'下载差异 CSV',exact:true}).click();await expect.poll(()=>paused).toBe(true);
  await page.getByLabel('测试身份').selectOption('bob');if(returnToAlice)await page.getByLabel('测试身份').selectOption('alice');
  const finished=page.waitForEvent('requestfinished',{predicate:r=>r.url().endsWith('/artifacts/'+artifact.id+'/download')});release();await finished;await noLateDownload(page,downloads);
 }finally{release();}
});


test('差异文件晚响应在界面已观察来源撤权后也不得下载',async({page,request})=>{
 const f=await fixture(request);await open(page,f);const snapshot=await get(request,'alice','/conversations/'+f.conversationId);const artifact=snapshot.artifacts[0];const downloads:Download[]=[];page.on('download',d=>downloads.push(d));
 let release!:()=>void;const gate=new Promise<void>(r=>release=r);let paused=false;
 await page.route('**/api/artifacts/'+artifact.id+'/download',async route=>{const response=await route.fetch();expect(response.status()).toBe(200);paused=true;await gate;await route.fulfill({response});});
 try{
  await page.getByRole('button',{name:'下载差异 CSV',exact:true}).click();await expect.poll(()=>paused).toBe(true);
  await command(request,'alice','/attachments/'+f.attachments[0].id+'/revoke',{});await expect(page.locator('.file-row').filter({hasText:'detail.csv'})).toHaveCount(0);
  const response=page.waitForResponse(r=>r.url().endsWith('/artifacts/'+artifact.id+'/download'));release();expect((await response).status()).toBe(200);await noLateDownload(page,downloads);
 }finally{release();}
});
