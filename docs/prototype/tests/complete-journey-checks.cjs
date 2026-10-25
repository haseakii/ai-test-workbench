// One continuous UI path. Seeded offline registry, synthetic identity and execution only.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
module.exports=async({page,check,nav,out})=>{
 const gf=n=>page.locator(`[data-gov-field=${n}]`),af=n=>page.locator(`[data-field=${n}]`),sf=n=>page.locator(`[data-service-field=${n}]`);
 const g=n=>page.locator(`[data-gov=${n}]`).click(),a=n=>page.locator(`[data-admin=${n}]`).click(),s=n=>page.locator(`[data-service=${n}]`).click(),o=n=>page.locator(`[data-ops=${n}]`).click();
 const apply=async()=>{await a('publish-set');await page.locator('#admin-dialog [data-config-entry]').evaluateAll(xs=>xs.forEach(x=>x.checked=true));await a('confirm');},publish=async()=>{await g('publish');await g('confirm');};
 const send=async text=>{await page.locator('#chat-input').fill(text);await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});};
 const inspect=()=>page.evaluate(()=>ChatPrototype.inspect());
 await nav('admin-runtime');await page.reload();await page.waitForSelector('#configuration-root h1');
 let documentLoads=0;const loaded=()=>documentLoads++;page.on('domcontentloaded',loaded);
 try{await check('continuous custom setup survives member opening approval delivery and administrator review',async()=>{
  await g('add');await gf('name').fill('连续走查运行配置');await gf('nodeRef').fill('demo-journey-node');await gf('enabled').check();await publish();
  const runtime=await page.evaluate(()=>ConfigurationPrototype.current().runtimeProfiles.at(-1));
  await nav('admin-context');await g('add');await gf('name').fill('连续走查上下文');await gf('inputLimit').fill('9000');await gf('enabled').check();await publish();
  const context=await page.evaluate(()=>ConfigurationPrototype.current().contextPolicies.at(-1));
  await nav('admin-integrations');await g('add');await gf('name').fill('连续走查 ACP Agent');await gf('provider').selectOption('direct');await gf('address').fill('template://journey-agent');await gf('runtimeId').selectOption(runtime.id);await gf('enabled').check();await g('discover');await publish();
  const agent=await page.evaluate(()=>ConfigurationPrototype.current().agentConnections.at(-1));
  await g('create-model');assert.equal(await af('connectionId').inputValue(),agent.id);await af('name').fill('连续走查模型');await af('enabled').check();await apply();
  const model=await page.evaluate(()=>ConfigurationPrototype.current().models.at(-1));

  await nav('admin-resources');await a('add');await af('name').fill('连续走查代码');await af('address').fill('https://git.example.invalid/journey/report.git');await af('description').fill('连续走查的报表汇总模块');await af('enabled').check();
  const project=await page.evaluate(()=>ConfigurationPrototype.draft().projects.at(-1));
  await page.locator('[data-resource-tab=mcps]').click();await a('add');await af('name').fill('连续走查 MCP');await af('tools').fill('report.read, detail.read');await af('enabled').check();await a('review-mcp');
  const mcp=await page.evaluate(()=>ConfigurationPrototype.draft().mcps.at(-1));
  await page.locator('[data-resource-tab=skills]').click();await a('add');await af('name').fill('连续走查 Skill');await af('instructions').fill('先核对时间与状态，再以只读工具计算差异并记录来源。');await page.locator(`[data-grant=mcpIds][value=${mcp.id}]`).check();await af('enabled').check();
  const skill=await page.evaluate(()=>ConfigurationPrototype.draft().skills.at(-1));
  await nav('admin-systems');await page.locator('[data-record=orders]').click();await page.locator('[data-open-environment=orders-test]').click();
  await af('description').fill('连续走查：东八区、已完成订单、欧元');await af('contextPolicyId').selectOption(context.id);await page.locator(`[data-grant=allowedModelIds][value=${model.id}]`).check();
  await page.locator(`[data-grant=projectIds][value=${project.id}]`).check();await page.locator(`[data-project-ref=${project.id}]`).fill('demo-journey-commit');await page.locator(`[data-grant=mcpIds][value=${mcp.id}]`).check();await page.locator(`[data-grant=skillIds][value=${skill.id}]`).check();
  await nav('admin-workflows');await page.locator('[data-record=report]').click();while(await page.locator('[data-flow-tool]:checked').count())await page.locator('[data-flow-tool]:checked').first().uncheck();
  for(const tool of ['report.read','detail.read'])await page.locator(`[data-flow-tool="${mcp.id}::${tool}"]`).check();
  await page.locator('[data-grant=skillIds][value=demo-report-check]').uncheck();await page.locator(`[data-grant=skillIds][value=${skill.id}]`).check();await af('readCode').check();await apply();
  await nav('chat');assert(!(await page.locator('#chat-model option').allTextContents()).includes(model.name));
  await nav('admin-permissions');await page.locator('[data-record=tester]').click();await page.locator(`[data-grant=models][value=${model.id}]`).check();await apply();
  const published=await page.evaluate(()=>ConfigurationPrototype.current());

  await nav('getting-started');await page.locator('[data-onboarding=begin]').click();await page.locator('[data-onboarding=identity]').click();await page.locator('#join-reason').fill('连续走查的订单报表排查申请');await page.locator('[data-onboarding=request]').click();
  await nav('chat');assert(await page.locator('[aria-label="发送消息"]').isDisabled());assert.equal(await page.locator('#chat-environment option').count(),0);
  await nav('admin-users');await sf('actor').selectOption('access');await page.locator('[data-service=select-user][data-id=demo-live-tester]').click();await page.locator('[data-user-scope=orders-test]').check();await sf('userExecutionMode').selectOption('approval');await sf('reason').fill('仅开通订单测试环境，逐次审批');await s('toggle-user');await s('confirm-toggle');
  await nav('chat');assert.deepEqual(await page.locator('#chat-system option').allTextContents(),['订单系统']);assert.deepEqual(await page.locator('#chat-environment option').allTextContents(),['测试环境']);await page.locator('#chat-model').selectOption(model.name);
  await send('请核对订单报表与明细金额差异');await send('昨天全天，东八区，已完成订单，欧元');
  const prepared=await inspect(),p=prepared.preparation;assert.equal(prepared.phase,'permission');assert.equal(prepared.binding,null);assert.equal(p.executionPolicy.mode,'approval');assert.equal(p.policyRevision,published.revision);assert.equal(p.provider,'direct');assert.equal(p.governanceSnapshot.connection.id,agent.id);assert.equal(p.governanceSnapshot.runtime.id,runtime.id);assert.equal(p.governanceSnapshot.contextPolicy.id,context.id);assert.equal(p.governanceSnapshot.contextPolicy.inputLimit,9000);assert.equal(p.quotaSnapshot.pairKey,[agent.id,agent.agentId,model.quotaModelId].join('/'));assert.equal(p.quotaSnapshot.source,'全局默认');assert(p.flowDecision.configured);assert.deepEqual(p.flowDecision.toolBindings.map(t=>t.connectionId),[mcp.id,mcp.id]);assert.deepEqual(p.flowDecision.skillBindings.map(t=>t.id),[skill.id]);assert(p.flowDecision.codeProjects.some(t=>t.id===project.id&&t.requestedRef==='demo-journey-commit'));assert(p.executionContext.descriptions.some(t=>t.text==='连续走查的报表汇总模块'));
  const recordId='demo-chat-'+prepared.id;
  await nav('admin-approvals');await sf('actor').selectOption('approver');await page.locator('[data-ops-field=source]').selectOption('live');await page.locator('[data-ops=tab][data-value=pending]').click();await page.locator(`[data-ops=select][data-id="${recordId}"]`).click();await page.locator('[data-ops-field=reason]').fill('批准本次合成只读计划');await o('approve');await page.locator('#service-dialog details summary').click();assert((await page.locator('#service-dialog').innerText()).includes(p.id));await o('confirm-approve');await page.waitForFunction(()=>ChatPrototype.inspect().latestResultRevision===1);
  const delivered=await inspect();assert.equal(delivered.binding.id,p.plannedBindingId);assert.equal(delivered.binding.provider,'direct');assert.equal(delivered.binding.actualExecution,'not_run');assert.equal(delivered.executions.length,1);assert.equal(delivered.verification,null);assert.deepEqual(delivered.binding.preparationSnapshot,p);
  await nav('chat');const download=page.waitForEvent('download');await page.locator('[data-chat=download]').click();const file=await download;assert(file.suggestedFilename().includes('合成'));const csvPath=path.join(out,'complete-journey-difference.csv');await file.saveAs(csvPath);const cells=fs.readFileSync(csvPath,'utf8').trim().split('\n')[1].split(',');assert(cells[0].includes('非业务结果'));assert.equal(Number(cells[1])-Number(cells[2]),Number(cells[3]));assert.equal(Number(cells[3]),2530);await page.locator('[data-chat=resolved]').click();
  const verified=await inspect();assert.equal(verified.verification.resultRevision,1);
  await nav('admin-executions');await sf('actor').selectOption('operator');await page.locator(`[data-ops=select][data-id="${recordId}"]`).click();await page.locator('.ops-technical summary').click();assert((await page.locator('.ops-technical').innerText()).includes(delivered.binding.id));await o('close');
  await nav('admin-records');await sf('actor').selectOption('support');await page.locator(`[data-service=select-record][data-id="${recordId}"]`).click();assert.equal(await page.locator('.service-sensitive').count(),0);const summary=await page.locator('.record-binding-summary').innerText();for(const value of [agent.id,model.name,runtime.name,context.name,mcp.id])assert(summary.includes(value),value);await sf('leaseReason').fill('核对连续走查的同一结果与配置引用');await s('lease');assert((await page.locator('.service-sensitive').innerText()).includes('订单报表'));
  const record=await page.evaluate(id=>ReviewJourney.records().find(r=>r.id===id),recordId);assert.equal(record.owner,'demo-live-tester');assert.equal(record.binding.id,delivered.binding.id);assert.deepEqual(record.preparation,p);assert.equal(record.verification.resultRevision,1);assert.equal(record.title,verified.title);assert.equal(documentLoads,0,'No reload or reset is allowed between configuration and review');
  await page.screenshot({path:path.join(out,'screenshots/complete-journey-record.png'),fullPage:true});
  fs.writeFileSync(path.join(out,'complete-journey.json'),JSON.stringify({scope:'One continuous offline UI path from seeded registry; not empty deployment, identity or Provider acceptance',documentReloads:documentLoads,configurationRevision:published.revision,subjectId:record.owner,recordId,title:record.title,preparationId:p.id,connectionId:agent.id,modelPresetId:model.id,runtimeId:runtime.id,contextPolicyId:context.id,mcpId:mcp.id,skillId:skill.id,projectId:project.id,quotaPairKey:p.quotaSnapshot.pairKey,bindingId:record.binding.id,actualExecution:record.binding.actualExecution,realAdmission:p.governanceSnapshot.realAdmission,actualModelId:p.actualModelId,verification:record.verification,csvDifference:2530},null,2)+'\n');
 });}finally{page.off('domcontentloaded',loaded);}
 await nav('chat');await page.reload();
};
