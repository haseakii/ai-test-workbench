import {test,expect} from '@playwright/test';
import {createHash} from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

const evidence=path.resolve('../docs/reviews/implementation-d0-d2');
const csv=(amounts:string[])=>Buffer.from('order_id,amount,currency,status,occurred_at\r\n'+amounts.map((a,i)=>'O'+String(i+1).padStart(3,'0')+','+a+',EUR,completed,2026-10-25T12:00:00Z').join('\r\n')+'\r\n');

test('正式界面完成配置开通审批计算下载与用户确认',async({page,request})=>{
 const outside:string[]=[];const errors:string[]=[];
 await page.route('**/*',async route=>{const url=route.request().url();if(/^https?:/.test(url)&&new URL(url).hostname!=='127.0.0.1'){outside.push(url);await route.abort();}else await route.continue();});
 page.on('pageerror',e=>errors.push(e.message));
 const suffix=Date.now().toString();const environment='UI订单测试-'+suffix;const title='UI差异-'+suffix;
 await fs.mkdir(path.join(evidence,'screenshots'),{recursive:true});
 await page.goto('/');
 await page.getByLabel('测试身份').selectOption('pending');
 const initialMembership=await (await request.get('/api/session',{headers:{'X-Test-Subject':'pending'}})).json();
 let requestSubmitted=false;
 if(initialMembership.state!=='active'){
  await expect(page.getByRole('heading',{name:'等待分配可用环境'})).toBeVisible();
  await page.getByRole('button',{name:'提交开通申请'}).click();
  await expect(page.getByRole('status')).toContainText('申请已保存');requestSubmitted=true;
 }

 await test.step('管理员创建并显式发布配置',async()=>{
  await page.getByLabel('测试身份').selectOption('admin');
  await page.getByRole('button',{name:'系统设置',exact:true}).click();
  await page.getByRole('button',{name:'新建测试环境',exact:true}).click();
  await page.getByLabel('环境名称').fill(environment);
  await page.getByRole('button',{name:'保存草稿',exact:true}).click();
  const publish=page.getByRole('dialog',{name:'发布变更集'});await expect(publish).toBeVisible();
  await expect(publish.locator('li')).toHaveCount(3);
  await publish.getByRole('button',{name:'一起发布所选 3 项'}).click();
  await expect(publish).toBeHidden();
  await expect(page.locator('tr').filter({hasText:environment})).toBeVisible();
 });
 await test.step('开通测试成员并分配负责人环境权限',async()=>{
  await page.getByRole('button',{name:'成员与权限',exact:true}).click();
  await page.getByLabel('授权成员').selectOption('pending');
  await page.getByLabel('授权环境').selectOption({label:environment});
  await page.getByLabel('授权角色').selectOption('member');
  await page.getByRole('button',{name:'开通并授权'}).click();
  await expect(page.getByRole('status')).toContainText('授权已保存');
  await page.getByLabel('授权成员').selectOption('reviewer');
  await page.getByLabel('授权角色').selectOption('reviewer');
  await page.getByRole('button',{name:'开通并授权'}).click();
  await expect(page.getByRole('status')).toContainText('授权已保存');
 });
 await test.step('成员保存问题和真实上传两份CSV',async()=>{
  await page.getByLabel('测试身份').selectOption('pending');
  await page.getByLabel('所属环境').selectOption({label:environment});
  await page.getByRole('button',{name:'新建问题',exact:true}).click();
  await page.getByLabel('问题描述').fill(title);
  await page.getByRole('button',{name:'保存消息',exact:true}).click();
  await expect(page.locator('.message p')).toContainText(title);
  await page.locator('input[type=file]').setInputFiles({name:'detail.csv',mimeType:'text/csv',buffer:csv(['100.00','200.00','50.00'])});
  await expect(page.locator('.file-row').filter({hasText:'detail.csv'})).toBeVisible();
  await page.locator('input[type=file]').setInputFiles({name:'report.csv',mimeType:'text/csv',buffer:csv(['100.00','190.00','70.00'])});
  await expect(page.locator('.file-row').filter({hasText:'report.csv'})).toBeVisible();
  await page.getByRole('button',{name:'准备检查',exact:true}).click();
  await page.getByLabel('明细材料').selectOption({label:'detail.csv'});
  await page.getByLabel('报表材料').selectOption({label:'report.csv'});
  await page.getByRole('button',{name:'生成固定计划',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'确认检查范围'})).toBeHidden();
  await expect(page.locator('.result-card .badge')).toHaveText('待审批');
 });
 await test.step('负责人从列表审阅固定计划并批准',async()=>{
  await page.getByLabel('测试身份').selectOption('reviewer');
  await page.getByRole('button',{name:'审批中心',exact:true}).click();
  await page.locator('tr').filter({hasText:title}).getByRole('button',{name:'查看计划'}).click();
  const dialog=page.getByRole('dialog',{name:'审批计划'});
  await expect(dialog).toContainText('EUR · completed');
  await dialog.getByText('固定计划与输入摘要',{exact:true}).click();
  await expect(dialog.locator('pre')).toContainText('detailAttachmentIdHash');
  await dialog.getByLabel('审批意见').fill('已核对固定范围和合成材料');
  await dialog.getByLabel('我已核对以上固定范围和材料').check();
  await page.screenshot({path:path.join(evidence,'screenshots','ui-approval.png'),fullPage:true});
  await dialog.getByRole('button',{name:'批准本次计划'}).click();
  await expect(dialog).toBeHidden();
 });
 let conversationId='';let artifactId='';let csvSha256='';
 await test.step('成员下载实际差异文件并确认当前结果',async()=>{
  await page.getByLabel('测试身份').selectOption('pending');
  await page.locator('.recent-item').filter({hasText:title}).click();
  await expect(page.getByRole('heading',{name:'差异结果已就绪'})).toBeVisible();
  await expect(page.locator('.delivery .metrics strong')).toHaveText(['2','10.00']);
  await page.screenshot({path:path.join(evidence,'screenshots','ui-delivery.png'),fullPage:true});
  const downloaded=page.waitForEvent('download');await page.getByRole('button',{name:'下载差异 CSV'}).click();
  const download=await downloaded;const file=path.join(evidence,'ui-difference.csv');await download.saveAs(file);
  const bytes=await fs.readFile(file);csvSha256=createHash('sha256').update(bytes).digest('hex');expect(bytes.toString()).toContain('-10.00');expect(bytes.toString()).toContain('20.00');
  const list=await (await request.get('/api/conversations',{headers:{'X-Test-Subject':'pending'}})).json();
  conversationId=list.find((c:any)=>c.title===title).id;
  const state=await (await request.get('/api/conversations/'+conversationId,{headers:{'X-Test-Subject':'pending'}})).json();
  artifactId=state.artifacts[0].id;expect(csvSha256).toBe(state.artifacts[0].sha256);
  await page.getByLabel('验证说明').fill('已复算两条差异，确认本次结果解决问题');
  await page.getByRole('button',{name:'确认已解决',exact:true}).click();
  await expect(page.locator('.chat-head .badge')).toHaveText('已解决');
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:path.join(evidence,'screenshots','ui-mobile.png'),fullPage:true});
  await page.setViewportSize({width:1440,height:1000});
 });
 await test.step('管理员按同一问题查元数据且无正文权限',async()=>{
  await page.getByLabel('测试身份').selectOption('admin');
  await page.getByRole('button',{name:'执行管理',exact:true}).click();
  await page.getByLabel('筛选问题标题').fill(title);await page.getByRole('button',{name:'筛选列表',exact:true}).click();
  await expect(page.locator('tr').filter({hasText:title})).toContainText('已结束');
  await page.getByRole('button',{name:'用户记录',exact:true}).click();
  await page.getByLabel('筛选问题标题').fill(title);await page.getByRole('button',{name:'筛选列表',exact:true}).click();
  await expect(page.locator('tr').filter({hasText:title})).toContainText('已解决');
  const denied=await request.get('/api/conversations/'+conversationId,{headers:{'X-Test-Subject':'admin'}});expect(denied.status()).toBe(404);
 });
 expect(outside).toEqual([]);expect(errors).toEqual([]);
 await fs.writeFile(path.join(evidence,'ui-journey.json'),JSON.stringify({scope:'actual local service and MySQL with synthetic identities/backend/CSV',environment,conversationId,artifactId,csvSha256,differenceCount:2,totalDifference:'10.00',initialMembershipState:initialMembership.state,requestSubmitted,outsideRequests:outside,pageErrors:errors,viewportWidths:[1440,390]},null,2)+'\n');
});

