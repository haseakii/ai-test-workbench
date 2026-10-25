// Called by browser-check.cjs. These test local presentation, not model intelligence.
const assert=require('node:assert/strict');
const path=require('node:path');
module.exports=async({page,check,out})=>{
 const input=()=>page.locator('#chat-input');
 const send=async text=>{await input().fill(text);await page.locator('[aria-label="发送消息"]').click();await page.waitForSelector('.replying',{state:'hidden'});};
 const click=action=>page.locator('[data-chat="'+action+'"]:visible').first().click();
 await check('chat is the default with no technical dashboard',async()=>{
  assert(await page.locator('#chat-root').isVisible());assert(!await page.locator('#review-root').isVisible());
  assert.equal(await page.locator('#chat-root h1').textContent(),'今天想检查什么？');
  assert(await page.locator('[aria-label="发送消息"]').isDisabled());
  await page.screenshot({path:path.join(out,'screenshots/chat.png'),fullPage:true});
 });
 await check('system selection narrows environments',async()=>{
  await page.locator('#chat-system').selectOption('支付系统');
  assert.deepEqual(await page.locator('#chat-environment option').allTextContents(),['测试环境','联调环境']);
  await page.locator('#chat-environment').selectOption('联调环境');
 });
 await check('quick questions populate composer without sending',async()=>{
  await page.getByRole('button',{name:'排查接口异常',exact:true}).click();assert.equal(await input().inputValue(),'排查接口异常');
  assert.equal(await page.locator('.chat-message').count(),0);assert.equal(await page.evaluate(()=>document.activeElement.id),'chat-input');
 });
 await check('attachment metadata can be added and removed without upload',async()=>{
  await page.locator('#chat-files').setInputFiles({name:'example-log.txt',mimeType:'text/plain',buffer:Buffer.from('synthetic only')});
  assert((await page.locator('.draft-files').innerText()).includes('example-log.txt'));
  assert((await page.locator('#chat-notice').innerText()).includes('未读取内容'));
  await click('remove-file');assert.equal(await page.locator('.draft-files>span').count(),0);
 });
 await check('chat sends text safely and fixes system and environment',async()=>{
  await send('接口异常 <img src=x onerror=alert(1)>');
  assert.equal(await page.locator('.chat-message img').count(),0);
  assert((await page.locator('.assistant').innerText()).includes('支付系统的联调环境'));
  assert(await page.locator('#chat-system').isDisabled());assert(await page.locator('#chat-environment').isDisabled());
 });
 await check('permission and verification stay inside conversation',async()=>{
  await send('今天 10 点，预期成功，实际返回错误');
  assert.equal(await page.locator('.inline-permission').count(),1);assert.equal(await page.locator('.result-choice').count(),0);
  await click('allow');await page.waitForSelector('.result-choice');
  assert((await page.locator('.assistant').last().innerText()).includes('合成结果'));
  assert((await page.locator('.assistant').last().innerText()).includes('请求参数'));
  await click('resolved');assert((await page.locator('.result-choice').innerText()).includes('已确认'));

 });
 await check('new conversations and history preserve previous context',async()=>{
  await click('new');assert.equal(await page.locator('.chat-message').count(),0);assert(!await page.locator('#chat-system').isDisabled());
  await input().fill('保留草稿');await page.locator('[data-chat=session][data-id="1"]').click();
  assert.equal(await page.locator('#chat-system').inputValue(),'支付系统');
  await page.locator('[data-chat=session][data-id="2"]').click();assert.equal(await input().inputValue(),'保留草稿');
 });
 await check('stopping a simulated reply prevents later result',async()=>{
  await page.locator('#chat-model').selectOption('分析模型');await input().press('Enter');
  assert(await page.locator('#chat-model').isDisabled());await click('stop');await page.waitForTimeout(750);
  assert.equal(await page.locator('.assistant').count(),1);assert((await page.locator('.assistant').innerText()).includes('已停止'));
  assert(!await page.locator('#chat-model').isDisabled());
 });
 await check('new context invalidates pending permission',async()=>{
  await send('继续核对报表');await send('补充时间范围');assert.equal(await page.locator('[data-chat=allow]').count(),1);
  await send('改为另外一天');assert.equal(await page.locator('[data-chat=allow]').count(),1);
  assert((await page.locator('.inline-permission').first().innerText()).includes('替代'));
  await click('deny');assert.equal(await page.locator('.result-choice').count(),0);
 });
 await check('shift enter inserts newline and mobile retains new conversation',async()=>{
  await input().fill('第一行');await input().press('Shift+Enter');assert.equal(await input().inputValue(),'第一行\n');
  await page.setViewportSize({width:390,height:844});await click('new');assert(await page.locator('#chat-root h1').isVisible());
  assert(await page.locator('#chat-mobile-history').isVisible());await page.setViewportSize({width:1440,height:1000});
 });
 // Capture a clean review example after interaction assertions, without security-test text.
 await send('帮我核对昨天的报表与订单明细，金额对不上。');
 await send('订单系统测试环境，昨天全天，已完成订单，报表比明细多。');
 await click('allow');await page.waitForSelector('.result-choice');
 await page.screenshot({path:path.join(out,'screenshots/chat-conversation.png'),fullPage:true});
 await page.setViewportSize({width:390,height:844});
 await page.screenshot({path:path.join(out,'screenshots/chat-mobile.png'),fullPage:true});
 await click('new');await page.setViewportSize({width:1440,height:1000});
};
