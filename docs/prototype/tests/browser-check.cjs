// Local file:// and DOM checks only. No application service or real execution backend.
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const assert = require('node:assert/strict');
const {createHash} = require('node:crypto');
const {chromium} = require('playwright');
const root=path.resolve(__dirname,'../../..');
const file=path.join(root,'docs/prototype/index.html');
const out=path.join(root,'docs/reviews/initial-design-checks');
const report={time:new Date().toISOString(),scope:'offline synthetic prototype, not real Provider acceptance',method:'Playwright on local file:// plus DOM actions',cases:[],pageErrors:[],networkAttempts:[],layouts:[]};
report.sourceHashes=Object.fromEntries(['docs/prototype/index.html','docs/prototype/model.js','docs/prototype/app.js','docs/prototype/journey.js','docs/prototype/tests/walkthrough-checks.cjs','docs/prototype/service-design.js','docs/prototype/service-design.css','docs/prototype/tests/service-design-checks.cjs','docs/prototype/configuration.js','docs/prototype/admin.js','docs/prototype/admin.css','docs/prototype/tests/admin-checks.cjs','docs/prototype/chat.js','docs/prototype/chat.css','docs/prototype/tests/chat-checks.cjs','docs/prototype/style.css','docs/prototype/shell.html','docs/design/contracts/state-transitions.json','docs/prototype/tests/browser-check.cjs'].map(rel=>[rel,createHash('sha256').update(fs.readFileSync(path.join(root,rel))).digest('hex')]));
(async()=>{
 fs.mkdirSync(path.join(out,'screenshots'),{recursive:true});
 const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{})});
 report.browser=browser.version();
 const context=await browser.newContext({viewport:{width:1440,height:1000},acceptDownloads:true});
 await context.route(/^https?:/,async r=>{report.networkAttempts.push(r.request().url());await r.abort();});
 const page=await context.newPage();page.setDefaultTimeout(5000);
 page.on('pageerror',e=>report.pageErrors.push(String(e)));
 const nav=async route=>{await page.evaluate(r=>location.hash=r,route);await page.waitForTimeout(50);};
 const state=()=>page.evaluate(()=>window.__reviewPrototype.getState());
 const action=name=>page.locator('[data-action="'+name+'"]').click();
 const load=async name=>{await page.locator('#scenario').selectOption(name);};
 const conditions=async()=>{await nav('workbench');await page.locator('#conditions').fill('合成报表，UTC+8，已完成订单，EUR');await action('conditions');};
 const approve=async()=>{await page.locator('#approval-check').check();await action('approve');};
 async function check(name,fn){try{await fn();report.cases.push({name,result:'pass'});}catch(e){report.cases.push({name,result:'fail',detail:String(e)});console.log('FAIL '+name+' '+String(e).slice(0,300));}}
 try{
 await page.goto(pathToFileURL(file).href);await page.waitForSelector('#chat-root h1');
 await require('./chat-checks.cjs')({page,check,out});
 await require('./admin-checks.cjs')({page,check,nav,out});
 await require('./service-design-checks.cjs')({page,check,nav,out});
 await require('./walkthrough-checks.cjs')({page,check,nav,out});
 await nav('workbench');
 await check('actual file navigation loads self-contained prototype',async()=>{assert.equal(await page.locator('#review-root h1').textContent(),'闭环演练');assert((await page.locator('body').innerText()).includes('离线演示'));});
 await check('empty conditions block with visible feedback',async()=>{await action('conditions');assert.equal((await state()).run,'draft');assert((await page.locator('#feedback').textContent()).includes('操作被阻止'));});
 await check('normal execution awaits explicit user verification',async()=>{await conditions();await action('start');await action('complete');assert.equal((await state()).issue,'awaiting_verification');await nav('result');await action('verify');assert.equal((await state()).issue,'resolved');});
 await check('synthetic CSV download includes clear provenance and deterministic difference',async()=>{const promise=page.waitForEvent('download');await page.locator('[data-ui=download-sample]').click();const d=await promise;const target=path.join(out,'synthetic-difference.csv');await d.saveAs(target);const text=fs.readFileSync(target,'utf8');assert(text.includes('2530'));assert(text.includes('非业务结果'));assert(d.suggestedFilename().includes('合成'));});
 await check('reopen preserves prior result and verifier',async()=>{await action('new_round');const s=await state();assert.equal(s.round,2);assert.equal(s.results.length,1);assert.equal(s.results[0].verifiedBy,'合成提交人');});
 await check('not_found retains original owner and disabled replacement',async()=>{await load('timeout');await nav('binding');await action('lookup_not_found');assert.equal((await state()).binding.state,'acceptance_unknown');assert(await page.locator('[data-action=new_round]').isDisabled());});
 await check('original receipt recovery does not resubmit',async()=>{await action('lookup_found');assert.equal((await state()).startCommands,1);assert.equal((await state()).binding.id,'demo-binding-1');});
 await check('changing default does not rebind active execution',async()=>{const before=(await state()).binding;await nav('connections');await page.locator('[data-default=direct]').click();assert.deepEqual((await state()).binding,before);assert.equal((await state()).defaultProvider,'direct');});
 await check('final fence precedes a new binding',async()=>{await load('timeout');await nav('binding');await action('close_final');assert((await state()).binding.admissionFence);await action('new_round');assert.equal((await state()).binding.id,'demo-binding-2');});
 await check('cancel request and acknowledgement remain separate',async()=>{await load('normal');await conditions();await action('start');await action('cancel');assert.equal((await state()).run,'stopping');await action('confirm_cancel');assert.equal((await state()).run,'cancelled');});
 await check('approval checkbox guard and decision/delivery separation',async()=>{await load('approval');await nav('approval');await action('approve');assert.equal((await state()).approval.decision,'pending');await approve();const s=await state();assert.equal(s.approval.delivery,'ready');assert.equal(s.operationsExecuted,0);});
 await check('failed approval preserves entered review comment',async()=>{await load('approval');await page.locator('#approval-comment').fill('合成审批意见应保留');await action('approve');assert.equal(await page.locator('#approval-comment').inputValue(),'合成审批意见应保留');assert.equal((await state()).approval.decision,'pending');await approve();});
 await check('native expiry renews request and resets human confirmation',async()=>{const original=await state();await action('expire_native');assert(await page.locator('[data-action=send_permission]').isDisabled());await action('prepare_new_permission');const s=await state();assert.equal(s.binding.id,original.binding.id);assert.notEqual(s.approval.requestId,original.approval.requestId);assert.equal(s.approvalHistory.length,1);assert(!await page.locator('#approval-check').isChecked());assert.equal(s.approval.decision,'pending');});
 await check('renewed approval delivers once and awaits operation receipt',async()=>{await approve();await action('send_permission');assert.equal((await state()).operationsExecuted,0);await action('operation_receipt');assert.equal((await state()).operationsExecuted,1);assert(await page.locator('[data-action=operation_receipt]').isDisabled());});
 await check('unknown delivery disables resend and resolves original receipt',async()=>{await load('approval');await approve();await action('send_timeout');assert.equal((await state()).approval.delivery,'delivery_unknown');assert(await page.locator('[data-action=send_permission]').isDisabled());await action('lookup_delivery');assert.equal((await state()).permissionSends,1);});
 await check('unsupported bridge is visible and send disabled',async()=>{await load('bridge');await approve();assert(await page.locator('[data-action=send_permission]').isDisabled());assert((await page.locator('#content').innerText()).includes('不支持完整审批桥接'));});
 await check('capability and budget failures leave dispatch count zero',async()=>{for(const name of ['mismatch','unknownCapability','usage']){await load(name);await conditions();await action('start');assert.equal((await state()).startCommands,0);assert((await page.locator('#feedback').textContent()).includes('操作被阻止'));}});
 await check('bad evidence cannot produce successful result',async()=>{await load('evidence');await conditions();await action('start');await action('complete');assert.equal((await state()).results.length,0);assert.notEqual((await state()).run,'succeeded');});
 await check('bad artifact blocks sample download',async()=>{await load('artifact');await nav('result');await page.locator('[data-ui=download-sample]').click();assert((await page.locator('#feedback').textContent()).includes('下载被阻止'));});
 await check('handoff export preserves unresolved ownership and provenance',async()=>{await load('timeout');await nav('exceptions');await action('handoff');const promise=page.waitForEvent('download');await page.locator('[data-ui=download-handoff]').click();const d=await promise;const target=path.join(out,'synthetic-handoff.json');await d.saveAs(target);const data=JSON.parse(fs.readFileSync(target,'utf8'));assert.equal(data.state.binding.ownerOccupied,true);assert(data.provenance.includes('synthetic'));});
 await check('state explorer exposes all five layers',async()=>{await nav('contract');for(const layer of ['issue','run','node','binding','approval']){await page.locator('#state-layer').selectOption(layer);assert(await page.locator('tbody tr').count()>0);}});
 await check('dialog Escape closes and returns focus',async()=>{await page.locator('[data-ui=reset]').click();assert(await page.locator('#reset-dialog').evaluate(d=>d.open));await page.keyboard.press('Escape');await page.waitForTimeout(30);assert(!await page.locator('#reset-dialog').evaluate(d=>d.open));assert.equal(await page.evaluate(()=>document.activeElement.dataset.ui),'reset');});
 await check('refresh explicitly resets in-memory simulation',async()=>{await page.reload();await page.waitForSelector('#review-root h1');assert.equal((await state()).round,1);assert.equal((await state()).run,'draft');assert((await page.locator('body').innerText()).includes('刷新将清空'));});
 const routes=['admin-integrations','admin-runtime','admin-context','admin-users','admin-records','chat','admin-systems','admin-environments','admin-models','admin-permissions','workbench','result','connections','binding','approval','contract','exceptions'];
 await load('approval');
 for(const [width,height] of [[1440,1000],[1366,768],[1024,900],[390,844]]){
  await page.setViewportSize({width,height});
  for(const route of routes){await nav(route);const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);report.layouts.push({route,width,height,bodyHorizontalOverflow:overflow});if(width===1440&&['chat','workbench','connections','approval','binding'].includes(route))await page.screenshot({path:path.join(out,'screenshots',route+'.png'),fullPage:true});if(width===390&&route==='admin-systems')await page.screenshot({path:path.join(out,'screenshots','admin-mobile.png'),fullPage:true});}
 }
 }finally{report.passed=report.cases.filter(x=>x.result==='pass').length;report.total=report.cases.length;report.overflows=report.layouts.filter(x=>x.bodyHorizontalOverflow);fs.writeFileSync(path.join(out,'browser-report.json'),JSON.stringify(report,null,2));await browser.close();}
 console.log(JSON.stringify({passed:report.passed,total:report.total,layouts:report.layouts.length,overflows:report.overflows.length,pageErrors:report.pageErrors,networkAttempts:report.networkAttempts,browser:report.browser},null,2));
 if(report.passed!==report.total||report.pageErrors.length||report.networkAttempts.length||report.overflows.length)process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
