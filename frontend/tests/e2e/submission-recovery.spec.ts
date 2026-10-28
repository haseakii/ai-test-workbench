import {test,expect,APIRequestContext} from '@playwright/test';

async function cmd(api:APIRequestContext,subject:string,path:string,body:object){const r=await api.post('/api'+path,{headers:{'X-Test-Subject':subject},data:{...body,idempotencyKey:crypto.randomUUID()}});expect(r.status()).toBe(200);return r.json();}
async function fixtureBudget(api:APIRequestContext){
 const get=async(path:string)=>(await api.get('/api'+path,{headers:{'X-Test-Subject':'alice'}})).json();
 const q=await get('/quota'),today=new Date().toISOString().slice(0,10),accounts=q.accounts.filter((r:any)=>r.period===today);
 const used=Math.max(0,...accounts.map((r:any)=>r.consumed+r.reserved));let limit=accounts.length?Math.min(...accounts.map((r:any)=>r.turnLimit)):100;
 if(limit-used<20){expect(accounts.every((r:any)=>r.reserved===0)&&q.slots.every((r:any)=>r.occupied===0)).toBe(true);const identity=await get('/session');const previous=limit;limit=used+100;
  await cmd(api,'admin','/admin/quotas',{alias:'alice',turnLimit:limit,slotLimit:q.slots[0]?.slotLimit??1,expectedAuthorizationRevision:identity.authorizationRevision});
  const after=await get('/quota');const counters=(rows:any[])=>rows.map(r=>[r.account,r.period,r.consumed,r.reserved]);expect(counters(after.accounts)).toEqual(counters(q.accounts));
  test.info().annotations.push({type:'fixture-quota',description:JSON.stringify({subject:'alice',beforeLimit:previous,afterLimit:limit,countersUnchanged:true})});
 }
 return limit;
}
async function fixture(api:APIRequestContext){
 const turnLimit=await fixtureBudget(api);
 const name='提交恢复-'+crypto.randomUUID().slice(0,8);
 const m=await cmd(api,'admin','/admin/configurations',{kind:'model',name,content:{backend:'persistent_fixture',modelCalls:false}});
 const w=await cmd(api,'admin','/admin/configurations',{kind:'workflow',name,content:{operation:'reconcile_csv_readonly_v1'}});
 const e=await cmd(api,'admin','/admin/configurations',{kind:'environment',name,content:{modelRef:m.id,workflowRef:w.id,timeZone:'UTC',turnLimit}});
 await cmd(api,'admin','/admin/config-publications',{objects:[{id:m.id,expectedRevision:0,dependencyRevisions:{}},{id:w.id,expectedRevision:0,dependencyRevisions:{}},{id:e.id,expectedRevision:0,dependencyRevisions:{[m.id]:0,[w.id]:0}}]});
 await cmd(api,'admin','/admin/grants',{alias:'alice',environmentId:e.id,role:'member'});
 await cmd(api,'admin','/admin/grants',{alias:'reviewer',environmentId:e.id,role:'reviewer'});
 const c=await cmd(api,'alice','/conversations',{environmentId:e.id});const title=await api.patch('/api/conversations/'+c.id+'/title',{headers:{'X-Test-Subject':'alice'},data:{title:name,expectedTitleRevision:1,idempotencyKey:crypto.randomUUID()}});expect(title.status()).toBe(200);return {name,environmentId:e.id,conversationId:c.id};
}

