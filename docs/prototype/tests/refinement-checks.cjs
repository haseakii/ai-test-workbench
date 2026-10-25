// Offline design regressions, not authentication, resource isolation or billing acceptance.
const assert=require('node:assert/strict');
const path=require('node:path');
module.exports=async({page,check,nav,out})=>{
 const fresh=async()=>{await nav('chat');await page.reload();await page.waitForSelector('#chat-input');};
 const current=()=>page.evaluate(()=>ChatPrototype.inspect());
 const click=a=>page.locator(`[data-chat="${a}"]:visible`).first().click();
 const send=async text=>{await page.locator('#chat-input').fill(text);await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});};
 const prepare=async()=>{await send('核对订单报表差异');await send('昨天全天，已完成订单，EUR');};
 await check('refinement: navigation never stops a running response or loses drafts',async()=>{
  await fresh();await page.locator('#chat-input').fill('核对报表差异');await page.locator('[aria-label="发送消息"]').click();const id=(await current()).id;
  await page.locator('#chat-input').fill('下一条草稿');await click('new');assert.equal((await current()).messages.length,0);const newId=(await current()).id;
  await page.locator(`[data-chat=session][data-id="${id}"]`).click();assert.equal(await page.locator('#chat-input').inputValue(),'下一条草稿');await page.locator(`[data-chat=session][data-id="${newId}"]`).click();
  await page.locator('#chat-input').fill('另一会话草稿');await page.waitForTimeout(800);assert.equal(await page.locator('#chat-input').inputValue(),'另一会话草稿');assert.equal((await current()).messages.length,0);assert.equal(await page.evaluate(()=>document.activeElement.id),'chat-input');
  await page.locator(`[data-chat=session][data-id="${id}"]`).click();assert.equal((await current()).messages.filter(x=>x.role==='assistant').length,1);assert(!(await current()).trace.some(x=>x.type.includes('停止')));
 });
 await check('refinement: cross-conversation model work queues without blocking browsing',async()=>{
  await fresh();await page.locator('#chat-input').fill('核对报表差异');await page.locator('[aria-label="发送消息"]').click();const a=(await current()).id;await click('new');await page.locator('#chat-input').fill('分析错误日志');await page.locator('[aria-label="发送消息"]').click();const b=await current();assert.equal(b.queued,true);assert((await page.locator('.replying').innerText()).includes('排队'));
  await page.locator(`[data-chat=session][data-id="${a}"]`).click();await page.waitForTimeout(1450);await page.locator(`[data-chat=session][data-id="${b.id}"]`).click();assert.equal((await current()).busy,false);assert.equal((await current()).messages.filter(x=>x.role==='assistant').length,1);
 });
 await check('refinement: cancelling queued work does not cancel the other conversation',async()=>{
  await fresh();await page.locator('#chat-input').fill('核对报表差异');await page.locator('[aria-label="发送消息"]').click();const a=(await current()).id;await click('new');await page.locator('#chat-input').fill('分析错误日志');await page.locator('[aria-label="发送消息"]').click();await click('stop');await page.waitForTimeout(1500);assert.equal((await current()).phase,'stopped');assert.equal((await current()).messages.filter(x=>x.role==='assistant').length,1);await page.locator(`[data-chat=session][data-id="${a}"]`).click();assert.equal((await current()).phase,'details');
 });
 await check('refinement: narrow history navigation stays enabled during work',async()=>{
  await fresh();await page.setViewportSize({width:390,height:844});await send('核对报表');const first=(await current()).id;await click('new');await page.locator('#chat-input').fill('分析日志');await page.locator('[aria-label="发送消息"]').click();await page.locator('#chat-mobile-history').selectOption(String(first));assert.equal((await current()).id,first);await page.setViewportSize({width:1440,height:1000});
 });
 await check('refinement: object publication ignores unrelated invalid drafts and preserves them',async()=>{
  await fresh();await page.evaluate(()=>{ConfigurationPrototype.draft().agentConnections[1].address='invalid-unfinished';});await nav('admin-systems');await page.locator('[data-field=name]').fill('订单检查平台');await page.locator('[data-admin=publish]').click();assert.equal(await page.locator('#admin-dialog [data-config-entry]:checked').count(),1);await page.locator('[data-admin=confirm]').click();
  const c=await page.evaluate(()=>({p:ConfigurationPrototype.current(),d:ConfigurationPrototype.draft()}));assert.equal(c.p.systems[0].name,'订单检查平台');assert.notEqual(c.p.agentConnections[1].address,'invalid-unfinished');assert.equal(c.d.agentConnections[1].address,'invalid-unfinished');
 });
 await check('refinement: discard restores only the selected object',async()=>{
  await fresh();await page.evaluate(()=>{const d=ConfigurationPrototype.draft();d.systems[0].description='系统草稿';d.quotaDefaults[0].dailyCalls=250;});await nav('admin-systems');await page.locator('[data-admin=discard]').click();await page.locator('[data-admin=confirm-discard]').click();assert.equal(await page.evaluate(()=>ConfigurationPrototype.draft().quotaDefaults[0].dailyCalls),250);assert.notEqual(await page.evaluate(()=>ConfigurationPrototype.draft().systems[0].description),'系统草稿');
 });
 await check('refinement: dependent change set is atomic and requires explicit selection',async()=>{
  await fresh();const result=await page.evaluate(()=>{const C=ConfigurationPrototype,d=C.draft();d.models.push({...d.models[0],id:'new-model',name:'新预设'});d.environments[0].defaultModel='new-model';d.environments[0].allowedModelIds.push('new-model');d.roles[0].models.push('new-model');d.roles[1].models.push('new-model');const before=C.current();const bad=C.publish(['environments:orders-test']);const unchanged=JSON.stringify(before)===JSON.stringify(C.current());const good=C.publish(['models:new-model','environments:orders-test','roles:tester','roles:observer']);return {bad:bad.ok,unchanged,good:good.ok,model:C.current().environments[0].defaultModel};});assert.deepEqual(result,{bad:false,unchanged:true,good:true,model:'new-model'});
 });
 await check('refinement: unrelated configuration changes do not invalidate approval',async()=>{
  await fresh();await prepare();const p=(await current()).preparation;await page.evaluate(()=>{const C=ConfigurationPrototype;C.draft().systems.find(x=>x.id==='payments').description='与当前问题无关';C.publish(['systems:payments']);});assert.equal(await page.locator('[data-chat=allow]').count(),1);assert.equal((await current()).preparation.id,p.id);assert.equal((await current()).binding,null);
 });
 await check('refinement: presentation rename preserves the pending operation',async()=>{
  await fresh();await prepare();await page.evaluate(()=>{const C=ConfigurationPrototype;C.draft().models[0].name='仅改显示名';C.publish(['models:general']);});assert.equal(await page.locator('[data-chat=allow]').count(),1);
 });
 await check('refinement: relevant context change invalidates only the old plan',async()=>{
  await fresh();await prepare();const before=(await current()).preparation;await page.evaluate(()=>{const C=ConfigurationPrototype;C.draft().environments[0].description='新的时点口径';C.publish(['environments:orders-test']);});assert.equal(await page.locator('[data-chat=allow]').count(),0);assert.deepEqual((await current()).preparation,before);assert.equal((await current()).binding,null);
 });
 await check('refinement: ordinary configuration edits do not cancel an accepted binding',async()=>{
  await fresh();await prepare();await click('allow');const before=(await current()).binding;await page.evaluate(()=>{const C=ConfigurationPrototype;C.draft().systems[0].description='下轮使用的业务背景';C.publish(['systems:orders']);});await page.waitForSelector('.result-choice');assert.equal((await current()).latestResultRevision,1);assert.equal((await current()).binding.id,before.id);assert.deepEqual((await current()).binding.preparationSnapshot,before.preparationSnapshot);
 });
 await check('refinement: actual scope revocation still stops local progress',async()=>{
  await fresh();await prepare();await click('allow');await page.evaluate(()=>{const C=ConfigurationPrototype;C.draft().roles[0].environments=C.draft().roles[0].environments.filter(x=>x!=='orders-test');C.publish(['roles:tester']);});await page.waitForTimeout(800);assert.equal((await current()).busy,false);assert.equal((await current()).latestResultRevision,0);assert.equal((await current()).binding.state,'stopped_simulation');
 });
 await check('refinement: understanding authorization is distinct from resource approval',async()=>{
  await fresh();await send('核对报表差异');const s=await current();assert.equal(s.understanding.purpose,'understanding');assert.deepEqual(s.understanding.tools,[]);assert.equal(s.binding,null);assert.equal(s.understanding.attachmentContentsAllowed,false);assert.equal(s.understanding.actualExecution,'not_run');
 });
 await check('refinement: no understanding grant means static receipt, no AI activity',async()=>{
  await fresh();await page.evaluate(()=>{const C=ConfigurationPrototype;C.draft().environments[0].understandingAllowed=false;C.publish(['environments:orders-test']);});await send('请核对报表');assert.equal((await current()).busy,false);assert.equal((await current()).binding,null);assert.equal((await current()).messages.at(-1).role,'system');assert.equal(await page.locator('.replying').count(),0);assert.equal(await page.locator('.inline-permission').count(),0);
 });
 await check('refinement: collection-only never claims a model response or comprehension',async()=>{
  await fresh();await page.evaluate(()=>{const u=ReviewJourney.users.find(x=>x.id==='demo-live-tester');u.executionMode='collect_only';u.revision++;ReviewJourney.userChanged();});await send('整理报表');await send('昨天');const s=await current();assert.equal(s.phase,'collect_only');assert.equal(s.binding,null);assert.equal(s.messages.filter(x=>x.role==='assistant').length,0);assert(s.messages.filter(x=>x.role==='system').every(x=>x.text.includes('不是 AI 分析')));
 });
 await check('refinement: unknown usage is usable only under an explicitly non-hard policy',async()=>{
  await fresh();const q=await page.evaluate(()=>ContextQuota.resolve(ConfigurationPrototype.current(),ReviewJourney.subject('tester'),'general','orders-test'));assert.equal(q.configured,true);assert.equal(q.capability,'unreported');assert.equal(q.actualUsage,'unknown');assert.equal(q.enforcement,'platform_dispatch_only');assert.equal(q.hardLimitVerified,false);
 });
 await check('refinement: hard-limit workflow rejects both unknown and merely reported capability',async()=>{
  await fresh();const states=await page.evaluate(()=>{const C=ConfigurationPrototype,c=C.current(),u=ReviewJourney.subject('tester');c.environments[0].requireHardModelLimits=true;const unknown=ContextQuota.resolve(c,u,'general','orders-test');c.agentConnections[0].quotaCapability='reported';const reported=ContextQuota.resolve(c,u,'general','orders-test');c.agentConnections[0].quotaCapability='enforced';const synthetic=ContextQuota.resolve(c,u,'general','orders-test');return [unknown,reported,synthetic];});assert.equal(states[0].configured,false);assert.equal(states[1].configured,false);assert.equal(states[2].configured,true);assert.equal(states[2].hardLimitVerified,false);assert.equal(states[2].realAdmission,'unknown');
 });
 await check('refinement: per-model accounting references the same cross-connection user total',async()=>{
  await fresh();const qs=await page.evaluate(()=>['general','analysis'].map(id=>ContextQuota.resolve(ConfigurationPrototype.current(),ReviewJourney.subject('tester'),id)));assert.notEqual(qs[0].pairKey,qs[1].pairKey);assert.equal(qs[0].userTotalKey,qs[1].userTotalKey);
 });
 await check('refinement: bootstrap risk profiles do not grant permissions by toggling a demo',async()=>{
  await fresh();const before=await page.evaluate(()=>JSON.stringify(ReviewJourney.users));await nav('getting-started');assert((await page.locator('#onboarding-policy').innerText()).includes('有限只读'));await page.locator('#onboarding-mode').selectOption('sensitive');assert((await page.locator('#onboarding-policy').innerText()).includes('独立复核'));assert.equal(await page.evaluate(()=>JSON.stringify(ReviewJourney.users)),before);await page.screenshot({path:path.join(out,'screenshots/refinement-onboarding.png'),fullPage:true});
 });
 await fresh();await prepare();await page.screenshot({path:path.join(out,'screenshots/refinement-chat.png'),fullPage:true});await nav('admin-permissions');await page.locator('.admin-details').first().evaluate(x=>x.open=true);await page.screenshot({path:path.join(out,'screenshots/refinement-quotas.png'),fullPage:true});await fresh();
};
