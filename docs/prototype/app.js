/* Presentation for the offline simulator; no persistence, fetch or resource execution. */
(() => {
  'use strict';
  const M = window.ReviewModel;
  let state = M.createState(), route = location.hash.slice(1) || 'chat', layer = 'binding', resetTrigger, formDrafts = {};
  const esc = value => String(value ?? '未知').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const labels = {awaiting_user:'待补充',open:'处理中',awaiting_verification:'待验证',resolved:'已解决',handoff:'人工接管',archived_unconfirmed:'未确认归档',draft:'草稿',ready:'就绪',active:'执行中',waiting:'等待中',reconciling:'核查中',stopping:'停止中',succeeded:'执行成功',failed:'失败',cancelled:'已取消',blocked:'待依赖',dispatching:'分派中',waiting_input:'待输入',waiting_approval:'待审批',prepared:'已准备',submitting:'提交中',acceptance_unknown:'接纳未知',accepted:'已接收',waiting_permission:'待权限',terminal:'执行终态',rejected:'已拒绝',pending:'待决定',approved:'已批准',denied:'已拒绝',expired:'已过期',superseded:'已被替代',revoked:'已撤销',changes_requested:'需调整',delivered:'已送达',delivery_unknown:'送达未知',stale:'已失效',denied_by_provider:'内层拒绝',not_started:'未执行',result_unknown:'结果未知',verified:'已核验',metadata_only:'仅元数据'};
  const tag = (text, warn = false) => '<span class="tag '+(warn?'warn':'')+'">'+esc(labels[text] || text)+'</span>';
  const btn = (action, text, enabled = true, cls = '') => '<button data-action="'+action+'" class="'+cls+'" '+(enabled?'':'disabled')+'>'+text+'</button>';
  const ui = (action,text,cls='') => '<button data-ui="'+action+'" class="'+cls+'">'+text+'</button>';
  const kv = rows => '<dl>'+rows.map(([k,v])=>'<dt>'+esc(k)+'</dt><dd>'+esc(v)+'</dd>').join('')+'</dl>';
  const card = (title, body, cls='') => '<section class="card '+cls+'"><h2>'+title+'</h2>'+body+'</section>';
  const actions = text => '<div class="actions">'+text+'</div>';
  const pages = {workbench:'闭环演练',result:'结果与验证',connections:'执行连接',binding:'运行与绑定',approval:'审批与恢复',contract:'契约与状态',exceptions:'异常与接管'};
  const link = (id,title=pages[id]) => '<a class="nav-item" href="#'+id+'" '+(route===id?'aria-current="page"':'')+'>'+title+'</a>';
  function header() {
    return '<div class="page-head"><div><div class="eyebrow">'+(route==='workbench'||route==='result'?'测试工作台':'平台管理')+' / 初始设计</div><h1 tabindex="-1">'+pages[route]+'</h1><div class="muted">报表与明细不一致 · 一个问题、一条固定流程、一个执行拥有者</div></div><div class="scenario"><label for="scenario">演示路径</label><select id="scenario">'+Object.entries(M.scenarios).map(([id,name])=>'<option value="'+id+'" '+(state.scenario===id?'selected':'')+'>'+name+'</option>').join('')+'</select>'+ui('reset','重置')+'</div></div><div class="scope-line">系统：合成订单系统　/　环境：测试　/　固定流程：报表核对 · 已发布示例　/　轮次 '+state.round+'</div>';
  }
  function states() {
    const values=[['问题',state.issue],['流程运行',state.run],['节点运行',state.node],['执行绑定',state.binding.state],['审批决定',state.approval?.decision || '无请求']];
    return '<div class="state-grid">'+values.map(([name,value])=>'<div class="state-cell"><small>'+name+'</small><strong>'+esc(labels[value]||value)+'</strong><br><code>'+esc(value)+'</code></div>').join('')+'</div>';
  }
  function events() {
    return state.events.length?state.events.slice(-8).reverse().map(x=>'<div class="event"><span class="event-index">'+String(x.sequence).padStart(2,'0')+'</span><div>'+esc(x.message)+'<div class="help">'+esc(x.source)+'</div></div></div>').join(''):'<div class="empty">暂无执行事件。空记录不证明外部未发生执行。</div>';
  }
  function bindingInfo() {
    const b=state.binding,c=M.connections[b.provider];
    return kv([['执行拥有者',c.label+' / '+c.agent],['Binding',b.id],['NodeRun',b.nodeRunId],['连接 revision',b.connectionRevision],['外部运行引用',b.externalRef || '尚未确认'],['演示执行活跃',b.executionActive],['副作用',b.effectStatus],['拥有权占用',b.ownerOccupied?'保留':'未占用/已安全关闭'],['模型请求',c.model+'（合成）'],['实际模型确认','未知，未启动真实 Agent'],['下次默认连接',M.connections[state.defaultProvider].label]]);
  }
  function workbench() {
    const b=state.binding;
    return states()+'<div class="grid"><div class="stack">'+card('德国成交金额与导出明细不一致','<p class="muted">先补齐报表时间、时区、订单状态与币种；获得可核对证据后再交付结果。</p><div class="steps"><span class="step done">1 提出问题</span><span class="step '+(state.conditions?'done':'')+'">2 补齐条件</span><span class="step '+(b.externalRef?'done':'')+'">3 授权取证</span><span class="step '+(state.results.length?'done':'')+'">4 结果与验证</span></div><label for="conditions">报表范围、时区、状态与币种</label><textarea id="conditions" placeholder="例如：10 月 1–24 日，UTC+8，已完成订单，EUR">'+esc(state.conditions || '')+'</textarea>'+actions(btn('conditions','保存条件',state.run==='draft','primary')+btn('start','模拟接收并执行',state.node==='ready')+btn('start_timeout','模拟提交无响应',state.node==='ready'))+'<div class="help">实际能力均未验收；这里仅推进内存中的合成状态。</div>')+card('执行过程',events()+actions(btn('complete','模拟执行完成',['active','stopping'].includes(b.state))+btn('cancel','请求停止',['active','waiting_permission','reconciling'].includes(b.state))+btn('confirm_cancel','模拟停止确认',b.state==='stopping')+btn('request_approval','模拟受限导出请求',b.state==='active'&&!state.approval)))+'</div><div class="stack">'+card('当前上下文',bindingInfo(),'tint')+card('交付门槛','<ul class="checklist"><li>部署与代码版本一致</li><li>报表区间、取数时点与水位可比较</li><li>产物完整性和下载权限已检查</li><li>运行成功后仍需用户确认</li></ul><a href="#result">查看结果与验证 →</a>')+'</div></div>';
  }
  function resultPage() {
    const ready=state.issue==='awaiting_verification' && state.evidenceConsistent && state.artifact==='verified';
    const rows=state.results.map(x=>'<tr><td>结果 #'+x.revision+'</td><td>'+esc(x.bindingId)+'</td><td>'+esc(x.verifiedBy||'未确认')+'</td><td>'+esc(x.conclusion)+'</td></tr>').join('');
    return '<div class="banner">以下金额、查询区间、版本及文件均为内置合成示例，未解析用户材料或查询数据库。</div><div class="grid"><div class="stack">'+card('结论与有效范围',state.results.length?'<div class="between"><span class="result-amount">2,530 <small>EUR</small></span>'+tag(state.evidenceConsistent?'合成结论可复核':'证据不一致',!state.evidenceConsistent)+'</div><p>示例报表与明细采用不同订单状态口径。该结论仅适用于内置样例。</p>':'<div class="empty">本轮尚无新结果；已有历史结果会继续保留。</div>')+card('可定位证据',kv([['部署 / 代码',state.evidenceConsistent?'demo-deploy-1025 / demo-deploy-1025':'demo-deploy-1025 / demo-other'],['查询区间','2026-10-01T00:00:00+08:00 ≤ t < 2026-10-25T00:00:00+08:00'],['数据库取数时点','2026-10-25T06:00:00Z（合成）'],['报表水位','2026-10-25T05:55:00Z；示例延迟 5 分钟'],['来源定位','synthetic_report / sheet=德国 / rows=2–4'],['完整性',labels[state.artifact]||state.artifact]]))+card('结果历史','<div class="table-wrap"><table><thead><tr><th>版本</th><th>来源绑定</th><th>用户确认</th><th>结论</th></tr></thead><tbody>'+rows+'</tbody></table></div>')+'</div><div class="stack">'+card('用户验证','<p>执行成功只代表分析完成。请针对当前结果版本确认问题是否解决。</p>'+tag(state.issue)+actions(btn('verify','确认本结果已解决',ready,'primary')+btn('new_round','仍有问题 / 新一轮',M.safeClosed(state)))+'<div class="help">重开会保留旧结果。未知执行仍占用时禁止创建替代执行。</div>')+card('文件与接管','<p>产物状态：'+tag(state.artifact,state.artifact==='failed')+'</p>'+actions(ui('download-sample','下载合成差异样例')+ui('download-handoff','下载合成接管包'))+'<p class="help">下载前会重新检查当前模拟状态。真实 ACL、计算和文件导入尚未接入。</p>')+'</div></div>';
  }
  function connectionsPage() {
    return '<div class="banner">两条连接只做结构演示。真实连接健康、Agent 能力与模型确认均为未知；不会发起探测。</div><div class="grid"><div class="stack">'+Object.entries(M.connections).map(([id,c])=>card(c.label,'<div class="between"><span class="muted">'+c.agent+'</span>'+tag('实际未验收',true)+'</div><div class="separator"></div>'+kv([['连接标识','demo-'+id],['配置 revision',3],['Agent / 模型',c.agent+' → '+c.model],['能力观察','实际：unknown；演示数据：内置'],['审批桥接',c.bridge?'模拟宿主支持，实际未验收':'已查源码缺少完整外部审批桥接'],['二进制产物',id==='control'?'当前入口仅元数据，需适配导入':'模拟有导入步骤，实际未验收'],['实际隔离/预算','unknown，worktree 不等于 OS 沙箱']])+'<div class="actions"><button data-default="'+id+'">设为下次默认连接</button></div>',state.binding.provider===id?'tint':'')).join('')+'</div>'+card('绑定规则','<p>当前 Binding：<code>'+esc(state.binding.id)+'</code></p><p>当前拥有者：<strong>'+esc(M.connections[state.binding.provider].label)+'</strong></p><p>下次默认：'+esc(M.connections[state.defaultProvider].label)+'</p><ul class="checklist"><li>默认修改只影响新节点</li><li>接纳未知时先核查原键</li><li>原执行安全关闭后才可创建新绑定</li><li>能力未知/不匹配时拒绝真实启动</li></ul><a href="#binding">查看当前绑定 →</a>')+'</div>';
  }
  function bindingPage() {
    const b=state.binding, unknown=b.state==='acceptance_unknown';
    return states()+'<div class="grid"><div class="stack">'+card('唯一执行拥有者',bindingInfo(),'tint')+card('提交与断线核查','<p class="muted">查不到记录并不能排除延迟接纳。必须找到原回执，或证明旧提交永久不能接纳。</p>'+actions(btn('lookup_not_found','模拟查询：not_found',unknown)+btn('lookup_found','模拟找到原回执',unknown,'primary')+btn('close_final','模拟最终封闭原提交',unknown))+actions(btn('disconnect','模拟连接离线',['active','waiting_permission','stopping'].includes(b.state))+btn('reconcile_active','模拟原拥有者快照恢复',b.state==='reconciling')+btn('new_round','安全关闭后新一轮',M.safeClosed(state)))+'<div class="help">最终封闭证明：'+esc(b.admissionFence||'尚无')+'。这些回执均为合成示例。</div>')+'</div><div class="stack">'+card('事件与水位',events()+actions(btn('duplicate_event','模拟重复事件')))+card('预算与停止',kv([['真实费用','未上报'],['演示估算','18 内部积分；不等同实际费用'],['新调用门禁',state.usageBlocked?'阻断':'仅允许合成计数'],['合成新调用数',state.modelCalls+' / 2'],['start 发送数',state.startCommands],['停止状态',labels[b.state]||b.state]])+actions(btn('billable_step','模拟一次新增调用',b.state==='active'&&!state.usageBlocked)+btn('usage_unknown','模拟用量未知'))+'<p class="help">调用次数限制不证明供应商金额硬阻断。未知预算保持挂账。</p>')+'</div></div>';
  }
  function approvalPage() {
    const a=state.approval;
    if(!a)return card('暂无权限请求','<div class="empty">先加载“审批与安全恢复”演示路径，或在原执行中模拟受限导出请求。</div>');
    const bridge=M.connections[state.binding.provider].bridge;
    return '<div class="banner">批准固定的合成文件导出计划。原生请求没有统一固定超时；本页手动模拟请求失效，不运行 SQL 或写入。</div><div class="grid"><div class="stack">'+card('固定操作与范围',kv([['动作','导出合成差异文件；仅测试环境'],['operationId',a.operationId],['获审计划哈希',a.planHash],['当前计划哈希',state.currentPlanHash],['目标集合','合成订单 3 条；字段仅国家和金额'],['前置状态',a.preconditionsMatch?'合成数据 revision=7 已核对':'已漂移，需要重新取证'],['批准有效性',a.valid?'演示逻辑时钟：有效':'演示逻辑时钟：已过期'],['资源权限','仅合成样例；真实资源无授权'],['审批桥接',bridge?'模拟支持，实际未知':'当前连接不支持完整审批桥接']]))+card('业务决定','<div class="between">'+tag(a.decision,a.decision!=='approved')+'<code>'+esc(a.id)+'</code></div><label class="check"><input type="checkbox" id="approval-check" '+(formDrafts.approvalId===a.id&&formDrafts.checked?'checked':'')+'>我已审阅当前计划、影响范围、前置条件与有效期。</label><label for="approval-comment">审批意见（拒绝必填）</label><input type="text" id="approval-comment" value="'+esc(formDrafts.approvalId===a.id?formDrafts.comment:a.comment)+'" placeholder="请输入审批意见">'+actions(btn('approve','保存业务批准',a.decision==='pending','primary')+btn('deny','拒绝本计划',a.decision==='pending')))+'<section class="card"><h2>历史审批</h2>'+(state.approvalHistory.length?state.approvalHistory.map(h=>'<p><code>'+esc(h.id)+'</code> '+tag(h.decision,true)+' → <code>'+esc(h.requestId)+'</code></p>').join(''):'<div class="empty">尚无历史决定。恢复后保留旧记录。</div>')+'</section></div><div class="stack">'+card('权限送达与动作结果',kv([['原生 requestId',a.requestId],['映射 revision',a.mappingRevision],['请求有效',a.requestLive?'合成请求仍有效':'已失效 / 不可重放'],['响应送达',labels[a.delivery]||a.delivery],['动作结果',labels[a.action]||a.action],['权限发送次数',state.permissionSends],['已核验动作次数',state.operationsExecuted]])+actions(btn('send_permission','模拟发送权限响应',a.decision==='approved'&&a.delivery==='ready'&&bridge,'primary')+btn('send_timeout','模拟响应送达未知',a.decision==='approved'&&a.delivery==='ready'&&bridge))+actions(btn('lookup_delivery','核对原决定回执',a.delivery==='delivery_unknown')+btn('operation_receipt','模拟动作核验回执',a.delivery==='delivered'&&!a.consumed)+btn('inner_deny','模拟内层策略拒绝',a.decision==='approved'&&!a.consumed)))+card('失效与安全恢复','<div class="recovery"><p>① 核查原拥有者与未执行证明</p><p>② 保留旧请求和决定</p><p>③ 原 Binding 准备新请求</p><p>④ 新业务审批，再复查，再发送</p></div>'+actions(btn('expire_native','模拟原生请求失效',a.delivery==='ready')+btn('expire_business','模拟业务批准过期',a.delivery==='ready')+btn('drift','模拟前置条件漂移',a.delivery==='ready'))+actions(btn('refresh_plan','模拟重新取证',a.delivery==='stale')+btn('prepare_new_permission','安全准备新请求',a.delivery==='stale')))+'</div></div>';
  }
  function contractPage() {
    const l=window.REVIEW_TABLES.layers[layer];
    const methods=['inspectCapabilities','validateBinding','start','lookupSubmission','closeSubmission','getSnapshot','readEvents','submitInput','cancel','preparePermissionResume','respondPermission','getArtifact'];
    return '<div class="banner">正式数据结构与异常规则保存在设计文档。这里展示设计合同，不代表接口已经实现。</div>'+card('执行契约','<div class="actions compact">'+methods.map(x=>'<code>'+x+'</code>').join('')+'</div><p class="help">同键同哈希复用回执；not_found 不证明未接纳；取消请求不等于停止确认；未知不能换后端重放。</p><a href="../design/EXECUTION-PROVIDER.md">完整合同文档 →</a>')+'<div class="card mt"><div class="between"><h2>分层状态转换</h2><label for="state-layer">选择状态层</label><select id="state-layer" style="max-width:200px">'+Object.entries(window.REVIEW_TABLES.layers).map(([id,x])=>'<option value="'+id+'" '+(id===layer?'selected':'')+'>'+x.label+'</option>').join('')+'</select></div><p class="muted small">未列出的转换拒绝；重复事实只确认，不重复执行。审批决定、送达与动作状态分开。</p><div class="table-wrap"><table><thead><tr><th>ID / 从</th><th>触发 → 到</th><th>责任主体</th><th>守卫</th><th>原子组</th></tr></thead><tbody>'+l.transitions.map(x=>'<tr><td><b>'+x.id+'</b><br>'+esc(x.from.join(' / '))+'</td><td>'+esc(x.event)+'<br>→ '+esc(x.to)+'</td><td>'+esc(x.actor)+'</td><td>'+esc(x.guard)+'</td><td>'+esc(x.atomic)+'</td></tr>').join('')+'</tbody></table></div></div>';
  }
  function exceptionPage() {
    return states()+'<div class="grid"><div class="stack">'+card('当前阻塞与证据','<p>'+esc(state.message)+'</p>'+kv([['能力状态',state.capability==='simulation_only'?'仅合成演示，实际 unknown':state.capability],['用量门禁',state.usageBlocked?'新增计费阻断':'真实费用未上报；仅演示计数'],['证据一致性',state.evidenceConsistent?'合成版本与时点可比':'版本或时点不一致，限制结论'],['产物完整性',labels[state.artifact]||state.artifact],['未知拥有权',state.binding.ownerOccupied?'仍保留，不可替代执行':'无未关闭执行占用']]),'warning')+card('失效路径演示',actions(btn('usage_unknown','用量未上报')+btn('evidence_mismatch','证据版本不一致')+btn('artifact_corrupt','产物完整性失败')+btn('handoff','转人工接管',!['resolved','archived_unconfirmed'].includes(state.issue))))+'</div>'+card('人工接管材料','<ul class="checklist"><li>问题、条件、固定系统环境</li><li>唯一 Binding、外部运行引用、未知接纳和副作用</li><li>审批、原生请求及送达历史</li><li>最后事件水位、证据及文件状态</li><li>预算挂账、下一步核查与负责人</li></ul><p>接管责任人：<strong>合成测试负责人</strong></p><p class="help">接管不会清除未决拥有权。包仅包含合成材料，真实导出仍须逐项授权。</p>'+actions(ui('download-handoff','下载合成接管包')) )+'</div>';
  }
  function render(focus=false) {
    const chat = !route || route === 'chat';
    const admin = route.startsWith('admin-');
    if(route!=='admin-records')window.ServiceDesignPrototype.hide();
    document.querySelector('#configuration-root').hidden=!admin;
    document.querySelector('#chat-root').hidden=!chat;
    document.querySelector('#review-root').hidden=chat||admin;
    if(admin){window.AdminPrototype.render(route.slice(6),focus);return;}
    if(chat){window.ChatPrototype.render(focus);return;}
    if(!pages[route])route='workbench';
    document.querySelector('#work-nav').innerHTML=link('workbench')+link('result');
    document.querySelector('#admin-nav').innerHTML=['connections','binding','approval','contract','exceptions'].map(x=>link(x)).join('');
    const views={workbench,result:resultPage,connections:connectionsPage,binding:bindingPage,approval:approvalPage,contract:contractPage,exceptions:exceptionPage};
    document.querySelector('#page-header').innerHTML=header();
    document.querySelector('#content').innerHTML=views[route]();
    document.querySelector('#feedback').textContent=state.message;
    if(focus)document.querySelector('#review-root h1').focus();
  }
  function perform(action,payload={}){if(document.querySelector('#approval-comment'))formDrafts={approvalId:state.approval?.id,comment:document.querySelector('#approval-comment').value,checked:document.querySelector('#approval-check').checked};state=M.act(state,action,payload);render();}
  function download(name,content,type) {
    const url=URL.createObjectURL(new Blob([content],{type}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
  document.addEventListener('click',e=>{
    const b=e.target.closest('button');if(!b)return;
    if(b.dataset.default){perform('default_provider',{provider:b.dataset.default});return;}
    if(b.dataset.action){perform(b.dataset.action,{text:document.querySelector('#conditions')?.value,checked:document.querySelector('#approval-check')?.checked,comment:document.querySelector('#approval-comment')?.value});return;}
    if(b.dataset.ui==='reset'){resetTrigger=b;document.querySelector('#reset-dialog').showModal();}
    if(b.dataset.ui==='close-reset'){document.querySelector('#reset-dialog').close();resetTrigger?.focus();}
    if(b.dataset.ui==='confirm-reset'){document.querySelector('#reset-dialog').close();state=M.createState(state.scenario);formDrafts={};render(true);}
    if(b.dataset.ui==='download-sample'){
      if(state.artifact!=='verified'||!state.evidenceConsistent||!state.results.length){document.querySelector('#feedback').textContent='下载被阻止：当前产物未核验或证据不一致。';return;}
      download('合成差异样例.csv','\uFEFF国家,报表金额_EUR,明细金额_EUR,差额_EUR,来源\n德国,128450,125920,2530,离线合成示例_非业务结果\n','text/csv;charset=utf-8');
    }
    if(b.dataset.ui==='download-handoff')download('合成人工接管包.json',JSON.stringify({provenance:'offline_synthetic_example_not_business_evidence',responsible:'合成测试负责人',unknowns:'所有真实能力、真实执行、真实费用均未验收',next:'核查原拥有者与外部副作用；禁止静默换后端重放',state},null,2),'application/json');
  });
  document.addEventListener('change',e=>{
    if(e.target.id==='scenario'){state=M.createState(e.target.value);formDrafts={};render(true);}
    if(e.target.id==='state-layer'){layer=e.target.value;render();}
  });
  document.querySelector('#reset-dialog').addEventListener('cancel',()=>setTimeout(()=>resetTrigger?.focus(),0));
  window.addEventListener('hashchange',()=>{route=location.hash.slice(1);render(true);});
  window.__reviewPrototype={getState:()=>JSON.parse(JSON.stringify(state)),act:perform,loadScenario:name=>{state=M.createState(name);formDrafts={};render();}};
  render();
})();