test('已到服务端但回执丢失：刷新后查原回执且消息只保存一次',async({page,request})=>{
 const f=await fixture(request);const sent:any[]=[];let lose=true;
 await page.route('**/api/conversations/'+f.conversationId+'/messages',async route=>{
  sent.push(route.request().postDataJSON());if(lose){lose=false;await route.fetch();await route.abort('failed');}else await route.continue();
 });
 await page.goto('/');await page.getByLabel('测试身份').selectOption('alice');await page.locator('.recent-item').filter({hasText:f.name}).click();await page.getByLabel('问题描述').fill('回执丢失需要保持原消息身份');
 await page.getByRole('button',{name:'保存消息',exact:true}).click();await expect(page.getByRole('alert')).toBeVisible();
 await page.reload();await expect(page.getByRole('button',{name:'核查并继续原提交',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'核查并继续原提交',exact:true}).click();
 await expect(page.locator('.message p').filter({hasText:'回执丢失需要保持原消息身份'})).toBeVisible();
 expect(sent).toHaveLength(1);
 const snapshot=await (await request.get('/api/conversations/'+f.conversationId,{headers:{'X-Test-Subject':'alice'}})).json();expect(snapshot.messages.filter((m:any)=>m.text==='回执丢失需要保持原消息身份')).toHaveLength(1);
});

test('请求未到达：刷新保留原键和消息 ID，查不到回执才原键重试',async({page,request})=>{
 const f=await fixture(request);const sent:any[]=[];const ordering:string[]=[];let lose=true;
 await page.route('**/api/conversations/'+f.conversationId+'/messages',async route=>{sent.push(route.request().postDataJSON());ordering.push('message');if(lose){lose=false;await route.abort('failed');}else await route.continue();});
 await page.route('**/api/command-lookups',async route=>{ordering.push('lookup');await route.continue();});
 await page.goto('/');await page.getByLabel('测试身份').selectOption('alice');await page.locator('.recent-item').filter({hasText:f.name}).click();
 await page.getByLabel('问题描述').fill('请求没有到达服务端');await page.getByRole('button',{name:'保存消息',exact:true}).click();await expect(page.getByRole('alert')).toBeVisible();
 await page.reload();await page.getByRole('button',{name:'核查并继续原提交',exact:true}).click();await expect(page.getByText('请求没有到达服务端',{exact:true})).toBeVisible();
 expect(sent).toHaveLength(2);expect(sent[0]).toEqual(sent[1]);expect(ordering).toEqual(['message','lookup','message']);
 await page.getByLabel('问题描述').fill('请求没有到达服务端');await page.getByRole('button',{name:'保存消息',exact:true}).click();await expect(page.locator('.message')).toHaveCount(2);
 expect(sent[2].idempotencyKey).not.toBe(sent[1].idempotencyKey);expect(sent[2].clientMessageId).not.toBe(sent[1].clientMessageId);
});

test('环境草稿取消不发送，分步保存丢回执后刷新沿用已完成步骤',async({page,request})=>{
 const name='环境保存恢复-'+crypto.randomUUID().slice(0,8);const posted:any[]=[];let lose=true;
 await page.route('**/api/admin/configurations',async route=>{if(route.request().method()!=='POST'){await route.continue();return;}const body=route.request().postDataJSON();posted.push(body);if(lose&&body.name.startsWith(name)){lose=false;await route.fetch();await route.abort('failed');}else await route.continue();});
 await page.goto('/');await page.getByLabel('测试身份').selectOption('admin');await page.getByRole('button',{name:'系统设置',exact:true}).click();
 await page.getByRole('button',{name:'新建测试环境',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'取消',exact:true}).click();expect(posted).toHaveLength(0);
 await page.getByRole('button',{name:'新建测试环境',exact:true}).click();await page.getByLabel('环境名称').fill(name);await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByRole('alert')).toBeVisible();
 await page.reload();const dialog=page.getByRole('dialog',{name:'新建测试环境'});await expect(dialog).toBeVisible();await dialog.getByRole('button',{name:'核查并继续原提交',exact:true}).click();await dialog.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByRole('dialog',{name:'发布变更集'})).toBeVisible();
 expect(posted.filter(x=>x.name.startsWith(name))).toHaveLength(3);expect(new Set(posted.map(x=>x.idempotencyKey)).size).toBe(3);
 const all=await (await request.get('/api/admin/configurations',{headers:{'X-Test-Subject':'admin'}})).json();expect(all.filter((x:any)=>x.name.startsWith(name))).toHaveLength(3);
});

