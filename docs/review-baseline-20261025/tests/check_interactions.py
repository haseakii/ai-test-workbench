import os
import json, traceback
from pathlib import Path
from playwright.sync_api import sync_playwright
BASE=Path(__file__).resolve().parent.parent; results=[]; errors=[]
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH','/usr/bin/chromium'),headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 page=browser.new_page(viewport={'width':1440,'height':1000},accept_downloads=True)
 page.on('pageerror',lambda e:errors.append(str(e)))
 page.set_content((BASE/'index.html').read_text(),wait_until='load')
 def nav(route):
  page.evaluate('(r)=>{location.hash="/"+r;render()}',route);page.wait_for_timeout(40)
 def reset(route='chat'):
  page.evaluate('window.__prototype.reset()');nav(route)
 def state(): return page.evaluate('window.__prototype.getState()')
 def action(a): page.locator(f'[data-action="{a}"]').first.click()
 def check(name,fn):
  try:
   fn();results.append({'name':name,'result':'pass'})
  except Exception as e:
   results.append({'name':name,'result':'fail','detail':str(e)});print('FAIL',name,str(e)[:200]);
   if page.locator('#modal').evaluate('(d)=>d.open'):page.keyboard.press('Escape')
 def assert_(v,msg='断言未通过'):
  if not v:raise AssertionError(msg)
 check('前台上下文 / 执行事件 / 产物切换',lambda:(reset(),page.locator('[data-action="evidence-tab"][data-tab="events"]').click(),assert_('代码定位完成' in page.locator('.side-panel').inner_text()),page.locator('[data-action="evidence-tab"][data-tab="files"]').click(),assert_('差异订单' in page.locator('.side-panel').inner_text())))
 check('补充问题使用脚本响应且不冒充 AI 调用',lambda:(reset(),page.locator('#message-input').fill('只检查已完成订单'),action('send'),assert_('已接收这条演示消息' in page.locator('#conversation').inner_text())))
 check('待补充状态回答后进入运行',lambda:(reset(),page.locator('[name="chatScenario"]').select_option('needs'),action('answer'),assert_(state()['chatState']=='running')))
 check('运行推进到待验证而非已解决',lambda:(reset(),action('advance'),action('advance'),assert_(state()['chatState']=='verifying')))
 check('结果验证后已解决',lambda:(nav('result'),action('resolve'),assert_(state()['chatState']=='resolved')))
 check('重开保留旧结果并创建新一轮',lambda:(action('reopen'),page.locator('#reopen-form [name=reason]').fill('补充数据后仍有差异'),page.locator('#reopen-form button[type=submit]').click(),assert_(state()['round']==2)))
 check('停止请求与停止确认分离',lambda:(reset(),action('stop'),action('stop-request'),assert_(state()['chatState']=='stopping'),action('stop-confirm'),assert_(state()['chatState']=='cancelled')))
 check('失败重试新建执行尝试',lambda:(reset(),page.locator('[name="chatScenario"]').select_option('failed'),action('retry'),assert_(state()['attempt']==2)))
 check('结果未知禁止自动继续',lambda:(reset(),page.locator('[name="chatScenario"]').select_option('unknown'),action('advance'),assert_(state()['chatState']=='unknown')))
 def approve_guard():
  reset('approvals');action('approval-approve');assert_('勾选确认' in page.locator('#approval-error').inner_text());assert_(state()['approval']=='pending')
 check('审批未勾选时阻止批准',approve_guard)
 def approve_case():
  reset('approvals');page.locator('#approval-check').check();action('approval-approve');assert_(state()['approval']=='approved');assert_(state()['tasks'][1]['state']=='queued');assert_(page.locator('[data-action=approval-approve]').count()==0)
 check('批准只入队且不可重复批准',approve_case)
 def after_approval():
  page.evaluate("S.selectedTask='QA-1025-017'");nav('task');action('advance-approved');assert_(state()['tasks'][1]['state']=='running');action('advance-approved');assert_(state()['tasks'][1]['state']=='verifying');action('advance-approved');assert_(state()['tasks'][1]['state']=='resolved')
 check('审批关联任务可推进至待验证再确认',after_approval)
 check('拒绝审批必须填写原因',lambda:(reset('approvals'),action('approval-reject'),assert_('必须填写原因' in page.locator('#approval-error').inner_text()),page.locator('#approval-note').fill('目标记录需要重新核查'),action('approval-reject'),assert_(state()['approval']=='rejected')))
 check('计划变化使旧审批失效',lambda:(reset('approvals'),action('approval-drift'),assert_(page.locator('[data-action=approval-approve]').is_disabled())))
 def node_save():
  reset('editor');page.locator('#node-form [name=name]').fill('检查报表入口与部署提交');page.locator('#node-form button[type=submit]').click();assert_(state()['workflowDraft']);assert_(state()['nodes'][1]['name']=='检查报表入口与部署提交')
 check('流程节点保存草稿',node_save)
 check('流程节点新增与排序',lambda:(action('add-node'),assert_(len(state()['nodes'])==7),action('node-up'),assert_(state()['nodes'][-2]['id']==state()['selectedNode'])))
 check('流程基本校验可见结果',lambda:(reset('editor'),action('validate-flow'),assert_('本地规则校验通过' in page.locator('#flow-validation').inner_text())))
 def flow_publish():
  action('publish-flow');page.locator('#publish-flow-form [name=note]').fill('完善节点边界与输出字段');page.locator('#publish-flow-form [name=checked]').check();page.locator('#publish-flow-form button[type=submit]').click();assert_(state()['workflowVersion']==4)
 check('流程发布新版本与保留发布记录',flow_publish)
 def duplicate_output():
  reset('editor');page.locator('#node-form [name=output]').fill('question_context');page.locator('#node-form button[type=submit]').click();action('validate-flow');assert_('输出变量重复' in page.locator('#flow-validation').inner_text())
 check('重复输出变量被校验拒绝',duplicate_output)
 check('Skill 缺少元信息校验失败',lambda:(reset('skills'),page.locator('#skill-editor').fill('no metadata'),action('validate-skill'),assert_('缺少有效' in page.locator('#skill-validation').inner_text())))
 check('Skill 合法内容保存与发布',lambda:(reset('skills'),action('save-skill'),assert_(state()['skillDraft']),action('publish-skill'),assert_(state()['skillVersion']==3)))
 def model_unconfirmed():
  reset('agents');action('agent-settings');page.locator('#agent-settings-form [name=model]').select_option('快速模型 A');page.locator('#agent-settings-form [name=confirm]').uncheck();page.locator('#agent-settings-form button[type=submit]').click();assert_(state()['modelRequested']=='快速模型 A' and state()['modelConfirmed']=='')
 check('模型请求值与确认值分离',model_unconfirmed)
 check('停用 MCP 阻止后续演示执行',lambda:(reset('mcp'),action('toggle-mcp'),nav('chat'),action('advance'),assert_(state()['step']==2)))
 check('测试角色访问后台显示无权访问',lambda:(reset('guide'),action('role-test-admin'),assert_('无权访问' in page.locator('h1').inner_text())))
 def user_off():
  reset('users');action('toggle-user');nav('login');page.locator('[name=role]').select_option('测试成员');page.locator('#login-form button[type=submit]').click();assert_('账号已停用' in page.locator('#login-error').inner_text())
 check('停用演示用户禁止测试身份登录',user_off)
 def quota_edit():
  reset('quotas');action('quota-config');page.locator('#quota-config-form [name=quota]').fill('180');page.locator('#quota-config-form [name=reason]').fill('扩大试运行范围');page.locator('#quota-config-form button[type=submit]').click();assert_(state()['quota']==180)
 check('额度表单保存有效数值与审计',quota_edit)
 check('搜索无结果显示空状态',lambda:(reset('tasks'),page.locator('#task-search').fill('不存在的案例 xyz'),assert_('没有匹配任务' in page.locator('#task-table').inner_text())))
 def upload_case():
  reset();page.locator('#file-input').set_input_files({'name':'example.txt','mimeType':'text/plain','buffer':b'Local sample only'});assert_(len(state()['pendingAttachments'])==1);action('preview-upload');page.wait_for_timeout(100);assert_('Local sample only' in page.locator('#modal').inner_text());page.keyboard.press('Escape')
 check('本地文件添加与文本预览',upload_case)
 check('禁止不支持的附件格式',lambda:(reset(),page.locator('#file-input').set_input_files({'name':'blocked.exe','mimeType':'application/octet-stream','buffer':b'fake'}),assert_(len(state()['pendingAttachments'])==0)))
 def down():
  reset('result')
  with page.expect_download() as d:action('download-csv')
  f=d.value;assert_('合成示例.csv' in f.suggested_filename)
 check('样例 CSV 可下载并明确为合成数据',down)
 def new_question():
  reset('home');page.locator('#new-message').fill('核查订单 DEMO-001 的状态');action('start-question');assert_(state()['chatState']=='needs');assert_('核查订单 DEMO-001 的状态' in page.locator('.chat-header h2').inner_text())
 check('新问题输入进入补充状态并显示用户目标',new_question)
 check('原生对话框可 Escape 关闭',lambda:(reset(),action('snapshot'),assert_(page.locator('#modal').evaluate('(d)=>d.open')),page.keyboard.press('Escape'),assert_(not page.locator('#modal').evaluate('(d)=>d.open'))))
 reset('chat');page.screenshot(path=str(BASE/'previews'/'chat-final.png'),full_page=True)
 nav('approvals');page.screenshot(path=str(BASE/'previews'/'approvals-final.png'),full_page=True)
 nav('editor');page.screenshot(path=str(BASE/'previews'/'editor-final.png'),full_page=True)
 browser.close()
report={'method':'Chromium 离线 set_content 渲染与真实 DOM 点击；没有真实外部服务调用','cases':results,'pageErrors':errors}
(BASE/'tests'/'interaction_report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({'passed':sum(r['result']=='pass' for r in results),'total':len(results),'pageErrors':errors,'failures':[r for r in results if r['result']!='pass']},ensure_ascii=False,indent=2))
