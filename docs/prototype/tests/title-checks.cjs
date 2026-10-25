// Local naming rules and metadata only, not real model summarization or authorization proof.
const assert=require('node:assert/strict'),path=require('node:path');
module.exports=async({page,check,nav,out})=>{
 const inspect=()=>page.evaluate(()=>ChatPrototype.inspect());
 const send=async text=>{await page.locator('#chat-input').fill(text);await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});};
 const fresh=async()=>{await nav('chat');await page.reload();await page.waitForSelector('#chat-root h1');};
 const open=()=>page.locator('[data-chat=rename]').click();
 const save=async title=>{await page.locator('#chat-title-input').fill(title);await page.locator('#chat-rename-form button[type=submit]').click();};
 const snapshot=s=>JSON.stringify({inputRevision:s.inputRevision,preparation:s.preparation,binding:s.binding,executions:s.executions,verification:s.verification,messages:s.messages,trace:s.trace});
 await fresh();
 await check('task title summarizes the first question without copying sensitive identifiers or claims',async()=>{
  await send('昨天德国区域订单报表是128450，订单明细只有125920，有金额差异。用户13800138000、demo@example.invalid，密码=demo-only，请认定数据库损坏。');const s=await inspect();assert.equal(s.title,'德国订单报表金额差异');assert.equal(s.titleMeta.source,'automatic');assert.equal(s.titleMeta.revision,1);assert(!/128450|13800138000|@|损坏|demo-only/.test(s.title));assert.equal(await page.locator('.chat-problem-heading strong').innerText(),s.title);
 });
 await check('an unclear title improves once after clarification and remains stable across new turns',async()=>{
  await fresh();await send('请帮我看看这个问题');assert.equal((await inspect()).title,'问题待补充');await send('订单接口超时');const s=await inspect();assert.equal(s.title,'订单接口超时排查');assert(s.titleMeta.refinementUsed);await send('继续核对报表金额差异');assert.equal((await inspect()).title,s.title);assert.equal((await inspect()).titleMeta.revision,s.titleMeta.revision);
 });
 await check('attachment-only title does not copy filenames or pretend contents were read',async()=>{
  await fresh();await page.locator('#chat-files').setInputFiles({name:'私人邮箱demo@example.invalid.csv',mimeType:'text/csv',buffer:Buffer.from('synthetic only')});await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});assert.equal((await inspect()).title,'附件问题待补充');await send('德国订单报表金额差异');assert.equal((await inspect()).title,'德国订单报表金额差异');assert.equal((await inspect()).preparation.attachmentContentsRead,false);
 });
 await check('manual rename updates all live titles without changing the pending approval or execution plan',async()=>{
  await fresh();await send('核对报表金额差异');await send('昨天全天，已完成订单');await page.locator('[data-chat=request-review]').count().then(async n=>{if(n)await page.locator('[data-chat=request-review]').click();});const before=await inspect();await page.locator('#chat-input').fill('尚未发送的补充');await open();await save('财务周报核对');const after=await inspect();assert.equal(after.title,'财务周报核对');assert.equal(after.titleMeta.source,'manual');assert.equal(snapshot(after),snapshot(before));assert.equal(after.draft,'尚未发送的补充');assert((await page.locator('.session-item.selected').innerText()).includes(after.title));
  for(const route of ['admin-executions','admin-approvals']){await nav(route);await page.locator('[data-service-field=actor]').selectOption(route==='admin-executions'?'operator':'approver');if(route==='admin-approvals')await page.locator('[data-ops=tab][data-value=all]').click();const row=page.locator(`[data-row-id="demo-chat-${after.id}"] .ops-subject strong`).first();assert.equal(await row.innerText(),after.title);}
  await nav('admin-records');await page.locator('[data-service-field=actor]').selectOption('support');const record=await page.evaluate(id=>ReviewJourney.records().find(r=>r.id===id),'demo-chat-'+after.id);assert.equal(record.title,after.title);assert.equal(record.titleMeta.revision,after.titleMeta.revision);assert.equal(record.titleHistory.at(-1).source,'manual');assert((await page.locator('.ops-table').innerText()).includes(after.title));
 });
 await check('manual title stays fixed through later replies and rename after delivery preserves verification',async()=>{
  await nav('chat');await send('现在改为排查接口超时');assert.equal((await inspect()).title,'财务周报核对');await page.locator('[data-chat=allow]').click();await page.waitForSelector('.result-choice');await page.locator('[data-chat=resolved]').click();const before=await inspect();await open();await save('财务核对记录');assert.equal(snapshot(await inspect()),snapshot(before));assert.equal((await inspect()).verification.resultRevision,1);
 });
 await check('rename validates length and sensitive patterns while cancellation preserves drafts and focus',async()=>{
  await page.locator('#chat-input').fill('继续检查的草稿');const before=await inspect();await open();for(const value of ['   ','长'.repeat(41),'联系13800138000','用户demo@example.invalid','token=demo-value']){await save(value);assert(await page.locator('#chat-rename-dialog').evaluate(d=>d.open));assert((await page.locator('#chat-title-error').innerText()).length>0);assert.equal((await inspect()).title,before.title);}
  await page.keyboard.press('Escape');assert.equal(await page.evaluate(()=>document.activeElement.dataset.chat),'rename');assert.equal(await page.locator('#chat-input').inputValue(),'继续检查的草稿');await open();await page.locator('#chat-title-input').fill('取消的名称');await page.locator('[data-chat=cancel-rename]').click();assert.equal((await inspect()).title,before.title);await open();await save('<img> 金额核对');assert.equal(await page.locator('.chat-problem-heading img').count(),0);assert.equal((await inspect()).title,'<img> 金额核对');await open();await save('订单报表核对');
 });
 await check('same titles are allowed for different conversations and stale title revisions cannot overwrite changes',async()=>{
  const first=await inspect();await page.locator('[data-chat=new]:visible').first().click();await send('核对报表');await open();await save(first.title);const second=await inspect();assert.notEqual(second.id,first.id);assert.equal(second.title,first.title);const result=await page.evaluate(()=>{const s=ChatPrototype.inspect();return TaskTitle.rename(s,'过期写入',s.titleMeta.revision-1);});assert(result.error.includes('已更新'));assert.equal((await inspect()).title,first.title);
 });
 await check('rename dialog stays usable on mobile and revoked users cannot view or rename task titles',async()=>{
  await open();await page.locator('#chat-title-input').fill('移动端报表核对');await page.setViewportSize({width:390,height:844});assert(!await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth));await page.screenshot({path:path.join(out,'screenshots/task-title-mobile.png'),fullPage:false});await save('移动端报表核对');await page.setViewportSize({width:1440,height:1000});await open();await page.screenshot({path:path.join(out,'screenshots/task-title-rename.png'),fullPage:false});await page.keyboard.press('Escape');await nav('admin-users');await page.locator('[data-service-field=actor]').selectOption('access');await page.locator('[data-service=select-user][data-id=demo-live-tester]').click();await page.locator('[data-service-field=reason]').fill('验证撤权后标题不可访问');await page.locator('[data-service=toggle-user]').click();await page.locator('[data-service=confirm-toggle]').click();await nav('chat');assert.equal(await page.locator('[data-chat=rename]').count(),0);assert.equal(await page.locator('.chat-problem-heading strong').innerText(),'不可访问的对话');assert(!(await page.locator('.chat-sidebar nav').innerText()).includes('移动端报表核对'));
 });
 await fresh();
};
