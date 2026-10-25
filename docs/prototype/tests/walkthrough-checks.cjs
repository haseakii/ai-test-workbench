// Whole offline journey: same synthetic user, conversation, context, approval, result and review record.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
module.exports=async({page,check,nav,out})=>{
 const click=id=>page.locator('[data-chat="'+id+'"]:visible').last().click();
 const service=id=>page.locator('[data-service="'+id+'"]').click();
 const field=id=>page.locator('[data-service-field="'+id+'"]');
 const inspect=()=>page.evaluate(()=>ChatPrototype.inspect());
 const send=async text=>{await page.locator('#chat-input').fill(text);await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});};
 const fresh=async()=>{await nav('chat');await page.reload();await page.waitForSelector('#chat-root h1');};
 const question=async()=>{await send('走查报表差异：这是一条本次对话');await send('昨天全天，测试环境，已完成订单。');};
 const record=async(id,actor='support')=>{await nav('admin-records');await field('actor').selectOption(actor);await page.locator('[data-service=select-record][data-id="'+id+'"]').click();};
 await fresh();
 await check('walkthrough model change invalidates pending plan without rewriting prior snapshot',async()=>{
  await question();const before=(await inspect()).snapshot;await page.locator('#chat-model').selectOption('分析模型');
  assert.equal(await page.locator('[data-chat=allow]').count(),0);assert((await page.locator('.inline-permission').innerText()).includes('模型已变更'));
  assert.deepEqual((await inspect()).snapshot,before);await send('继续按相同范围，改用分析模型');
  assert.equal((await inspect()).preparation.modelId,'analysis');assert.equal((await inspect()).preparation.actualModelId,null);
 });
 await check('walkthrough execution and user delivery share a fixed preparation and binding',async()=>{
  await click('allow');await page.waitForSelector('.result-choice');const s=await inspect();
  assert.equal(s.binding.provider,'direct');assert.equal(s.binding.actualExecution,'not_run');assert.equal(s.latestResultRevision,1);
  assert.equal(s.messages.findLast(x=>x.kind==='result').preparation.id,s.preparation.id);
  await page.locator('.chat-provenance summary').click();assert((await page.locator('.chat-provenance').innerText()).includes('实际模型未知'));
 });
 await check('walkthrough conversation delivers a clearly synthetic deterministic file',async()=>{
  const d=page.waitForEvent('download');await click('download');const download=await d;const target=path.join(out,'walkthrough-difference.csv');await download.saveAs(target);
  const text=fs.readFileSync(target,'utf8');assert(text.includes('非业务结果'));assert(text.includes('2530'));assert(download.suggestedFilename().includes('合成'));
 });
 await check('walkthrough administrator finds this same conversation, context and result revision',async()=>{
  const s=await inspect(),id='demo-chat-'+s.id;await record(id);
  assert((await page.locator('#configuration-root').innerText()).includes(s.subjectId));
  await field('leaseReason').fill('走查 DEMO：核对本次用户记录');await service('lease');assert((await page.locator('.service-sensitive').innerText()).includes('这是一条本次对话'));
  const r=await page.evaluate(id=>ReviewJourney.records().find(x=>x.id===id),id);assert.equal(r.preparation.id,s.preparation.id);assert.equal(r.binding.id,s.binding.id);assert.equal(r.resultRevision,1);assert(r.events.some(x=>x.includes('下载合成产物')));
  await page.screenshot({path:path.join(out,'screenshots/walkthrough-record.png'),fullPage:true});
 });
 await check('walkthrough content lease is lost when leaving the review page',async()=>{
  await nav('admin-context');await nav('admin-records');assert.equal(await page.locator('.service-sensitive').count(),0);
 });
 await check('walkthrough older result cannot confirm a newer question or result version',async()=>{
  await nav('chat');await click('resolved');await send('继续排查：补充另一个时间范围');
  assert.equal(await page.locator('[data-chat=resolved]').count(),0);assert.equal((await inspect()).verification,null);
  await click('allow');await page.waitForFunction(()=>ChatPrototype.inspect().latestResultRevision===2);await click('resolved');assert.equal((await inspect()).verification.resultRevision,2);
 });
 await check('walkthrough frozen configuration revision stays distinct from current admission policy',async()=>{
  const previous=(await inspect()).snapshot.revision;await nav('admin-systems');await page.locator('[data-field=description]').fill('走查：修改显示说明不改写执行快照');await page.locator('[data-admin=publish]').click();await page.locator('[data-admin=confirm]').click();await nav('chat');
  await send('继续核查同一个系统环境');const s=await inspect();assert.equal(s.preparation.configurationRevision,previous);assert(s.preparation.policyRevision>previous);assert.equal(s.snapshot.revision,previous);
 });
 await check('walkthrough artifact download rechecks current role permission',async()=>{
  await nav('admin-permissions');await page.locator('[data-record=tester]').click();await page.locator('[data-field=download]').uncheck();await page.locator('[data-admin=publish]').click();await page.locator('[data-admin=confirm]').click();await nav('chat');
  assert(await page.locator('[data-chat=download]').last().isDisabled());
 });
 await fresh();
 await check('walkthrough a submitter without approval rights waits for the responsible reviewer',async()=>{
  await nav('admin-permissions');await page.locator('#admin-preview-role').selectOption('observer');await nav('chat');await question();
  assert.equal(await page.locator('[data-chat=allow]').count(),0);await click('request-review');assert.equal(await page.locator('.result-choice').count(),0);
  const s=await inspect();await record('demo-chat-'+s.id,'approver');await service('approve-plan');assert((await page.locator('#service-dialog').innerText()).includes(s.preparation.id));await service('confirm-plan');
  await page.waitForFunction(()=>ChatPrototype.inspect().latestResultRevision===1);await nav('chat');assert.equal((await inspect()).verification,null);assert.equal(await page.locator('[data-chat=resolved]').count(),1);
 });
 await fresh();
 await check('walkthrough pausing the actual preview user stops next steps and hides history titles',async()=>{
  await question();await page.locator('#chat-input').fill('这份未发送草稿要保留');
  await nav('admin-users');await field('actor').selectOption('access');await page.locator('[data-service=select-user][data-id=demo-live-tester]').click();await field('reason').fill('走查 DEMO：暂停当前对话用户');await service('toggle-user');await service('confirm-toggle');await nav('chat');
  assert((await page.locator('#chat-notice').innerText()).includes('用户已暂停'));assert(await page.locator('[aria-label="发送消息"]').isDisabled());assert(!(await page.locator('.chat-sidebar nav').innerText()).includes('走查报表'));
  assert.equal((await inspect()).draft,'这份未发送草稿要保留');
 });
 await check('walkthrough restoring a user preserves drafts but does not revive prior approval',async()=>{
  await nav('admin-users');await field('reason').fill('走查 DEMO：恢复当前对话用户');await service('toggle-user');await service('confirm-toggle');await nav('chat');
  assert.equal(await page.locator('#chat-input').inputValue(),'这份未发送草稿要保留');assert.equal(await page.locator('[data-chat=allow]').count(),0);
 });
 await check('walkthrough live user grant changes update model and approval rights on the same identity',async()=>{
  await nav('admin-users');await field('userRole').selectOption('observer');await field('reason').fill('走查 DEMO：调整当前用户协作权限');await service('save-user');await service('confirm-save');await nav('chat');
  await send('按新权限继续补充条件');assert.equal((await inspect()).subjectId,'demo-live-tester');assert.deepEqual(await page.locator('#chat-model option').allTextContents(),['默认模型']);assert(await page.locator('[data-chat=attach]').isDisabled());assert.equal(await page.locator('[data-chat=allow]').count(),0);assert.equal(await page.locator('[data-chat=request-review]').count(),1);
 });
 await fresh();
 await check('walkthrough MCP observation change expires the current plan with no replay',async()=>{
  await question();const s=await inspect();await nav('admin-integrations');await field('scenario').selectOption('schema');await service('simulate-admission-change');await nav('chat');
  assert.equal(await page.locator('[data-chat=allow]').count(),0);assert.equal((await inspect()).binding,null);assert.deepEqual((await inspect()).snapshot,s.snapshot);
 });
 await fresh();
 await check('walkthrough context includes only eligible memory with original source authority',async()=>{
  await nav('admin-context');await field('memory').selectOption('environment');await service('memory-review');await service('memory-activate');await nav('chat');await question();
  const s=await inspect();assert.deepEqual(s.preparation.memoryRefs,['demo-memory-1']);assert.equal(s.preparation.attachmentContentsRead,false);
  await click('allow');await page.waitForSelector('.result-choice');
 });
 await check('walkthrough source revocation invalidates derived result and administrator content lease',async()=>{
  const id='demo-chat-'+(await inspect()).id;await record(id);await field('leaseReason').fill('走查 DEMO：检查来源撤权');await service('lease');
  await nav('admin-context');await field('actor').selectOption('config');await service('memory-source-revoke');await nav('chat');
  assert((await page.locator('.assistant').last().innerText()).includes('来源已失效'));assert.equal(await page.locator('[data-chat=resolved]').count(),0);assert.equal(await page.locator('[data-chat=download]').count(),0);
  await record(id);assert.equal(await page.locator('.service-sensitive').count(),0);await field('leaseReason').fill('走查 DEMO：重新审阅脱敏历史');await service('lease');assert(!(await page.locator('.service-sensitive').innerText()).includes('演示结论：'));
 });
 await check('walkthrough content lease expires automatically without a manual button',async()=>{
  await page.clock.install();await page.locator('[data-service=select-record][data-id=demo-record-1]').click();await field('leaseReason').fill('走查 DEMO：检查真实定时过期');await service('lease');assert.equal(await page.locator('.service-sensitive').count(),1);
  await page.clock.fastForward(300001);assert.equal(await page.locator('.service-sensitive').count(),0);assert((await page.locator('#service-feedback').innerText()).includes('自动到期'));
 });
 await fresh();
 await check('walkthrough expired pending plan cannot be approved or executed later',async()=>{
  await question();await page.clock.fastForward(300001);assert.equal(await page.locator('[data-chat=allow]').count(),0);assert((await page.locator('.inline-permission').innerText()).includes('计划已过期'));assert.equal((await inspect()).binding,null);
 });
 await fresh();
};
