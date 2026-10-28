import {test,expect,APIRequestContext} from '@playwright/test';

async function fixture(request:APIRequestContext){
 const post=async(actor:string,path:string,body:object)=>{const response=await request.post('/api'+path,{headers:{'X-Test-Subject':actor},data:{...body,idempotencyKey:crypto.randomUUID()}});expect(response.ok()).toBeTruthy();return response.json();};
 const name='context-'+crypto.randomUUID();
 const m=await post('admin','/admin/configurations',{kind:'model',name,content:{backend:'persistent_fixture',modelCalls:false}});
 const w=await post('admin','/admin/configurations',{kind:'workflow',name,content:{operation:'reconcile_csv_readonly_v1'}});
 const e=await post('admin','/admin/configurations',{kind:'environment',name,content:{modelRef:m.id,workflowRef:w.id,timeZone:'UTC',turnLimit:100}});
 await post('admin','/admin/config-publications',{objects:[{id:m.id,expectedRevision:0,dependencyRevisions:{}},{id:w.id,expectedRevision:0,dependencyRevisions:{}},{id:e.id,expectedRevision:0,dependencyRevisions:{[m.id]:0,[w.id]:0}}]});
 await post('admin','/admin/grants',{alias:'alice',environmentId:e.id,role:'member'});
 const c=await post('alice','/conversations',{environmentId:e.id});return {name,id:c.id};
}
test('缺少材料时独立理解记录补充问题且不生成执行',async({page,request})=>{
 const f=await fixture(request);await page.goto('/');await page.getByLabel('测试身份').selectOption('alice');
 // Choose the fixture directly through the same persisted owner view used after refresh.
 const s=await (await request.get('/api/session',{headers:{'X-Test-Subject':'alice'}})).json();
 await page.evaluate(({subjectId,selected})=>sessionStorage.setItem('wb-view:'+subjectId,JSON.stringify({subject:'alice',selected,text:''})),{subjectId:s.subjectId,selected:f.id});await page.reload();
 await page.getByRole('button',{name:'准备检查',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'确认检查范围'});await expect(dialog).toContainText('请选择明细 CSV 材料');await expect(dialog).toContainText('没有调用模型');await expect(dialog.getByRole('button',{name:'生成固定计划',exact:true})).toBeDisabled();
 const snapshot=await (await request.get('/api/conversations/'+f.id,{headers:{'X-Test-Subject':'alice'}})).json();expect(snapshot.approvals).toHaveLength(0);expect(snapshot.executions).toHaveLength(0);
});
test('理解回执丢失后用原键恢复并显示同一结果',async({page,request})=>{
 const f=await fixture(request);await page.goto('/');await page.getByLabel('测试身份').selectOption('alice');
 const s=await (await request.get('/api/session',{headers:{'X-Test-Subject':'alice'}})).json();await page.evaluate(({subjectId,selected})=>sessionStorage.setItem('wb-view:'+subjectId,JSON.stringify({subject:'alice',selected,text:''})),{subjectId:s.subjectId,selected:f.id});await page.reload();
 let posts=0;await page.route('**/api/conversations/'+f.id+'/understanding',async route=>{posts++;await route.fetch();await route.abort('failed');});
 await page.getByRole('button',{name:'准备检查',exact:true}).click();await expect(page.getByRole('button',{name:'核查并继续原提交',exact:true})).toBeVisible();
 await page.unroute('**/api/conversations/'+f.id+'/understanding');await page.reload();await page.getByRole('button',{name:'核查并继续原提交',exact:true}).click();
 await expect(page.getByRole('dialog',{name:'确认检查范围'})).toContainText('请选择明细 CSV 材料');expect(posts).toBe(1);
});
