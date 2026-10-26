// Design-only custom resources and policy-driven execution/approval management.
const assert=require('node:assert/strict');
const path=require('node:path');
module.exports=async({page,check,nav,out})=>{
 const admin=name=>page.locator('[data-admin="'+name+'"]').click();
 const field=name=>page.locator('[data-field="'+name+'"]');
 const service=name=>page.locator('[data-service="'+name+'"]').click();
 const sf=name=>page.locator('[data-service-field="'+name+'"]');
 const ops=name=>page.locator('[data-ops="'+name+'"]').click();
 const chat=()=>page.evaluate(()=>ChatPrototype.inspect());
 const send=async text=>{await page.locator('#chat-input').fill(text);await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});};
 const apply=async()=>{await admin('publish-set');await page.locator('#admin-dialog[open]').waitFor();await page.locator('#admin-dialog [data-config-entry]').evaluateAll(xs=>xs.forEach(x=>x.checked=true));await admin('confirm');};
 const fresh=async()=>{await nav('chat');await page.reload();await page.waitForSelector('#chat-root h1');};
 const mode=async(value,id='demo-live-tester')=>{await nav('admin-users');await sf('actor').selectOption('access');await page.locator('[data-service=select-user][data-id="'+id+'"]').click();await sf('userExecutionMode').selectOption(value);await sf('reason').fill('资源执行设计走查：调整演示用户方式');await service('save-user');assert((await page.locator('#service-dialog').innerText()).includes('执行方式'));await service('confirm-save');};
 await fresh();
 await check('resource registry can add and bind custom Git projects MCP and Skills to one environment',async()=>{
  await nav('admin-resources');await admin('add');await field('name').fill('自定义核对项目');await field('address').fill('https://git.example.invalid/custom/check.git');await field('defaultRef').fill('refs/heads/qa');await field('paths').fill('services/, docs/');await field('enabled').check();
  await page.locator('[data-resource-tab=mcps]').click();await admin('add');await field('name').fill('自定义观测 MCP');await field('address').fill('https://tools.example.invalid/custom/mcp');await field('tools').fill('log.read');await field('enabled').check();await admin('review-mcp');
  await page.locator('[data-resource-tab=skills]').click();await admin('add');await field('name').fill('自定义核对 Skill');await field('instructions').fill('只读核对，保留证据来源，不改变用户数据。');await page.locator('[data-grant=mcpIds][value=demo-mcps-3]').check();await field('enabled').check();
  await nav('admin-environments');await page.locator('[data-record=orders-test]').click();await page.locator('[data-grant=projectIds][value=demo-projects-4]').check();await page.locator('[data-project-ref=demo-projects-4]').fill('demo-custom-commit');await page.locator('[data-grant=mcpIds][value=demo-mcps-3]').check();await page.locator('[data-grant=skillIds][value=demo-skills-2]').check();await apply();
  const snapshot=await page.evaluate(()=>ResourceDesign.resolve(ConfigurationPrototype.current(),'orders-test'));assert(snapshot.projects.some(x=>x.id==='demo-projects-4'&&x.requestedRef==='demo-custom-commit'&&x.actualCommit===null));assert(snapshot.mcps.some(x=>x.id==='demo-mcps-3'));assert(snapshot.skills.some(x=>x.id==='demo-skills-2'&&x.verifiedContentHash===null));
  await page.screenshot({path:path.join(out,'screenshots/environment-resources.png'),fullPage:true});
 });
 await check('resource addresses reject embedded credentials and Skill dependency mismatch',async()=>{
  await nav('admin-resources');await page.locator('[data-resource-tab=projects]').click();await page.locator('[data-record=demo-projects-4]').click();await field('address').fill('https://demo:not-a-secret@git.example.invalid/repo.git');await admin('publish');assert((await page.locator('#admin-feedback').innerText()).includes('项目地址'));await admin('discard');await admin('confirm-discard');await field('paths').fill('C:/outside');await admin('publish');assert((await page.locator('#admin-feedback').innerText()).includes('只读路径'));await admin('discard');await admin('confirm-discard');
  await nav('admin-environments');await page.locator('[data-record=orders-test]').click();await page.locator('[data-grant=mcpIds][value=demo-mcps-3]').uncheck();await admin('publish');assert((await page.locator('#admin-feedback').innerText()).includes('Skill 要求的 MCP'));await admin('discard');await admin('confirm-discard');
 });
 await check('unused code project edit preserves approval while a later plan adopts new published resources',async()=>{
  await nav('chat');await send('请检查报表差异');await send('昨天全天，已完成订单。');const before=(await chat()).preparation.resources;assert(before.projects.some(x=>x.id==='demo-projects-4'));
  await nav('admin-resources');await page.locator('[data-record=demo-projects-4]').click();await field('address').fill('https://git.example.invalid/custom/check-v2.git');await apply();await nav('chat');assert.equal(await page.locator('[data-chat=allow]').count(),1);assert.equal((await chat()).preparation.flowDecision.codeProjects.length,0);assert.deepEqual((await chat()).preparation.resources,before);
  await send('按新配置重新准备本次检查');const after=(await chat()).preparation.resources;assert(after.configurationRevision>before.configurationRevision);assert.equal(after.projects.find(x=>x.id==='demo-projects-4').revision,2);
 });
 await check('resource directory supports trusted stdio templates and repository Skill versions',async()=>{
  await nav('admin-resources');await page.locator('[data-resource-tab=mcps]').click();await page.locator('[data-record=demo-mcps-3]').click();await field('transport').selectOption('stdio');await field('address').fill('template://observability/custom');await admin('review-mcp');await page.locator('[data-resource-tab=skills]').click();await page.locator('[data-record=demo-skills-2]').click();await field('source').selectOption('repository');await field('address').fill('repo://demo-projects-4/skills/review/SKILL.md');await field('version').fill('review-r2');await apply();
  const resources=await page.evaluate(()=>ResourceDesign.resolve(ConfigurationPrototype.current(),'orders-test'));assert.equal(resources.mcps.find(x=>x.id==='demo-mcps-3').transport,'stdio');assert.equal(resources.skills.find(x=>x.id==='demo-skills-2').version,'review-r2');assert.equal(resources.skills.find(x=>x.id==='demo-skills-2').verifiedContentHash,null);
 });
 await fresh();
 await check('user-level automatic readonly mode executes only as a policy-backed synthetic run',async()=>{
  await mode('auto_readonly');await nav('chat');await send('检查报表差异');await send('测试环境，昨天全天。');const s=await chat();assert.equal(s.latestResultRevision,1);assert.equal(s.binding.authority,'policy');assert.equal(s.binding.actualExecution,'not_run');assert.equal(s.preparation.executionPolicy.mode,'auto_readonly');assert.equal(await page.locator('[data-chat=allow]').count(),0);assert(s.trace.some(x=>x.type.includes('自动只读策略')));
 });
 await check('execution management displays the same request and its run history',async()=>{
  const s=await chat();await nav('admin-executions');await sf('actor').selectOption('operator');assert.equal(await page.locator('.admin-operations-nav a').count(),2);await page.locator('[data-ops=select][data-id="demo-chat-'+s.id+'"]').click();await page.locator('.ops-technical summary').click();assert((await page.locator('#configuration-root').innerText()).includes(s.binding.id));assert((await page.locator('#configuration-root').innerText()).includes(s.preparation.id));await page.screenshot({path:path.join(out,'screenshots/execution-management.png'),fullPage:true});await ops('close');await nav('admin-approvals');await sf('actor').selectOption('approver');await page.locator('[data-ops=tab][data-value=all]').click();await page.locator('[data-ops=select][data-id="demo-chat-'+s.id+'"]').first().click();assert((await page.locator('#configuration-root').innerText()).includes('非人工审批'));assert.equal(await page.locator('[data-ops=approve]').count(),0);await ops('close');
 });
 await check('environment approval ceiling overrides user automatic execution',async()=>{
  await nav('admin-environments');await page.locator('[data-record=orders-test]').click();await field('policyMode').selectOption('require_approval');await apply();await nav('chat');await send('补充一个时段，继续检查');const s=await chat();assert.equal(s.preparation.executionPolicy.mode,'approval');assert.equal(s.latestResultRevision,1);assert(s.messages.findLast(x=>x.kind==='permission').pendingReview);assert.equal(await page.locator('[data-chat=allow]').count(),0);
 });
 await check('dedicated approval management reviews the current plan and does not resolve the issue',async()=>{
  const before=await chat();await nav('admin-approvals');await sf('actor').selectOption('approver');await page.locator('[data-ops=tab][data-value=pending]').click();await page.locator('[data-ops=select][data-id="demo-chat-'+before.id+'"]').click();await page.screenshot({path:path.join(out,'screenshots/approval-management.png'),fullPage:true});await ops('approve');assert((await page.locator('#service-dialog').textContent()).includes(before.preparation.id));await page.screenshot({path:path.join(out,'screenshots/approval-decision.png'),fullPage:false});await ops('confirm-approve');await page.waitForFunction(()=>ChatPrototype.inspect().latestResultRevision===2);
  const s=await chat();assert.equal(s.executions.length,2);assert.equal(s.binding.authority,'reviewer');assert.equal(s.binding.id,s.preparation.plannedBindingId);assert.equal(s.verification,null);assert.equal(new Set(s.executions.map(x=>x.id)).size,2);
 });
 await check('approval rejects without execution and operator cannot approve',async()=>{
  await nav('chat');await send('请继续检查另一天');const before=await chat();await nav('admin-approvals');await sf('actor').selectOption('operator');await page.locator('[data-ops=select][data-id="demo-chat-'+before.id+'"]').click();assert(await page.locator('[data-ops=approve]').isDisabled());await ops('close');await sf('actor').selectOption('approver');await page.locator('[data-ops=select][data-id="demo-chat-'+before.id+'"]').click();await ops('reject');assert((await page.locator('#service-feedback').innerText()).includes('理由'));await page.locator('[data-ops-field=reason]').fill('需要用户补齐时间和范围之后重新提交。');await ops('reject');await ops('confirm-reject');const after=await chat();assert.equal(after.phase,'rejected');assert.equal(after.executions.length,before.executions.length);assert.equal(after.latestResultRevision,before.latestResultRevision);
 });
 await check('collect-only user mode records requests without creating approvals or executions',async()=>{
  await mode('collect_only');await nav('chat');const before=(await chat()).executions.length;await send('先记录这个问题，暂时不查');const s=await chat();assert.equal(s.phase,'collect_only');await page.locator('.chat-check-scope summary').click();assert((await page.locator('.chat-check-scope').innerText()).includes('不提交执行'));assert.equal(s.executions.length,before);assert(!s.messages.some(x=>x.pendingReview));await nav('admin-executions');await sf('actor').selectOption('operator');await page.locator('[data-ops-field=filter]').selectOption('collect');assert((await page.locator('#configuration-root').innerText()).includes('仅收集'));
 });
 await fresh();
 await check('automatic user mode cannot bypass a role that disallows automatic checks',async()=>{
  await nav('admin-permissions');await page.locator('#admin-preview-role').selectOption('observer');await mode('auto_readonly','demo-live-observer');await nav('chat');await send('检查报表');await send('补齐日期条件');const s=await chat();assert.equal(s.preparation.executionPolicy.requested,'auto_readonly');assert.equal(s.preparation.executionPolicy.mode,'approval');assert.equal(s.binding,null);
 });
 await fresh();
 await check('non-readonly resource prevents execution even when the user requests automatic mode',async()=>{
  await nav('admin-resources');await page.locator('[data-resource-tab=mcps]').click();await page.locator('[data-record=demo-data-mcp]').click();await field('readOnly').uncheck();await admin('review-mcp');await apply();await mode('auto_readonly');await nav('chat');await send('检查报表');await send('日期为昨天');const s=await chat();assert.equal(s.phase,'blocked');assert.equal(s.binding,null);assert(s.preparation.executionPolicy.reason.includes('非只读'));
 });
 await check('operator stop cancels only the selected synthetic execution and no late result arrives',async()=>{
  const p=await page.context().newPage(),errors=[];p.on('pageerror',e=>errors.push(String(e)));
  try{
   await p.clock.install();await p.goto(page.url().split('#')[0]+'#chat');await p.clock.pauseAt(new Date(Date.now()+1000));
   await p.evaluate(()=>{const u=ReviewJourney.users.find(x=>x.id==='demo-live-tester');u.executionMode='approval';u.revision++;ReviewJourney.userChanged();});
   for(let i=0;i<2;i++){
    await p.locator('#chat-input').fill('检查报表 '+i);await p.locator('[aria-label="发送消息"]').click();await p.clock.runFor(700);await p.locator('#chat-input').fill('昨天全天');await p.locator('[aria-label="发送消息"]').click();await p.clock.runFor(700);if(i===0)await p.locator('[data-chat=new]:visible').first().click();
   }
   await p.evaluate(()=>location.hash='admin-approvals');await p.locator('[data-service-field=actor]').selectOption('approver');
   for(const id of ['demo-chat-1','demo-chat-2']){await p.locator('[data-ops=select][data-id="'+id+'"]').click();await p.locator('[data-ops=approve]').click();await p.locator('[data-ops=confirm-approve]').click();}
   assert.equal(await p.evaluate(()=>ReviewJourney.records().filter(x=>x.binding?.state==='active_simulation').length),1);assert.equal(await p.evaluate(()=>ReviewJourney.records().filter(x=>x.binding?.state==='queued_simulation').length),1);
   await p.evaluate(()=>location.hash='admin-executions');await p.locator('[data-service-field=actor]').selectOption('operator');await p.locator('[data-ops=select][data-id=demo-chat-1]').click();await p.locator('[data-ops=stop]').click();await p.locator('[data-ops=confirm-stop]').click();await p.clock.runFor(2000);
   const records=await p.evaluate(()=>ReviewJourney.records());assert.equal(records.find(x=>x.id==='demo-chat-1').resultRevision,0);assert.equal(records.find(x=>x.id==='demo-chat-1').binding.state,'stopped_simulation');assert.equal(records.find(x=>x.id==='demo-chat-2').resultRevision,1);assert.equal(records.find(x=>x.id==='demo-chat-2').binding.state,'terminal_simulation');assert.deepEqual(errors,[]);
  }finally{await p.close();}
 });
 await fresh();
};
