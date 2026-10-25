// Daily lists, progressive details and examples; no real approvals or resource execution.
const assert=require('node:assert/strict');
const path=require('node:path');
module.exports=async({page,check,nav,out})=>{
 const field=name=>page.locator('[data-service-field="'+name+'"]');
 const ops=name=>page.locator('[data-ops="'+name+'"]').click();
 const fresh=async route=>{await nav(route);await page.reload();await page.waitForSelector('#configuration-root h1');};
 const send=async text=>{await page.locator('#chat-input').fill(text);await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});};
 await fresh('admin-approvals');
 await check('approval landing shows a table and sample pending items without opening details',async()=>{
  assert.equal(await field('actor').inputValue(),'approver');assert.equal(await page.locator('.ops-table th').count(),6);assert.equal(await page.locator('[data-ops=select]').count(),3);assert.equal(await page.locator('.ops-drawer').count(),0);assert((await page.locator('.ops-source-label').innerText()).includes('示例'));assert(await page.locator('.admin-operations-nav [aria-current=page]').innerText()==='审批中心');assert.equal(await page.locator('[data-ops=approve]').count(),0);
  await page.screenshot({path:path.join(out,'screenshots/approval-list.png'),fullPage:true});
 });
 await check('approval search environment and status filters include an actionable empty state',async()=>{
  await page.locator('[data-ops-field=query]').fill('林晓');await page.locator('[data-ops-field=query]').press('Enter');assert.equal(await page.locator('[data-ops=select]').count(),1);await page.locator('[data-ops-field=query]').fill('没有此申请');await page.locator('[data-ops-field=query]').press('Enter');assert.equal(await page.locator('[data-ops=select]').count(),0);assert((await page.locator('.ops-empty').innerText()).includes('没有匹配'));await ops('reset');await page.locator('[data-ops-field=scope]').selectOption('orders-stage');assert.equal(await page.locator('[data-ops=select]').count(),2);await page.locator('[data-ops=tab][data-value=pending]').click();assert.equal(await page.locator('[data-ops=select]').count(),1);await page.locator('[data-ops-field=scope]').selectOption('all');
 });
 await check('approval drawer keeps daily content simple and restores focus and entered opinion on close',async()=>{
  await page.locator('[data-ops=select][data-id=sample-approval-1]').click();assert.equal(await page.locator('.ops-drawer').count(),1);assert(!await page.locator('.ops-technical').evaluate(x=>x.open));assert(await page.locator('.admin-sidebar').evaluate(x=>x.inert));await page.screenshot({path:path.join(out,'screenshots/approval-drawer.png'),fullPage:false});await page.locator('[data-ops-field=reason]').fill('这条意见关闭后应保留');await page.keyboard.press('Escape');assert.equal(await page.locator('.ops-drawer').count(),0);assert.equal(await page.evaluate(()=>document.activeElement.dataset.id),'sample-approval-1');await page.locator('[data-ops=select][data-id=sample-approval-1]').click();assert.equal(await page.locator('[data-ops-field=reason]').inputValue(),'这条意见关闭后应保留');await ops('approve');await page.keyboard.press('Escape');assert.equal(await page.evaluate(()=>document.activeElement.dataset.ops),'approve');assert.equal(await page.locator('.ops-drawer').count(),1);await ops('close');
 });
 await check('example decisions update only the example list and never create a conversation execution',async()=>{
  const before=await page.evaluate(()=>ReviewJourney.records());await page.locator('[data-ops=select][data-id=sample-approval-1]').click();await ops('approve');await ops('confirm-approve');assert.equal(await page.locator('[data-ops=select][data-id=sample-approval-1]').count(),0);assert.deepEqual(await page.evaluate(()=>ReviewJourney.records()),before);await page.locator('[data-ops=tab][data-value=processed]').click();assert.equal(await page.locator('[data-ops=select][data-id=sample-approval-1]').count(),1);await page.locator('[data-ops-field=source]').selectOption('live');assert.equal(await page.locator('[data-ops=select]').count(),0);
 });
 await check('list examples and approval actions remain restricted by the selected review role',async()=>{
  await page.locator('[data-ops-field=source]').selectOption('examples');await field('actor').selectOption('config');assert.equal(await page.locator('[data-ops=select]').count(),0);assert((await page.locator('.ops-empty').innerText()).includes('无权'));await field('actor').selectOption('support');await page.locator('[data-ops=tab][data-value=pending]').click();await page.locator('[data-ops=select]').first().click();assert(await page.locator('[data-ops=approve]').isDisabled());await ops('close');
 });
 await check('members and records start as lists and open settings only for a selected row',async()=>{
  await nav('admin-users');await field('actor').selectOption('access');assert.equal(await page.locator('.daily-drawer').count(),0);assert.equal(await page.locator('[data-service=select-user]').count(),5);assert.equal(await field('userExecutionMode').count(),0);await page.screenshot({path:path.join(out,'screenshots/member-list.png'),fullPage:true});await page.locator('[data-service=select-user][data-id=demo-live-tester]').click();assert.equal(await field('userExecutionMode').count(),1);await page.keyboard.press('Escape');assert.equal(await page.locator('.daily-drawer').count(),0);assert.equal(await page.evaluate(()=>document.activeElement.dataset.id),'demo-live-tester');await nav('admin-records');await field('actor').selectOption('support');assert.equal(await page.locator('.daily-drawer').count(),0);assert.equal(await field('leaseReason').count(),0);await page.locator('[data-service=select-record]').first().click();assert.equal(await page.locator('.service-sensitive').count(),0);assert(!await page.locator('.ops-technical').evaluate(x=>x.open));await page.keyboard.press('Escape');assert.equal(await page.locator('.daily-drawer').count(),0);
 });
 await fresh('admin-approvals');
 await page.evaluate(()=>{const u=ReviewJourney.users.find(x=>x.id==='demo-live-tester');u.executionMode='approval';u.revision++;ReviewJourney.userChanged();});
 let oldContext,id;
 await check('approval history rows retain their own frozen plan and cannot approve an older context',async()=>{
  await nav('chat');await send('核对报表差异');await send('昨天全天');const first=await page.evaluate(()=>ChatPrototype.inspect());oldContext=first.preparation.id;id='demo-chat-'+first.id;await nav('admin-approvals');await page.locator('[data-ops=select][data-id="'+id+'"]').click();await ops('approve');await ops('confirm-approve');await page.waitForFunction(()=>ChatPrototype.inspect().latestResultRevision===1);await nav('chat');await send('补充另一天继续检查');await nav('admin-approvals');await page.locator('[data-ops=tab][data-value=all]').click();assert.equal(await page.locator('[data-ops=select][data-id="'+id+'"]').count(),2);await page.locator('[data-ops=select][data-key="'+id+'::'+oldContext+'"]').click();await page.locator('.ops-technical summary').click();assert((await page.locator('.ops-technical').innerText()).includes(oldContext));assert.equal(await page.locator('[data-ops=approve]').count(),0);await ops('close');
 });
 await check('daily lists and fixed drawer actions fit desktop tablet and mobile viewports',async()=>{
  await page.locator('[data-ops-field=source]').selectOption('examples');await page.locator('[data-ops=tab][data-value=pending]').click();for(const [width,height] of [[1440,1000],[1366,768],[1024,900],[390,844]]){
   await page.setViewportSize({width,height});assert(!await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth));await page.locator('[data-ops=select]').first().click();const footer=await page.locator('.ops-drawer-footer').boundingBox();assert(footer.y>=0&&footer.y+footer.height<=height+1);assert(!await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth));if(width===390)await page.screenshot({path:path.join(out,'screenshots/approval-mobile.png'),fullPage:false});await page.keyboard.press('Escape');
  }await page.setViewportSize({width:1440,height:1000});
 });
 await fresh('admin-executions');
 await check('execution landing uses a summary list with details behind one action',async()=>{
  assert.equal(await field('actor').inputValue(),'operator');assert.equal(await page.locator('.ops-drawer').count(),0);assert.equal(await page.locator('.ops-table th').count(),6);await page.screenshot({path:path.join(out,'screenshots/execution-list.png'),fullPage:true});await page.locator('[data-ops=select]').first().click();assert(!await page.locator('.ops-technical').evaluate(x=>x.open));assert(await page.locator('[data-ops=stop]').isDisabled());await ops('close');
 });
 await nav('chat');await page.reload();await page.waitForSelector('#chat-root h1');
};