test('配置弹窗支持Escape和焦点返回',async({page})=>{
 await page.goto('/');await page.getByLabel('测试身份').selectOption('admin');
 await page.getByRole('button',{name:'系统设置',exact:true}).click();
 const trigger=page.getByRole('button',{name:'新建测试环境',exact:true});await trigger.click();
 await expect(page.getByRole('dialog',{name:'新建测试环境'})).toBeVisible();
 await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).toHaveCount(0);await expect(trigger).toBeFocused();
});

test('身份切换清除正文且他环境权限不能授权他人结果',async({page,request})=>{
 const evidenceFile=JSON.parse(await fs.readFile(path.join(evidence,'ui-journey.json'),'utf8'));
 const source=await request.get('/api/conversations/'+evidenceFile.conversationId,{headers:{'X-Test-Subject':'pending'}});expect(source.status()).toBe(200);const own=await source.json();
 await page.goto('/');await page.getByLabel('测试身份').selectOption('pending');await page.locator('.recent-item').filter({hasText:own.title}).click();await expect(page.locator('.message')).toHaveCount(own.messages.length);
 await page.getByLabel('测试身份').selectOption('bob');await expect(page.locator('.message')).toHaveCount(0);await expect(page.locator('.recent-item').filter({hasText:own.title})).toHaveCount(0);
 const response=await request.get('/api/workbench/options',{headers:{'X-Test-Subject':'bob'}});expect(response.status()).toBe(200);const options=await response.json();expect(options.some((e:any)=>e.id===own.environmentId)).toBe(false);
 // Bob can legitimately own other synthetic environments from the ACL matrix.
 // Their existence must not weaken the original conversation/file/event boundary.
 if(options.length)await expect(page.getByRole('button',{name:'新建问题',exact:true})).toBeEnabled();else await expect(page.getByRole('button',{name:'新建问题',exact:true})).toBeDisabled();
 for(const path of ['/api/artifacts/'+evidenceFile.artifactId+'/download','/api/conversations/'+evidenceFile.conversationId+'/events','/api/conversations/'+evidenceFile.conversationId+'/handover'])expect((await request.get(path,{headers:{'X-Test-Subject':'bob'}})).status()).toBe(404);
});
