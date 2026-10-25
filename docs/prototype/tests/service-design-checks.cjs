// Design interaction assertions. These do not prove real protocol, sandbox or authorization enforcement.
const assert=require('node:assert/strict');
const path=require('node:path');
module.exports=async({page,check,nav,out})=>{
 const field=name=>page.locator('[data-service-field="'+name+'"]');
 const click=name=>page.locator('[data-service="'+name+'"]').click();
 await check('management distinguishes configuration, integrations, runtime, context, users and records',async()=>{
  await nav('admin-integrations');assert.equal(await page.locator('.admin-service-nav a').count(),5);
  assert((await page.locator('#configuration-root h1').innerText()).includes('ACP'));
  assert((await page.locator('#configuration-root').innerText()).includes('真实工具能力：未知'));
 });
 await check('connection preflight never upgrades unknown capabilities to verified',async()=>{
  await click('preflight');assert((await page.locator('#service-preflight').innerText()).includes('阻断'));
  await field('scenario').selectOption('normal');await click('preflight');assert((await page.locator('#service-preflight').innerText()).includes('不能真实启动'));
  await field('acpVersion').selectOption('v2-draft');await click('preflight');assert((await page.locator('#service-preflight').innerText()).includes('Draft'));
 });
 await check('MCP schema changes and expired authentication have explicit blocking reasons',async()=>{
  await field('acpVersion').selectOption('v1');
  for(const [scenario,text] of [['schema','schema'],['auth','认证已过期'],['bridge','审批桥接'],['scope','超出允许'],['timeout','禁止新键重做']]){await field('scenario').selectOption(scenario);await click('preflight');assert((await page.locator('#service-preflight').innerText()).includes(text));}
 });
 await check('runtime blocks writes, arbitrary terminal, network and mismatched code versions',async()=>{
  await nav('admin-runtime');await field('writeCode').check();await field('terminal').check();await field('network').selectOption('unrestricted');await field('deployment').fill('demo-commit-b');await click('runtime-check');
  const text=await page.locator('#runtime-result').innerText();for(const term of ['写业务代码','任意终端','任意网络','版本不一致','隔离尚未验证'])assert(text.includes(term));
 });
 await check('context requires valid output reservation and preserves unknown model window',async()=>{
  await nav('admin-context');await field('outputReserve').fill('16000');await click('context-check');assert((await page.locator('#context-result').innerText()).includes('阻断'));
  await field('outputReserve').fill('4000');await click('context-check');assert((await page.locator('#context-result').innerText()).includes('实际发送仍阻断'));
 });
 await check('memory review is required and deletion remains pending receipts',async()=>{
  await click('memory-activate');assert((await page.locator('#service-feedback').innerText()).includes('须先审核'));
  await click('memory-review');await click('memory-activate');assert.equal(await page.locator('#memory-state').innerText(),'active');
  await click('memory-revoke');assert.equal(await page.locator('#memory-state').innerText(),'revoked');await click('memory-delete');assert.equal(await page.locator('#memory-state').innerText(),'deletion_pending');
 });
 await check('configuration administrator cannot manage user identities',async()=>{
  await nav('admin-users');await click('save-user');assert((await page.locator('#service-feedback').innerText()).includes('users.manage'));
 });
 await check('user grant preview rejects missing reason, self elevation and out-of-scope grants',async()=>{
  await field('actor').selectOption('access');await click('save-user');assert((await page.locator('#service-feedback').innerText()).includes('理由'));
  await field('reason').fill('工单 DEMO-100 范围调整');await page.locator('[data-user-scope=payments-test]').check();await click('save-user');assert((await page.locator('#service-feedback').innerText()).includes('超出'));
  await page.locator('[data-user-scope=payments-test]').uncheck();await field('userRole').selectOption('access');await click('save-user');assert((await page.locator('#service-feedback').innerText()).includes('超出'));
  await page.locator('[data-service=select-user][data-id=demo-admin]').click();await click('save-user');assert((await page.locator('#service-feedback').innerText()).includes('自身'));
 });
 await check('suspending a synthetic user requires review and records a new revision',async()=>{
  await page.locator('[data-service=select-user][data-id=demo-tester]').click();await field('reason').fill('工单 DEMO-101 暂停演示用户');await click('toggle-user');assert(await page.locator('#service-dialog').evaluate(x=>x.open));await click('confirm-toggle');
  const user=await page.evaluate(()=>ServiceDesignPrototype.inspect().users.find(x=>x.id==='demo-tester'));assert.equal(user.status,'suspended');assert.equal(user.revision,2);
 });
 await check('valid user grant change applies only after confirmation and advances authorization revision',async()=>{
  await field('userRole').selectOption('observer');await page.locator('[data-user-scope=orders-stage]').check();await field('reason').fill('工单 DEMO-102 调整协作权限');await click('save-user');await click('confirm-save');
  const user=await page.evaluate(()=>ServiceDesignPrototype.inspect().users.find(x=>x.id==='demo-tester'));assert.equal(user.role,'observer');assert(user.scopes.includes('orders-stage'));assert.equal(user.revision,3);assert.equal(user.status,'suspended');
 });
 await check('records metadata is filtered by reviewer role and scope before display',async()=>{
  await nav('admin-records');assert.equal(await page.locator('[data-service=select-record]').count(),0);
  await field('actor').selectOption('support');assert.equal(await page.locator('[data-service=select-record]').count(),2);
  assert(!(await page.locator('#configuration-root').innerText()).includes('不可枚举记录'));assert.equal(await page.locator('.service-sensitive').count(),0);
 });
 await check('record content requires a reason and its own short-lived review lease',async()=>{
  await click('lease');assert((await page.locator('#service-feedback').innerText()).includes('用途理由'));
  await field('leaseReason').fill('工单 DEMO-200 排查异常记录');await click('lease');assert.equal(await page.locator('.service-sensitive').count(),1);
  await click('expire-lease');assert.equal(await page.locator('.service-sensitive').count(),0);
  const audit=await page.evaluate(()=>ServiceDesignPrototype.inspect().audit);assert(audit.some(x=>x.action.includes('正文租约')));
 });
 await check('switching administrator roles revokes content lease',async()=>{
  await field('leaseReason').fill('工单 DEMO-201 再次检查记录');await click('lease');await field('actor').selectOption('config');
  assert.equal(await page.locator('.service-sensitive').count(),0);assert.equal(await page.locator('[data-service=select-record]').count(),0);
 });
 await check('record search does not execute backend work',async()=>{
  await field('actor').selectOption('support');await field('query').fill('接口');await field('query').press('Enter');assert.equal(await page.locator('[data-service=select-record]').count(),1);
  await page.locator('[data-service=select-record]').click();assert((await page.locator('#configuration-root').innerText()).includes('原提交核查中'));
  assert.equal(await page.locator('[data-service=start]').count(),0);
 });
 // Capture clean, explicitly synthetic review surfaces.
 await page.reload();await page.waitForSelector('#configuration-root h1');
 for(const route of ['integrations','runtime','context','users','records']){
  await nav('admin-'+route);if(route==='users')await field('actor').selectOption('access');if(route==='records')await field('actor').selectOption('support');
  await page.screenshot({path:path.join(out,'screenshots','admin-'+route+'.png'),fullPage:true});
 }
 await page.reload();await page.waitForSelector('#configuration-root h1');
};
