const assert=require('node:assert/strict'),path=require('node:path');
module.exports=async({page,check,nav,out})=>{
 const f=n=>page.locator(`[data-gov-field=${n}]`),af=n=>page.locator(`[data-field=${n}]`),g=n=>page.locator(`[data-gov=${n}]`).click(),a=n=>page.locator(`[data-admin=${n}]`).click();
 const fresh=async(route='admin-integrations')=>{await nav(route);await page.reload();await page.waitForSelector('#configuration-root h1');};
 const publish=async()=>{await g('publish');await g('confirm');};
 const apply=async()=>{await a('publish');await a('confirm');};
 const send=async text=>{await page.locator('#chat-input').fill(text);await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});};
 await fresh();
 await check('governance entry offers connection list and next steps with diagnostics collapsed',async()=>{
  assert.equal(await page.locator('.admin-sidebar a[href="#admin-models"]').count(),0);assert.equal(await page.locator('[data-gov=edit]').count(),2);assert.equal(await page.locator('.governance-diagnostics[open]').count(),0);assert.equal(await page.locator('.gov-editor').count(),0);await page.screenshot({path:path.join(out,'screenshots/governance-agents.png'),fullPage:true});
 });
 await check('new agent requires synthetic discovery and changed connection invalidates that observation',async()=>{
  await g('add');assert(!await f('enabled').isChecked());await f('name').fill('演示专项 Agent');await f('enabled').check();await g('publish');assert((await page.locator('#gov-notice').innerText()).includes('模拟发现'));await g('discover');await f('address').fill('https://other.example.invalid');await g('publish');assert.equal(await page.locator('#govern-dialog[open]').count(),0);await g('discover');await publish();const c=await page.evaluate(()=>ConfigurationPrototype.current());assert.equal(c.agentConnections.length,3);assert.equal(c.agentConnections.at(-1).observation,'synthetic');assert.equal(c.models.length,2);assert.equal(await page.evaluate(()=>GovernanceDesign.decision(ConfigurationPrototype.current(),{environmentId:'orders-test',modelId:'general'}).realAdmission),'unknown');
 });
 await check('discovered model preset needs explicit environment and role binding before user selection',async()=>{
  await g('create-model');assert.equal(await af('connectionId').inputValue(),'demo-agentConnections-3');await af('name').fill('专项检查模型');await af('enabled').check();await apply();await nav('chat');assert(!(await page.locator('#chat-model option').allTextContents()).includes('专项检查模型'));await nav('admin-environments');await page.locator('[data-grant=allowedModelIds][value=demo-models-3]').check();await apply();await nav('chat');assert(!(await page.locator('#chat-model option').allTextContents()).includes('专项检查模型'));await nav('admin-permissions');await page.locator('[data-grant=models][value=demo-models-3]').check();await apply();await nav('chat');assert((await page.locator('#chat-model option').allTextContents()).includes('专项检查模型'));await page.locator('#chat-model').selectOption('专项检查模型');await send('核对报表差异');await send('昨天全天');const s=await page.evaluate(()=>ChatPrototype.inspect());assert.equal(s.preparation.governanceSnapshot.connection.id,'demo-agentConnections-3');assert.equal(s.preparation.governanceSnapshot.runtime.id,'demo-runtime');assert.equal(s.preparation.governanceSnapshot.realExecutionAllowed,false);
 });
 await check('revoking an environment model binding blocks old confirmation without changing its snapshot',async()=>{
  const before=await page.evaluate(()=>ChatPrototype.inspect());await nav('admin-environments');await page.locator('[data-grant=allowedModelIds][value=demo-models-3]').uncheck();await apply();await nav('chat');assert.equal(await page.locator('[data-chat=allow]').count(),0);assert.deepEqual((await page.evaluate(()=>ChatPrototype.inspect())).preparation.governanceSnapshot,before.preparation.governanceSnapshot);assert(await page.locator('[aria-label="发送消息"]').isDisabled());
 });
 await fresh();
 await check('stopping a connection blocks its default rather than silently selecting another agent',async()=>{
  await page.locator('[data-gov=edit][data-id=demo-control]').click();await f('enabled').uncheck();await publish();await nav('chat');const s=await page.evaluate(()=>ChatPrototype.inspect());assert.equal(s.model,'无可用模型');assert(await page.locator('[aria-label="发送消息"]').isDisabled());assert((await page.locator('#chat-model option').allTextContents()).includes('分析模型'));
 });
 await fresh();
 await check('agent identity changes cannot reuse an existing model preset or its quota identity',async()=>{
  await page.locator('[data-gov=edit][data-id=demo-control]').click();await f('agentId').fill('demo-different-agent');await g('discover');await g('publish');assert.equal(await page.locator('#govern-dialog[open]').count(),0);assert((await page.locator('#gov-notice').innerText()).includes('身份绑定不一致'));const decision=await page.evaluate(()=>GovernanceDesign.decision(ConfigurationPrototype.draft(),{environmentId:'orders-test',modelId:'general'}));assert.equal(decision.configured,false);assert(decision.reasons.some(r=>r.includes('旧额度绑定')));assert.equal((await page.evaluate(()=>ConfigurationPrototype.current())).agentConnections[0].agentId,'demo-agent-control');
 });
 await fresh('admin-runtime');
 await check('runtime configuration rejects broad access and only publishes reviewed draft scope',async()=>{
  await g('edit');await f('terminal').check();await g('publish');assert((await page.locator('#gov-notice').innerText()).includes('关闭任意终端'));assert.equal((await page.evaluate(()=>ConfigurationPrototype.current())).runtimeProfiles[0].terminal,false);await f('terminal').uncheck();await f('nodeRef').fill('demo-isolated-node-2');await g('publish');await g('cancel');assert.equal((await page.evaluate(()=>ConfigurationPrototype.current())).runtimeProfiles[0].nodeRef,'demo-node-1');await publish();await page.screenshot({path:path.join(out,'screenshots/governance-runtime.png'),fullPage:true});await nav('chat');await send('核对报表差异');await send('昨天');assert.equal((await page.evaluate(()=>ChatPrototype.inspect())).preparation.governanceSnapshot.runtime.nodeRef,'demo-isolated-node-2');
 });
 await fresh('admin-context');
 await check('context policy applies per environment while personal memory requires owner and reviewed source',async()=>{
  await g('edit');await f('memory').selectOption('personal');await f('inputLimit').fill('8000');await publish();await g('memory-detail');await page.locator('[data-service=memory-review]').click();await page.locator('[data-service=memory-activate]').click();assert(await page.evaluate(()=>ReviewJourney.memoryAllowed('orders-test','demo-live-tester')));assert(!await page.evaluate(()=>ReviewJourney.memoryAllowed('orders-test','demo-live-observer')));assert(!await page.evaluate(()=>ReviewJourney.memoryAllowed('payments-test','demo-live-tester')));await page.screenshot({path:path.join(out,'screenshots/governance-context.png'),fullPage:true});await nav('chat');await send('核对报表');await send('昨天全天');const p=(await page.evaluate(()=>ChatPrototype.inspect())).preparation;assert.equal(p.governanceSnapshot.contextPolicy.inputLimit,8000);assert.deepEqual(p.memoryRefs,['demo-memory-1']);
 });
 await fresh('admin-resources');
 await check('MCP endpoint or tool changes require explicit renewed range review before publication',async()=>{
  await page.locator('[data-resource-tab=mcps]').click();await af('tools').fill('report.read, detail.read, report.summary');await a('publish');assert((await page.locator('#admin-feedback').innerText()).includes('重新确认'));await a('review-mcp');await af('address').fill('https://new-tools.example.invalid/mcp');await a('publish');assert.equal(await page.locator('#admin-dialog[open]').count(),0);await a('review-mcp');await apply();assert.equal(await page.evaluate(()=>ResourceDesign.resolve(ConfigurationPrototype.current(),'orders-test').mcps[0].actualCapabilities),'unknown');await page.screenshot({path:path.join(out,'screenshots/governance-mcp.png'),fullPage:true});
 });
 await fresh();
 await check('governance changes cannot be published by a permissions reviewer',async()=>{
  await page.locator('[data-service-field=actor]').selectOption('access');const before=await page.evaluate(()=>ConfigurationPrototype.draft());await g('add');assert((await page.locator('#gov-notice').innerText()).includes('没有接入配置权限'));assert.deepEqual(await page.evaluate(()=>ConfigurationPrototype.draft()),before);
 });
 await fresh('admin-users');
 await check('member effective access preview reacts to draft scope without applying it',async()=>{
  await page.locator('[data-service=select-user][data-id=demo-live-tester]').click();await page.locator('#effective-user-preview details').evaluate(d=>d.open=true);await page.locator('[data-user-scope=orders-test]').uncheck();assert(!(await page.locator('#effective-user-preview').innerText()).includes('订单系统 / 测试环境'));assert.equal(await page.evaluate(()=>ReviewJourney.subject('tester').scopes),null);await page.screenshot({path:path.join(out,'screenshots/governance-member.png'),fullPage:true});
 });
 await fresh('admin-integrations');
 await check('record review displays frozen execution setup while keeping conversation content leased',async()=>{
  await nav('chat');await send('核对报表差异');await send('昨天全天');await nav('admin-records');await page.locator('[data-service-field=actor]').selectOption('support');const id=await page.evaluate(()=>ReviewJourney.records().at(-1).id);await page.locator(`[data-service=select-record][data-id="${id}"]`).click();const text=await page.locator('.record-binding-summary').innerText();assert(text.includes('demo-control'));assert(text.includes('上下文'));assert(text.includes('report.read'));assert.equal(await page.locator('.service-sensitive').count(),0);await page.screenshot({path:path.join(out,'screenshots/governance-record.png'),fullPage:true});
 });
 await fresh();
 await check('governance forms and explicit publication remain usable on a narrow viewport',async()=>{
  await page.setViewportSize({width:390,height:844});await page.locator('[data-gov=edit][data-id=demo-control]').click();await f('name').fill('移动端 Agent 草稿');await g('publish');assert.equal(await page.locator('#govern-dialog[open]').count(),1);await page.keyboard.press('Escape');assert.equal(await page.locator('#govern-dialog[open]').count(),0);assert(!await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth));await page.screenshot({path:path.join(out,'screenshots/governance-mobile.png'),fullPage:true});await page.setViewportSize({width:1440,height:1000});
 });
 await nav('chat');await page.reload();
};