test('身份与环境切换不自动重发，清除旧正文缓存且返回后仅核查原回执',async({page,request})=>{
 const f=await fixture(request);let posts=0;let lookups=0;
 await page.route('**/api/conversations/'+f.conversationId+'/messages',async route=>{posts++;await route.fetch();await route.abort('failed');});
 await page.route('**/api/command-lookups',async route=>{lookups++;await route.continue();});
 await page.goto('/');await page.getByLabel('测试身份').selectOption('alice');await page.locator('.recent-item').filter({hasText:f.name}).click();
 const secret='不得泄漏到其他身份的草稿-'+crypto.randomUUID();await page.getByLabel('问题描述').fill(secret);await page.getByRole('button',{name:'保存消息',exact:true}).click();await expect(page.getByRole('alert')).toBeVisible();
 await page.getByLabel('所属环境').selectOption(f.environmentId);expect(posts).toBe(1);expect(lookups).toBe(0);
 await page.getByLabel('测试身份').selectOption('bob');await expect(page.getByRole('region',{name:'待核查提交'})).toHaveCount(0);await expect(page.getByText(secret,{exact:false})).toHaveCount(0);
 expect(await page.evaluate(value=>JSON.stringify({...sessionStorage,...localStorage}).includes(value),secret)).toBe(false);expect(posts).toBe(1);expect(lookups).toBe(0);
 await page.getByLabel('测试身份').selectOption('alice');await page.getByRole('button',{name:'核查并继续原提交',exact:true}).click();await expect(page.getByText(secret,{exact:true})).toBeVisible();expect(posts).toBe(1);expect(lookups).toBe(1);
});

test('两个标签重命名冲突保留输入，明确载入最新基础后再保存',async({page,context,request})=>{
 const f=await fixture(request),other=await context.newPage();
 try{
  for(const tab of [page,other]){await tab.goto('/');await tab.getByLabel('测试身份').selectOption('alice');await tab.locator('.recent-item').filter({hasText:f.name}).click();await tab.getByRole('button',{name:'重命名',exact:true}).click();}
  await page.getByLabel('问题名称',{exact:true}).fill(f.name+' first');await other.getByLabel('问题名称',{exact:true}).fill(f.name+' second');
  await page.getByRole('button',{name:'保存名称',exact:true}).click();await expect(page.getByRole('dialog',{name:'重命名问题'})).toBeHidden();
  await other.getByRole('button',{name:'保存名称',exact:true}).click();await expect(other.getByRole('alert')).toContainText('名称已被更新');await expect(other.getByLabel('问题名称',{exact:true})).toHaveValue(f.name+' second');
  await other.getByRole('button',{name:'载入最新名称并保留输入'}).click();await other.getByRole('button',{name:'保存名称',exact:true}).click();await expect(other.getByRole('dialog')).toHaveCount(0);
  const snapshot=await (await request.get('/api/conversations/'+f.conversationId,{headers:{'X-Test-Subject':'alice'}})).json();expect(snapshot.title).toBe(f.name+' second');expect(snapshot.titleRevision).toBe(4);
 }finally{await other.close();}
});

test('两个标签保存配置以草稿哈希 CAS 防覆盖，冲突后保留输入并重新比较',async({page,context,request})=>{
 const f=await fixture(request),other=await context.newPage();
 try{
  for(const tab of [page,other]){await tab.goto('/');await tab.getByLabel('测试身份').selectOption('admin');await tab.getByRole('button',{name:'系统设置',exact:true}).click();await tab.locator('tr').filter({hasText:f.name}).getByRole('button',{name:'编辑草稿'}).click();}
  const first=JSON.parse(await page.getByLabel('环境配置').inputValue()),second={...first,turnLimit:98};first.turnLimit=99;
  await page.getByLabel('环境配置').fill(JSON.stringify(first));await other.getByLabel('环境配置').fill(JSON.stringify(second));await page.getByRole('button',{name:'保存环境草稿'}).click();await expect(page.getByRole('dialog')).toHaveCount(0);
  await other.getByRole('button',{name:'保存环境草稿'}).click();await expect(other.getByRole('alert')).toContainText('记录已更新');await expect(other.getByLabel('环境配置')).toHaveValue(JSON.stringify(second));
  const rows=await (await request.get('/api/admin/configurations',{headers:{'X-Test-Subject':'admin'}})).json();expect(rows.find((r:any)=>r.id===f.environmentId).draft.turnLimit).toBe(99);
  await other.getByRole('button',{name:'载入最新基础并保留输入'}).click();await other.getByRole('button',{name:'保存环境草稿'}).click();await expect(other.getByRole('dialog')).toHaveCount(0);
 }finally{await other.close();}
});

