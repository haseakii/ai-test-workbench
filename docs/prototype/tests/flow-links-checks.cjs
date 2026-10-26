// User journey and published workflow/resource links. Entirely synthetic; no real capabilities asserted.
const assert=require('node:assert/strict');const path=require('node:path');const {pathToFileURL}=require('node:url');
module.exports=async({page,check,nav,out})=>{
 const af=n=>page.locator('[data-field="'+n+'"]'),sf=n=>page.locator('[data-service-field="'+n+'"]');
 const admin=n=>page.locator('[data-admin="'+n+'"]').click(),ops=n=>page.locator('[data-ops="'+n+'"]').click();
 const fresh=async(route='chat')=>{await nav(route);await page.reload();await page.waitForSelector(route==='chat'?'#chat-root h1':'#configuration-root h1');};
 const send=async text=>{await page.locator('#chat-input').fill(text);await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});};
 const inspect=()=>page.evaluate(()=>ChatPrototype.inspect());
 const apply=async()=>{await admin('publish-set');await page.locator('#admin-dialog [data-config-entry]').evaluateAll(xs=>xs.forEach(x=>x.checked=true));await admin('confirm');};
 await fresh();
 await check('user sees configured check scope and question status without technical execution fields',async()=>{
  assert((await page.locator('.chat-check-scope summary').innerText()).includes('报表差异核对'));assert((await page.locator('.chat-check-scope summary').innerText()).includes('接口异常排查'));assert.equal(await page.locator('#chat-system').inputValue(),'订单系统');assert.equal(await page.locator('[data-chat=allow]').count(),0);await send('请核对报表和订单明细');assert((await page.locator('.chat-problem-heading').innerText()).includes('待补充'));await send('昨天全天，东八区，已完成订单');const s=await inspect();assert.equal(s.preparation.flowDecision.workflowId,'report');assert(s.preparation.flowDecision.configured);assert.equal(s.preparation.flowDecision.realExecutionAllowed,false);assert.equal(s.preparation.flowDecision.realAdmission,'unknown');assert.equal(s.preparation.codeSnapshot,null);assert.equal(s.preparation.actualModelId,null);assert.deepEqual(s.preparation.flowDecision.toolBindings.map(x=>x.name),['report.read','detail.read']);assert((await page.locator('.inline-permission').innerText()).includes('报表差异核对'));await page.screenshot({path:path.join(out,'screenshots/user-journey.png'),fullPage:true});
 });
 await check('workflow display rename preserves the pending confirmation and next round adopts the new name',async()=>{
  const before=await inspect();await nav('admin-workflows');assert.equal(await page.locator('.workflow-table tbody tr').count(),3);await page.locator('[data-record=report]').click();await af('name').fill('订单报表核对');assert.equal(await page.evaluate(()=>ConfigurationPrototype.current().workflows[0].name),'报表差异核对');await apply();await nav('chat');assert.equal(await page.locator('[data-chat=allow]').count(),1);assert.deepEqual((await inspect()).preparation.flowDecision,before.preparation.flowDecision);await send('按原时间范围重新核对');const next=await inspect();assert.equal(next.preparation.flowDecision.workflowName,'订单报表核对');assert(next.preparation.flowDecision.workflowRevision>before.preparation.flowDecision.workflowRevision);assert.equal(next.executions?.length||0,0);
 });
 await fresh();
 await check('environment workflow restrictions remove quick entries and block typed requests',async()=>{
  await nav('admin-environments');await page.locator('[data-grant=workflowIds][value=interface]').uncheck();await apply();await nav('chat');assert(!(await page.locator('.chat-check-scope summary').innerText()).includes('接口异常'));assert.equal(await page.locator('[data-chat=prompt]').filter({hasText:'接口'}).count(),0);await send('排查订单接口超时');await send('昨天 10 点，请求号 demo-1');assert.equal((await inspect()).phase,'blocked');assert.equal((await inspect()).binding,null);assert((await inspect()).preparation.flowDecision.reasons.some(x=>x.includes('未开放')));
 });
 await fresh();
 await check('role workflow permissions limit checks even inside an authorized environment',async()=>{
  await nav('admin-permissions');await page.locator('[data-record=tester]').click();await page.locator('[data-grant=workflowIds][value=report]').uncheck();await apply();await nav('chat');await send('检查报表差异');await send('昨天全天');assert.equal((await inspect()).phase,'blocked');assert((await inspect()).preparation.flowDecision.reasons.some(x=>x.includes('角色')));assert.equal((await inspect()).binding,null);
 });
 await fresh();
 await check('missing environment MCP prevents the related workflow while other checks remain configured',async()=>{
  await nav('admin-environments');await page.locator('[data-grant=mcpIds][value=demo-log-mcp]').uncheck();await apply();await nav('chat');const scope=await page.locator('.chat-check-scope summary').innerText();assert(scope.includes('报表差异'));assert(!scope.includes('日志分析'));await send('分析错误日志');await send('昨天全天，请求号 demo');assert.equal((await inspect()).binding,null);assert((await inspect()).preparation.flowDecision.reasons.some(x=>x.includes('logs.search')));
 });
 await fresh();
 await check('qualified tool identity prevents another MCP with the same tool name from substituting silently',async()=>{
  await nav('admin-resources');await page.locator('[data-resource-tab=mcps]').click();await page.locator('[data-record=demo-log-mcp]').click();await af('tools').fill('logs.summary');await page.locator('[data-admin=review-mcp]').click();await page.locator('[data-record=demo-data-mcp]').click();await af('tools').fill('report.read, detail.read, logs.search');await page.locator('[data-admin=review-mcp]').click();await apply();await nav('chat');await send('分析日志');await send('昨天全天');assert.equal((await inspect()).binding,null);assert((await inspect()).preparation.flowDecision.reasons.some(x=>x.includes('logs.search')));
 });
 await fresh();
 await check('workflow Skill must be explicitly bound to the chosen environment',async()=>{
  await nav('admin-environments');await page.locator('[data-grant=skillIds][value=demo-report-check]').uncheck();await apply();await nav('chat');await send('检查报表差异');await send('昨天全天');assert.equal((await inspect()).phase,'blocked');assert((await inspect()).preparation.flowDecision.reasons.some(x=>x.includes('Skill')));assert.equal((await inspect()).binding,null);
 });
 await fresh();
 await check('a workflow requiring code cannot start without an environment code project',async()=>{
  await nav('admin-workflows');await page.locator('[data-record=report]').click();await af('readCode').check();await nav('admin-environments');await page.locator('[data-grant=projectIds][value=demo-orders-code]').uncheck();await apply();await nav('chat');await send('核对报表和明细');await send('昨天');assert.equal((await inspect()).binding,null);assert((await inspect()).preparation.flowDecision.reasons.some(x=>x.includes('代码项目')));
 });
 await fresh();
 await check('unclear goals attachment analysis and writes do not fall through to a default check',async()=>{
  for(const text of ['帮我安排出差','请读取截图并分析图片','请直接修改数据并写入']){
   await page.locator('[data-chat=new]:visible').first().click();await send(text);await send(text);const s=await inspect();assert.equal(s.binding,null);assert.equal(s.latestResultRevision,0);assert.equal(s.preparation.flowDecision.configured,false);assert.equal(await page.locator('[data-chat=allow]').count(),0);
  }
 });
 await fresh();
 await check('a new check goal produces a new frozen workflow and only its required tools',async()=>{
  await send('核对报表');await send('昨天全天');const first=(await inspect()).preparation;await send('改为分析错误日志');const next=(await inspect()).preparation;assert.equal(next.flowDecision.workflowId,'logs');assert.deepEqual(next.flowDecision.toolBindings.map(x=>x.name),['logs.search']);assert.equal(first.flowDecision.workflowId,'report');assert.equal(await page.locator('[data-chat=allow]').count(),1);assert.equal((await inspect()).binding,null);
 });
 await fresh('admin-executions');
 await check('management uses the historical task list pattern with representative isolated examples',async()=>{
  assert.equal(await page.locator('.management-topbar').count(),1);assert.equal(await page.locator('[data-ops=select]').count(),8);assert.equal(await page.locator('#configuration-root h1').innerText(),'任务中心');await page.screenshot({path:path.join(out,'screenshots/task-center.png'),fullPage:true});await nav('admin-workflows');await page.screenshot({path:path.join(out,'screenshots/workflow-list.png'),fullPage:true});
 });
 await check('user can review range progress and result without leaving the conversation',async()=>{
  await fresh('chat');await send('核对报表明细差异');await send('昨天全天，已完成订单');await page.locator('[data-chat=show-info]').click();assert(await page.locator('#chat-info-dialog').evaluate(d=>d.open));assert((await page.locator('.chat-info-body').innerText()).includes('昨天全天'));assert(!(await page.locator('.chat-info-body').innerText()).includes('demo-context'));await page.locator('[data-chat=info-tab][data-tab=progress]').click();assert((await page.locator('.chat-info-body').innerText()).includes('待你确认'));await page.keyboard.press('Escape');assert.equal(await page.evaluate(()=>document.activeElement.dataset.chat),'show-info');await page.locator('[data-chat=allow]').click();await page.waitForFunction(()=>ChatPrototype.inspect().latestResultRevision===1);await page.locator('[data-chat=show-info]').click();await page.locator('[data-chat=info-tab][data-tab=result]').click();assert((await page.locator('.chat-info-body').innerText()).includes('等待你验证'));await page.screenshot({path:path.join(out,'screenshots/user-problem-info.png'),fullPage:false});await page.locator('[data-chat=close-info]').click();assert.equal((await inspect()).verification,null);await page.locator('[data-chat=resolved]').click();await page.locator('[data-chat=show-info]').click();await page.locator('[data-chat=info-tab][data-tab=scope]').click();assert((await page.locator('.chat-info-body').innerText()).includes('昨天全天'));await page.locator('[data-chat=close-info]').click();await send('继续核对另一个时段');await page.locator('[data-chat=show-info]').click();await page.locator('[data-chat=info-tab][data-tab=result]').click();assert((await page.locator('.chat-info-body').innerText()).includes('本轮尚无'));await page.locator('[data-chat=close-info]').click();
 });
 await check('historical task workflow and chat reference remain separate read-only comparison fixtures',async()=>{
  const reference=await page.context().newPage();try{const url=pathToFileURL(path.resolve(__dirname,'../../review-baseline-20261025/index.html')).href;for(const route of ['tasks','workflows','chat']){await reference.goto(url+'#/'+route);await reference.locator('.app-main').waitFor();await reference.screenshot({path:path.join(out,'screenshots/reference-'+route+'.png'),fullPage:true});}}finally{await reference.close();}
 });
 await fresh('chat');
};
