// Targeted admission checks for queued offline work. No real Provider is started.
const assert=require('node:assert/strict');
module.exports=async({page,check})=>{
 const queued=async(fn)=>{
  const context=await page.context().browser().newContext(),errors=[],requests=[];
  const url=page.url().split('#')[0];await context.route(/^https?:/,async r=>{if(r.request().url()===url)return r.continue();requests.push(r.request().url());await r.abort();});
  const p=await context.newPage();p.on('pageerror',e=>errors.push(String(e)));
  try{
   await p.clock.install();await p.goto(page.url().split('#')[0]+'#chat');await p.clock.pauseAt(new Date(Date.now()+1000));
   const inspect=()=>p.evaluate(()=>ChatPrototype.inspect());
   for(let i=0;i<2;i++){
    for(const text of ['核对订单报表差异','昨天全天，已完成订单']){await p.locator('#chat-input').fill(text);await p.locator('[aria-label="发送消息"]').click();await p.clock.runFor(700);}
    if(!i)await p.locator('[data-chat=new]:visible').first().click();
   }
   await p.locator('[data-chat=session][data-id="1"]').click();await p.locator('[data-chat=allow]').click();
   await p.locator('[data-chat=session][data-id="2"]').click();await p.locator('[data-chat=allow]').click();
   assert.equal((await inspect()).binding.state,'queued_simulation');assert.equal((await inspect()).latestResultRevision,0);
   await fn(p,inspect);assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);
  }finally{await context.close();}
 };
 await check('merge review: expired queued approval cannot start when a slot becomes free',async()=>queued(async(p,inspect)=>{
  const before=await inspect();await p.clock.setSystemTime(new Date(Date.parse(before.preparation.validUntil)+1));
  await p.locator('[data-chat=session][data-id="1"]').click();await p.locator('[data-chat=stop]').click();await p.clock.runFor(1000);
  await p.locator('[data-chat=session][data-id="2"]').click();const after=await inspect();assert.equal(after.latestResultRevision,0);assert.equal(after.binding.state,'stopped_simulation');assert.equal(after.binding.dispatchedAt,undefined);assert.equal(after.busy,false);assert.equal(after.queued,false);
 }));
 await check('merge review: relevant change invalidates queued work but preserves already active execution',async()=>queued(async(p,inspect)=>{
  await p.evaluate(()=>{const C=ConfigurationPrototype;C.draft().environments[0].description='改变本轮取证口径';const r=C.publish(['environments:orders-test']);if(!r.ok)throw Error(r.errors.join(';'));});
  await p.clock.runFor(2000);const after=await inspect();assert.equal(after.latestResultRevision,0);assert.equal(after.binding.state,'stopped_simulation');assert.equal(after.binding.dispatchedAt,undefined);assert.equal(after.busy,false);
  await p.locator('[data-chat=session][data-id="1"]').click();assert.equal((await inspect()).latestResultRevision,1);
 }));
 await check('merge review: queued work rechecks revised user quota before dispatch',async()=>queued(async(p,inspect)=>{
  await p.evaluate(()=>{const u=ReviewJourney.users.find(x=>x.id==='demo-live-tester'),c=ConfigurationPrototype.current();u.modelQuotas={[ContextQuota.key(c.models[0])]:{...ContextQuota.defaults,dailyTurns:0}};u.revision++;ReviewJourney.userChanged();});
  await p.clock.runFor(2000);assert.equal((await inspect()).latestResultRevision,0);assert.equal((await inspect()).binding.state,'stopped_simulation');assert.equal((await inspect()).binding.dispatchedAt,undefined);assert.equal((await inspect()).busy,false);
 }));
};