test('后台刷新晚返回不会覆盖管理员刚选择的授权环境',async({page,request})=>{
 const f=await fixture(request);let armed=false,paused=false;let release!:()=>void;const gate=new Promise<void>(r=>release=r);
 await page.route('**/api/admin/configurations',async route=>{if(armed&&route.request().method()==='GET'){armed=false;const response=await route.fetch();paused=true;await gate;await route.fulfill({response});}else await route.continue();});
 try{
  await page.goto('/');await page.getByLabel('测试身份').selectOption('admin');await page.getByRole('button',{name:'成员与权限',exact:true}).click();await expect(page.getByLabel('授权环境').locator('option[value="'+f.environmentId+'"]')).toHaveCount(1);
  armed=true;await page.getByRole('button',{name:'成员与权限',exact:true}).click();await expect.poll(()=>paused).toBe(true);
  await page.getByLabel('授权环境').selectOption(f.environmentId);const finished=page.waitForEvent('requestfinished',{predicate:r=>r.method()==='GET'&&r.url().endsWith('/api/admin/configurations')});release();await finished;
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));await expect(page.getByLabel('授权环境')).toHaveValue(f.environmentId);
 }finally{release();}
});

test('两个标签审批冲突可载入已批准状态，保留一个 Binding 和一次接纳',async({page,context,request})=>{
 const f=await fixture(request);const snapshot=await (await request.get('/api/conversations/'+f.conversationId,{headers:{'X-Test-Subject':'alice'}})).json();
 await cmd(request,'alice','/conversations/'+f.conversationId+'/messages',{clientMessageId:crypto.randomUUID(),text:f.name,expectedRevision:snapshot.revision});
 const csv=Buffer.from('order_id,amount,currency,status,occurred_at\r\nO1,1.00,EUR,completed,2026-10-25T12:00:00Z\r\n');const files:any[]=[];
 for(const name of ['detail.csv','report.csv']){const r=await request.post('/api/conversations/'+f.conversationId+'/attachments',{headers:{'X-Test-Subject':'alice'},multipart:{file:{name,mimeType:'text/csv',buffer:csv},idempotencyKey:crypto.randomUUID()}});expect(r.status()).toBe(200);files.push(await r.json());}
 await cmd(request,'alice','/conversations/'+f.conversationId+'/prepare',{scope:{detailAttachmentId:files[0].id,reportAttachmentId:files[1].id,from:'2026-10-25T00:00:00Z',to:'2026-10-26T00:00:00Z',timeZone:'UTC',currency:'EUR',status:'completed',expectedInputRevision:3,validForSeconds:600}});
 const total=async()=>{const q=await (await request.get('/api/quota',{headers:{'X-Test-Subject':'alice'}})).json();return q.accounts.filter((a:any)=>a.account==='total').reduce((n:number,a:any)=>n+a.consumed,0);};const before=await total();
 const other=await context.newPage();try{
  for(const tab of [page,other]){await tab.goto('/');await tab.getByLabel('测试身份').selectOption('reviewer');await tab.getByRole('button',{name:'审批中心',exact:true}).click();await tab.locator('tr').filter({hasText:f.name}).getByRole('button',{name:'查看计划'}).click();await tab.getByLabel('审批意见').fill('已核对固定计划和合成材料');await tab.getByLabel('我已核对以上固定范围和材料').check();}
  await page.getByRole('button',{name:'批准本次计划'}).click();await expect(page.getByRole('dialog')).toHaveCount(0);
  await other.getByRole('button',{name:'拒绝',exact:true}).click();await expect(other.getByRole('alert')).toContainText('计划已失效');await expect(other.getByLabel('审批意见')).toHaveValue('已核对固定计划和合成材料');
  await other.getByRole('button',{name:'载入当前计划状态'}).click();await expect(other.getByRole('dialog')).toContainText('已批准');await expect(other.getByRole('button',{name:'批准本次计划'})).toHaveCount(0);
  const state=await (await request.get('/api/conversations/'+f.conversationId,{headers:{'X-Test-Subject':'alice'}})).json();expect(state.executions).toHaveLength(1);expect(state.approvals).toHaveLength(1);await expect.poll(async()=>{const r=await request.get('/api/conversations/'+f.conversationId,{headers:{'X-Test-Subject':'alice'}});return (await r.json()).state;}).toBe('awaiting_verification');const events=await (await request.get('/api/conversations/'+f.conversationId+'/events',{headers:{'X-Test-Subject':'alice'}})).json();expect(events.events.filter((e:any)=>e.type==='execution.accepted')).toHaveLength(1);expect(await total()-before).toBe(1);
 }finally{await other.close();}
});
