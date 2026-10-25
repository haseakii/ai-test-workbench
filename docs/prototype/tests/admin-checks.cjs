// Offline configuration interaction; not production identity, resource enforcement or Provider acceptance.
const assert=require('node:assert/strict');
const path=require('node:path');
module.exports=async({page,check,nav,out})=>{
 const field=name=>page.locator('#configuration-root [data-field="'+name+'"]');
 const action=name=>page.locator('[data-admin="'+name+'"]').click();
 const config=()=>page.evaluate(()=>ConfigurationPrototype.current());
 const chat=()=>page.evaluate(()=>ChatPrototype.inspect());
 const apply=async()=>{await action('publish');await page.waitForSelector('#admin-dialog[open]');await action('confirm');};
 const select=async id=>page.locator('[data-record="'+id+'"]').click();
 await check('management entry shows four configuration sections, with secondary diagnostics',async()=>{
  await page.locator('.chat-sidebar-bottom a').click();await page.waitForSelector('#configuration-root h1');
  assert.equal(await page.locator('#configuration-root h1').textContent(),'系统配置');
  assert.equal(await page.locator('.admin-config-nav a').count(),4);
  assert(!await page.locator('#review-root').isVisible());
 });
 await check('configuration edits remain draft across navigation and do not affect chat',async()=>{
  await field('name').fill('订单检查系统');await nav('admin-environments');await nav('admin-systems');
  assert.equal(await field('name').inputValue(),'订单检查系统');await nav('chat');
  assert((await page.locator('#chat-system option').allTextContents()).includes('订单系统'));
  assert(!(await page.locator('#chat-system option').allTextContents()).includes('订单检查系统'));
 });
 await check('preview dialog can cancel with focus restoration before applying',async()=>{
  await nav('admin-systems');await action('publish');assert((await page.locator('#admin-dialog').innerText()).includes('订单检查系统'));
  await page.keyboard.press('Escape');assert.equal((await config()).revision,1);
  assert.equal(await page.evaluate(()=>document.activeElement.dataset.admin),'publish');
  await apply();assert.equal((await config()).revision,2);await nav('chat');
  assert((await page.locator('#chat-system option').allTextContents()).includes('订单检查系统'));
 });
 await check('duplicate names block publication and preserve input',async()=>{
  await nav('admin-systems');await field('name').fill('支付系统');await action('publish');
  assert((await page.locator('#admin-feedback').innerText()).includes('名称重复'));assert.equal(await field('name').inputValue(),'支付系统');
  assert.equal((await config()).revision,2);await action('discard');await action('confirm-discard');
 });
 await check('new records start disabled and whole-draft discard restores selection',async()=>{
  await action('add');assert(!await field('enabled').isChecked());await field('name').fill('合成库存系统');
  await nav('admin-models');await action('add');assert(!await field('enabled').isChecked());
  await action('discard');await action('confirm-discard');assert.equal((await config()).systems.length,3);
 });
 await check('disabled default model fails validation without changing applied config',async()=>{
  await nav('admin-models');await select('general');await field('enabled').uncheck();await action('publish');
  assert((await page.locator('#admin-feedback').innerText()).includes('默认模型未启用'));assert((await config()).models[0].enabled);
  await action('discard');await action('confirm-discard');
 });
 await check('role preview limits user systems, environments, models and attachments',async()=>{
  await nav('admin-permissions');await page.locator('#admin-preview-role').selectOption('observer');await nav('chat');
  assert.deepEqual(await page.locator('#chat-system option').allTextContents(),['订单检查系统']);
  assert.deepEqual(await page.locator('#chat-environment option').allTextContents(),['测试环境']);
  assert.deepEqual(await page.locator('#chat-model option').allTextContents(),['默认模型']);
  assert(await page.locator('[data-chat=attach]').isDisabled());
  await nav('admin-permissions');await page.locator('#admin-preview-role').selectOption('tester');
 });
 await check('model mapping changes do not overwrite existing conversation snapshot',async()=>{
  await nav('chat');await page.locator('#chat-system').selectOption('支付系统');
  await page.locator('#chat-input').fill('检查接口异常');await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});
  const before=await chat();assert.equal(before.snapshot.models[0].provider,'control');
  await nav('admin-models');await select('general');await field('provider').selectOption('direct');await apply();
  assert.equal((await config()).models[0].provider,'direct');assert.deepEqual((await chat()).snapshot,before.snapshot);
 });
 await check('revoking an environment blocks continuation and preserves the original scope',async()=>{
  await nav('admin-permissions');await select('tester');await page.locator('[data-grant=environments][value=payments-test]').uncheck();await apply();await nav('chat');
  assert((await page.locator('#chat-notice').innerText()).includes('权限范围'));
  await page.locator('#chat-input').fill('继续检查');assert(await page.locator('[aria-label="发送消息"]').isDisabled());
  assert.equal((await chat()).environmentId,'payments-test');assert.equal((await chat()).draft,'继续检查');
 });
 await check('restoring scope does not silently rebind or discard old messages',async()=>{
  await nav('admin-permissions');await page.locator('[data-grant=environments][value=payments-test]').check();await apply();await nav('chat');
  assert.equal((await chat()).snapshot.models[0].provider,'control');assert(await page.locator('.assistant').count()>0);
  await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.inline-permission');
 });
 await check('published configuration invalidates a pending permission card',async()=>{
  await nav('admin-systems');await select('orders');await field('description').fill('新的合成说明');await apply();await nav('chat');
  assert.equal(await page.locator('[data-chat=allow]').count(),0);
  assert((await page.locator('.inline-permission').innerText()).includes('配置已更新'));
 });
 await check('empty grants produce an explicit empty user state and no send',async()=>{
  await nav('admin-permissions');await select('observer');
  await page.locator('[data-grant=environments][value=orders-test]').uncheck();await apply();
  await page.locator('#admin-preview-role').selectOption('observer');await nav('chat');
  assert.equal(await page.locator('#chat-root h1').textContent(),'暂无可用的检查范围');
  await page.locator('#chat-input').fill('不能发送');assert(await page.locator('[aria-label="发送消息"]').isDisabled());
 });
 await check('configuration audit records changes and reload restores synthetic defaults',async()=>{
  await nav('admin-systems');await page.locator('.admin-audit summary').click();
  assert((await page.locator('.admin-audit').innerText()).includes('合成配置管理员'));
  await page.reload();await page.waitForSelector('#configuration-root h1');assert.equal((await config()).revision,1);
  assert.equal((await config()).models[0].provider,'control');
 });
 await check('new system and environment require an explicit role grant before appearing',async()=>{
  await nav('admin-systems');await action('add');await field('name').fill('库存系统');await field('enabled').check();
  await nav('admin-environments');await action('add');await field('system').selectOption('demo-systems-4');await field('name').fill('库存测试');await field('enabled').check();
  await apply();await nav('chat');assert(!(await page.locator('#chat-system option').allTextContents()).includes('库存系统'));
  await nav('admin-permissions');await select('tester');await page.locator('[data-grant=environments][value=demo-environments-7]').check();await apply();await nav('chat');
  await page.locator('#chat-system').selectOption('库存系统');assert.deepEqual(await page.locator('#chat-environment option').allTextContents(),['库存测试']);
 });
 await check('environment default model is selected on a new conversation scope',async()=>{
  await nav('admin-environments');await select('payments-test');await field('defaultModel').selectOption('analysis');await apply();await nav('chat');
  await page.locator('#chat-system').selectOption('支付系统');assert.equal(await page.locator('#chat-model').inputValue(),'分析模型');
  await page.locator('#chat-input').fill('支付检查');await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});
  assert.equal((await chat()).modelId,'analysis');
 });
 await check('reparenting an environment never silently changes an existing conversation system',async()=>{
  await nav('admin-environments');await select('payments-test');await field('name').fill('支付迁移测试');await field('system').selectOption('reports');await apply();await nav('chat');
  assert.equal((await chat()).systemId,'payments');assert((await page.locator('#chat-notice').innerText()).includes('权限范围'));
 });
 await check('invalid limit blocks apply and keeps the number for correction',async()=>{
  await nav('admin-permissions');await select('tester');await field('maxCalls').fill('0');await action('publish');
  assert((await page.locator('#admin-feedback').innerText()).includes('1–100'));assert.equal(await field('maxCalls').inputValue(),'0');
  await page.reload();await page.waitForSelector('#configuration-root h1');
 });
 for(const route of ['systems','environments','models','permissions']){
  await nav('admin-'+route);await page.screenshot({path:path.join(out,'screenshots','admin-'+route+'.png'),fullPage:true});
 }
};
