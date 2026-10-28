import {test,expect,APIRequestContext} from '@playwright/test';

async function get(api:APIRequestContext,subject:string,path:string){const response=await api.get('/api'+path,{headers:{'X-Test-Subject':subject}});expect(response.status()).toBe(200);return response.json();}
async function command(api:APIRequestContext,subject:string,path:string,body:object){const response=await api.post('/api'+path,{headers:{'X-Test-Subject':subject},data:{...body,idempotencyKey:crypto.randomUUID()}});expect(response.status()).toBe(200);return response.json();}
async function fixture(api:APIRequestContext){
 const name='UI权限恢复-'+crypto.randomUUID().slice(0,8),title=name+' 的问题',body=name+' 的独立正文材料';
 const model=await command(api,'admin','/admin/configurations',{kind:'model',name,content:{backend:'persistent_fixture',modelCalls:false}});
 const workflow=await command(api,'admin','/admin/configurations',{kind:'workflow',name,content:{operation:'reconcile_csv_readonly_v1'}});
 const environment=await command(api,'admin','/admin/configurations',{kind:'environment',name,content:{modelRef:model.id,workflowRef:workflow.id,timeZone:'UTC',turnLimit:100}});
 await command(api,'admin','/admin/config-publications',{objects:[{id:model.id,expectedRevision:0,dependencyRevisions:{}},{id:workflow.id,expectedRevision:0,dependencyRevisions:{}},{id:environment.id,expectedRevision:0,dependencyRevisions:{[model.id]:0,[workflow.id]:0}}]});
 for(const [alias,role] of [['alice','member'],['reviewer','reviewer']])await command(api,'admin','/admin/grants',{alias,environmentId:environment.id,role});
 const conversation=await command(api,'alice','/conversations',{environmentId:environment.id});
 await command(api,'alice','/conversations/'+conversation.id+'/messages',{clientMessageId:crypto.randomUUID(),text:title+'\n'+body,expectedRevision:1});
 return {name,title,body,environmentId:environment.id,conversationId:conversation.id};
}

test('正式界面正文资格、用途租约、撤权后清除正文',async({page,request})=>{
 const f=await fixture(request);await page.goto('/');await page.getByLabel('测试身份').selectOption('admin');
 await page.getByRole('button',{name:'成员与权限',exact:true}).click();
 await page.getByLabel('授权环境').selectOption(f.environmentId);
 await page.getByLabel('正文审阅负责人').selectOption('reviewer');
 await page.getByLabel('资格调整原因').fill('指定负责人复核合成问题处理正文');
 await page.getByRole('button',{name:'授予正文资格',exact:true}).click();
 await expect(page.getByRole('status')).toContainText('正文审阅资格已授予');
 await page.getByLabel('测试身份').selectOption('reviewer');await page.getByRole('button',{name:'用户记录',exact:true}).click();
 await page.getByLabel('筛选问题标题').fill(f.title);await page.getByRole('button',{name:'筛选列表',exact:true}).click();
 await page.locator('tr').filter({hasText:f.title}).getByRole('button',{name:'申请正文租约'}).click();
 const dialog=page.getByRole('dialog',{name:'正文审阅'});await expect(dialog.getByRole('button',{name:'申请租约并读取'})).toBeDisabled();
 await dialog.getByLabel('正文审阅用途').fill('核查此合成问题的处理记录及条件');
 await dialog.getByRole('button',{name:'申请租约并读取'}).click();await expect(dialog.locator('.leased-messages')).toContainText(f.body);
 const reviewer=await get(request,'reviewer','/session');
 await command(request,'admin','/admin/content-reviewers',{alias:'reviewer',environmentId:f.environmentId,enabled:false,expectedAuthorizationRevision:reviewer.authorizationRevision,reason:'复核完毕撤回正文资格并使租约失效'});
 await expect(dialog).toBeHidden({timeout:6000});await expect(page.getByText(f.body,{exact:false})).toHaveCount(0);
 const denied=await request.get('/api/admin/records/'+f.conversationId+'/content',{headers:{'X-Test-Subject':'reviewer'}});expect(denied.status()).toBe(403);
 expect((await request.get('/api/conversations/'+f.conversationId+'/events',{headers:{'X-Test-Subject':'reviewer'}})).status()).toBe(404);
});

test('正式对话在事件连接断开后恢复期间新增的输入',async({page,context,request})=>{
 const f=await fixture(request);let connections=0;page.on('request',r=>{if(r.url().includes('/conversations/'+f.conversationId+'/stream'))connections++;});
 await page.goto('/');await page.getByLabel('测试身份').selectOption('alice');
 await page.locator('.recent-item').filter({hasText:f.name}).click();await expect(page.locator('.message')).toContainText(f.body);
 await expect.poll(()=>connections).toBeGreaterThan(0);
 await context.setOffline(true);
 try{
  const snapshot=await get(request,'alice','/conversations/'+f.conversationId);
  await command(request,'alice','/conversations/'+f.conversationId+'/messages',{clientMessageId:crypto.randomUUID(),text:'连接中断期间已持久保存的新材料',expectedRevision:snapshot.revision});
  await page.waitForTimeout(600);
 }finally{await context.setOffline(false);}
 await expect(page.locator('.message').last()).toContainText('连接中断期间已持久保存的新材料',{timeout:10000});
 await expect.poll(()=>connections).toBeGreaterThan(1);
});
