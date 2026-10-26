/* Offline conversation presentation. All replies are scripted; no model or resource calls. */
(() => {
  'use strict';
  const root = document.querySelector('#chat-root');
  const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const C=window.ConfigurationPrototype,J=window.ReviewJourney;
  function sync(s, resetModel=false) {
    const v=C.visible(s.snapshot||C.current(),s.roleId);
    if(!s.locked){
      const sys=v.systems.find(x=>x.id===s.systemId)||v.systems[0];
      const env=v.environments.find(x=>x.id===s.environmentId&&x.system===sys?.id)||v.environments.find(x=>x.system===sys?.id);
      v.models=C.visible(s.snapshot||C.current(),s.roleId,true,env?.id).models;
      const model=(!resetModel&&v.models.find(x=>x.id===s.modelId))||v.models.find(x=>x.id===env?.defaultModel);
      Object.assign(s,{systemId:sys?.id,environmentId:env?.id,modelId:model?.id,system:sys?.name||'无可用系统',environment:env?.name||'无可用环境',model:model?.name||'无可用模型'});
    }
    if(s.locked)v.models=C.visible(s.snapshot||C.current(),s.roleId,true,s.environmentId).models;
    return v;
  }
  const denied=s=>C.access(s);
  function check(s){const error=denied(s);if(error){root.querySelector('#chat-notice').textContent=error;return false;}return true;}
  let nextId = 1, currentId,infoOpen=false,infoTab='scope',renameDraft=null;const replyTimers=new Map(),replyQueue=new Map();
  const sessions = [];
  function create() {
    const s = {id:nextId++, roleId:C.role(),subjectId:J.subjectId(C.role()),inputRevision:0,latestResultRevision:0,verification:null,binding:null,trace:[], title:'新对话', titleMeta:{source:'placeholder',revision:0,needsRefinement:true,refinementUsed:false},titleHistory:[], systemId:null,environmentId:null,modelId:null, draft:'', files:[], messages:[], phase:'intro', busy:false, locked:false};
    sync(s);sessions.unshift(s); currentId=s.id; return s;
  }
  create();
  const current = () => sessions.find(s => s.id === currentId);
  const button = (action, text, extra='') => `<button type="button" data-chat="${action}" ${extra}>${text}</button>`;
  const options = (values, selected) => values.map(v => `<option${v===selected?' selected':''}>${esc(v)}</option>`).join('');
  function currentResult(s,m){return m.resultRevision===s.latestResultRevision&&m.inputRevision===s.inputRevision&&s.phase==='result'&&!s.busy&&!denied(s)&&J.sourceCurrent(m.preparation);}
  function invalidatePlans(s,reason){for(const m of s.messages)if(m.kind==='permission'&&(!m.decision||m.pendingReview)){m.pendingReview=false;m.decision=reason;J.event(s,reason);}}
  function scheduleExpiry(s,preparation){setTimeout(()=>{const m=s.messages.find(x=>x.kind==='permission'&&x.preparation.id===preparation.id&&(!x.decision||x.pendingReview));if(!m)return;m.pendingReview=false;m.decision='计划已过期，请补充信息重新确认';s.phase='details';J.event(s,'待确认计划到期，未执行');J.sync(s);if(currentId===s.id)render();},Math.max(0,Date.parse(preparation.validUntil)-Date.now()+1));}
  function message(s, m, i) {
    let content = `<div class="message-text">${esc(m.text)}</div>`;
    if(m.files?.length) content += `<div class="message-files">${m.files.map(f=>`<span>▧ ${esc(f.name)} · 仅文件名</span>`).join('')}</div>`;
    if(m.kind === 'permission') content += `<div class="inline-permission"><strong>确认本次检查范围</strong><p>${esc(m.preparation.flowDecision?.workflowName||'未确定检查流程')}<br>${esc(s.system)} · ${esc(s.environment)}<br>只读查询与本次问题有关的记录，生成检查摘要；不修改数据。</p><small>离线演示：不会访问任何系统。范围变更需重新确认。</small><div>${m.decision ? `<span>${esc(m.decision)}</span>` : (C.visible(C.current(),s.roleId).role?.canApproveRead?button('allow','允许本次检查',`class="chat-primary" data-message="${i}"`):button('request-review','请负责人确认',`class="chat-primary" data-message="${i}"`))+button('deny','暂不允许',`data-message="${i}"`)}</div></div>`;
    if(m.kind === 'result') {
      if(!J.sourceCurrent(m.preparation))content='<p class="admin-muted">该结果关联的来源已失效，暂不能读取、下载或确认。历史版本保留。</p>';
      const active=currentResult(s,m);
      content+=`<details class="chat-provenance"><summary>检查依据 · 结果 ${m.resultRevision}（合成）</summary><p>上下文：${esc(m.preparation.id)}；请求模型：${esc(m.preparation.requestedModel)}；实际模型未知。<br>代码与工具引用均为演示。附件仅文件名，未解析；使用记忆 ${m.preparation.memoryRefs.length} 项。</p></details><div class="result-choice">${m.verified?'<span>✓ 已确认此示例结果</span>':active?button('resolved','问题已解决',`data-message="${i}"`)+button('continue','继续排查',`data-message="${i}"`):'<span>历史或失效结果，不能确认当前问题</span>'}${active||m.verified&&J.sourceCurrent(m.preparation)?button('download','下载合成差异示例',`data-message="${i}" ${!C.visible().role?.download?'disabled':''}`):''}</div>`;
    }
    return `<article class="chat-message ${m.role}"><div class="message-author">${m.role==='user'?'你':m.role==='system'?'系统 <span>保存回执</span>':'测试助手 <span>模拟回复</span>'}</div>${content}</article>`;
  }
  function userStatus(s){return s.verification?.resultRevision===s.latestResultRevision&&s.latestResultRevision?'已确认解决':s.queued?'排队中':s.busy?'处理中':({intro:'新问题',details:'待补充',permission:s.messages.some(m=>m.pendingReview)?'等待负责人审批':'待你确认',result:'待你验证',blocked:'当前无法检查',collect_only:'问题已记录',stopped:'已停止',rejected:'审批未通过'})[s.phase]||'待补充';}
  function availableChecks(s){const rows=window.FlowDesign.list(C.current(),s),ready=rows.filter(x=>x.configured);return `<details class="chat-check-scope"><summary>${ready.length?'本环境已配置检查：'+ready.map(x=>x.workflowName).join('、'):'本环境暂无可执行的检查流程'}</summary><p>系统和环境确定范围，用户权限和已发布流程共同决定可用检查。${C.executionPolicy(s).mode==='approval'?'本轮需要负责人审批。':C.executionPolicy(s).mode==='auto_readonly'?'获准范围内可自动只读检查。':C.executionPolicy(s).mode==='collect_only'?'当前仅收集问题，不提交执行。':C.executionPolicy(s).mode==='blocked'?'当前策略阻止执行：'+esc(C.executionPolicy(s).reason):'执行前会确认本次范围。'}</p><small>对话理解：${C.understandingPolicy(s).allowed?'已允许获权消息理解；无业务工具权限':'仅保存材料，未授权理解或额度不可用'}。原型无真实模型调用，附件未解析。</small></details>`;}
  function problemInfo(s){if(denied(s))return '<p>当前无权读取此问题。</p>';const last=s.messages.findLast(m=>m.role==='user'),result=s.messages.findLast(m=>m.kind==='result'),policy=s.preparation?.executionPolicy||C.executionPolicy(s);return `<header><h2>问题信息</h2>${button('close-info','×','aria-label="关闭问题信息"')}</header><nav aria-label="问题信息分类">${[['scope','本轮范围'],['progress','处理进度'],['result','结果文件']].map(([id,name])=>button('info-tab',name,`data-tab="${id}" aria-pressed="${infoTab===id}"`)).join('')}</nav><div class="chat-info-body">${infoTab==='scope'?`<dl><dt>系统 / 环境</dt><dd>${esc(s.system)} / ${esc(s.environment)}</dd><dt>当前检查</dt><dd>${esc(s.preparation?.flowDecision?.workflowName||'待你说明问题')}</dd><dt>执行方式</dt><dd>${esc(({confirm:'由你确认本次范围',approval:'交负责人审批',auto_readonly:'允许范围内自动只读',collect_only:'仅收集问题',blocked:'当前无法执行'})[policy.mode])}</dd><dt>本轮补充</dt><dd>${esc(s.preparation?.approvalSummary?.conditions||last?.text||'尚未提交')}</dd></dl><p>附件仅登记文件名，尚未解析内容。</p>`:infoTab==='progress'?`<p class="chat-info-state">${userStatus(s)}</p><ol>${(s.trace||[]).filter(e=>/用户消息已保存|计划已送负责人|进入负责人审批|负责人已批准固定|唯一绑定已建立|产物已就绪|用户确认结果|停止本地|停止本次/.test(e.type)).map(e=>`<li>${esc(/用户消息/.test(e.type)?'已保存问题或补充':/负责人已批准/.test(e.type)?'负责人已确认本计划':/审批|送负责人/.test(e.type)?'已提交负责人确认':/唯一绑定/.test(e.type)?'开始本地示例检查':/产物/.test(e.type)?'示例结果已准备，等待验证':/用户确认/.test(e.type)?'你已确认本轮结果':'已停止本地示例')}</li>`).join('')||'<li>提交问题后开始记录处理进度。</li>'}</ol><small>这里记录处理状态，当前未调用真实工具。</small>`:result&&result.inputRevision===s.inputRevision&&result.resultRevision===s.latestResultRevision&&J.sourceCurrent(result.preparation)?`<h3>结果 ${result.resultRevision}</h3><p>${result.verified?'你已确认此结果':'等待你验证，结果不代表问题已解决。'}</p>${button('download','下载合成差异示例',`data-message="${s.messages.indexOf(result)}" ${!currentResult(s,result)&&!result.verified?'disabled':''}`)}<p>示例文件用于演示交付，不是业务证据。</p>`:'<p>本轮尚无可交付的结果文件。</p>'}</div>`;}
  function render(focus=false) {
    const s=current(),v=sync(s),blocked=denied(s);if(renameDraft&&(blocked||renameDraft.id!==s.id))renameDraft=null;J.sync(s);
    const history=sessions.filter(x=>x.subjectId===s.subjectId&&(C.visible().role?.history||x.id===s.id)&&(!denied(x)||x.id===s.id));
    const allowedFlows=window.FlowDesign.list(C.current(),s).filter(x=>x.configured).map(x=>x.workflowId);const prompts=(v.systems.find(x=>x.id===s.systemId)?.prompts||[]).filter(p=>allowedFlows.includes(/接口/.test(p)?'interface':/日志/.test(p)?'logs':'report'));
    root.innerHTML=`<aside class="chat-sidebar"><a class="chat-brand" href="#chat"><span>◈</span> 测试助手</a>${button('new','＋ 新对话','class="new-chat"')}<div class="chat-history-label">最近对话</div><nav aria-label="最近对话">${history.map(x=>button('session',esc(denied(x)?'不可访问的对话':x.title)+(!denied(x)&&x.locked?'<small class="chat-history-state">'+userStatus(x)+'</small>':''),`data-id="${x.id}" class="session-item ${x.id===s.id?'selected':''}" `)).join('')}</nav><div class="chat-sidebar-bottom"><a href="#admin-systems">平台管理 ↗</a><small>离线原型 · 刷新清空对话</small></div></aside>
    <main class="chat-main"><header class="chat-header"><div class="chat-problem-heading"><strong>${blocked?'不可访问的对话':s.locked?esc(s.title):'测试助手'}</strong><small>${esc(s.system)} / ${esc(s.environment)} · ${userStatus(s)}</small></div>${s.locked&&!blocked?button('rename','重命名','class="chat-rename-button" aria-label="重命名任务"'):''}${button('show-info','问题信息','class="chat-info-button"')}${button('new','＋ 新对话','class="mobile-new"')}<select class="mobile-new" id="chat-mobile-history" aria-label="切换对话">${history.map(x=>`<option value="${x.id}" ${x.id===s.id?'selected':''}>${esc(denied(x)?'不可访问的对话':x.title)}</option>`).join('')}</select><span>初始设计 · 离线演示</span></header><div class="chat-scroll" role="log" aria-label="对话记录" aria-live="polite">${blocked ? `<div class="chat-welcome"><h1>暂无可用的检查范围</h1><p>${esc(blocked)}</p>${['pending','unregistered'].includes(J.subject(s.roleId).status)?'<a href="#getting-started">查看加入申请</a>':''}</div>` : s.messages.length ? `<div class="message-list">${s.messages.map((m,i)=>message(s,m,i)).join('')}${s.busy?'<div class="replying" role="status">'+(s.queued?'排队中，可切换对话；尚未执行':'正在生成模拟回复，可切换对话')+'<span>•••</span></div>':''}</div>` : '<div class="chat-welcome"><div class="welcome-symbol">◈</div><h1>今天想检查什么？</h1><p>选好系统和环境，直接描述问题。</p></div>'}</div>
    <div class="chat-bottom">${availableChecks(s)}${!s.messages.length?`<div class="quick-prompts">${prompts.map((p,i)=>button('prompt',p,`data-prompt="${i}"`)).join('')}</div>`:''}<form id="chat-form" class="chat-composer"><div class="chat-context"><label>系统 <select id="chat-system" ${s.locked?'disabled':''}>${options(s.locked?[s.system]:v.systems.map(x=>x.name),s.system)}</select></label><label>环境 <select id="chat-environment" ${s.locked?'disabled':''}>${options(s.locked?[s.environment]:v.environments.filter(x=>x.system===s.systemId).map(x=>x.name),s.environment)}</select></label>${s.locked?'<span class="context-fixed" title="新建对话可选择其他系统和环境">本对话已固定</span>':''}</div><label class="sr-only" for="chat-input">描述问题</label><textarea id="chat-input" placeholder="描述你遇到的问题，也可以附上截图或文件…" rows="2">${esc(s.draft)}</textarea><div class="draft-files">${s.files.map((f,i)=>`<span>▧ ${esc(f.name)} ${button('remove-file','×',`data-file="${i}" aria-label="移除 ${esc(f.name)}"`)}</span>`).join('')}</div><div class="composer-tools"><div>${button('attach','＋','class="attach-button" aria-label="添加附件" '+(blocked||!C.visible().role?.attachments?'disabled':''))}<input id="chat-files" type="file" multiple hidden><label class="sr-only" for="chat-model">模型</label><select id="chat-model" aria-label="模型" ${s.busy?'disabled':''}>${options([...new Set([s.model,...v.models.map(x=>x.name)])],s.model)}</select><span class="model-note">演示</span></div>${s.busy?button('stop','停止','class="send-button"'):'<button type="submit" class="send-button" aria-label="发送消息" '+(blocked||(!s.draft.trim()&&!s.files.length)?'disabled':'')+'>↑</button>'}</div></form><div id="chat-notice" role="status">${blocked?esc(blocked):s.files.length?'附件仅展示文件名，未读取内容。':'模拟对话，不连接模型或业务系统。'}</div></div></main><dialog id="chat-info-dialog" class="chat-info-dialog" aria-label="问题信息">${problemInfo(s)}</dialog><dialog id="chat-rename-dialog" aria-labelledby="chat-rename-title"><form id="chat-rename-form"><h2 id="chat-rename-title">重命名任务</h2><label for="chat-title-input">任务名称</label><input id="chat-title-input" value="${esc(renameDraft?.value||'')}" autocomplete="off" aria-describedby="chat-title-help chat-title-error"><p id="chat-title-help">最多 40 个字或可见字符。手动修改后不再自动改名；不会改变检查范围或审批。</p><p id="chat-title-error" role="alert">${esc(renameDraft?.error||'')}</p><div class="chat-title-actions">${button('cancel-rename','取消')}<button type="submit" class="chat-primary">保存名称</button></div></form></dialog>`;
    const rename=root.querySelector('#chat-rename-dialog');rename.addEventListener('cancel',e=>{e.preventDefault();closeRename();});if(renameDraft)rename.showModal();
    const info=root.querySelector('#chat-info-dialog');info.addEventListener('cancel',e=>{e.preventDefault();infoOpen=false;info.close();root.querySelector('[data-chat=show-info]')?.focus();});if(infoOpen)info.showModal();if(focus&&!infoOpen&&!renameDraft)root.querySelector('#chat-input').focus();
    const scroll=root.querySelector('.chat-scroll');scroll.scrollTop=scroll.scrollHeight;
  }
  function refreshHistory(s){const node=root.querySelector('[data-chat="session"][data-id="'+s.id+'"] .chat-history-state');if(node)node.textContent=userStatus(s);}
  function runnable(s,p){
    if(denied(s))return false;
    if(!p)return C.understandingPolicy(s).allowed;
    if(s.phase!=='executing')return J.planCurrent(s,p)&&C.understandingPolicy(s).allowed;
    if(s.binding?.state==='queued_simulation')return J.planCurrent(s,p)&&!C.executionAccess(s,p)&&!['blocked','collect_only'].includes(C.executionPolicy(s).mode);
    return !C.executionAccess(s,p)&&J.sourceCurrent(p)&&p.integrationRevision===J.integration().revision;
  }
  function rejectQueued(s){
    s.busy=false;s.queued=false;s.phase='details';if(s.binding?.contextRef===s.preparation?.id&&s.binding.state==='queued_simulation')s.binding.state='stopped_simulation';
    J.event(s,'排队计划已失效，未开始检查；需要重新准备并确认');
    s.messages.push({role:'system',text:'排队期间计划已过期或授权、额度、检查范围发生变化。本次检查尚未开始，请补充信息后重新确认。'});
    J.sync(s);if(currentId===s.id)render();else refreshHistory(s);
  }
  function drainReplies(){
    for(const [id,job] of replyQueue){
      const {s,fn,preparation}=job;
      if(!runnable(s,preparation)){replyQueue.delete(id);rejectQueued(s);continue;}
      const active=[...replyTimers.keys()].filter(id=>sessions.find(x=>x.id===id)?.subjectId===s.subjectId).length;
      const limit=C.understandingPolicy(s).quota.limits?.concurrency||1;if(active>=limit)continue;
      replyQueue.delete(id);s.queued=false;
      if(s.binding?.state==='queued_simulation'){s.binding.state='active_simulation';s.binding.dispatchedAt=new Date().toISOString();}
      J.sync(s);if(currentId===id)render();else refreshHistory(s);
      const handle=setTimeout(()=>{replyTimers.delete(id);s.busy=false;s.queued=false;if(runnable(s,preparation))fn();else{if(s.binding?.contextRef===preparation?.id)s.binding.state='stopped_simulation';s.phase='details';J.event(s,'权限或上下文变化，本地模拟停止；真实在途执行需核查');s.messages.push({role:'assistant',text:'范围、权限或上下文已变化，已停止本次模拟回复。'});}J.sync(s);if(currentId===id)render(false);else refreshHistory(s);drainReplies();},650);
      replyTimers.set(id,handle);
    }
  }
  function reply(s,fn,preparation=null){s.busy=true;s.queued=true;replyQueue.set(s.id,{s,fn,preparation});if(s.phase==='executing'&&s.binding)s.binding.state='queued_simulation';J.sync(s);if(currentId===s.id)render();drainReplies();}
  function send() {
    const s=current(); if(s.busy||(!s.draft.trim()&&!s.files.length)||!check(s))return;
    if(s.files.length&&!C.visible().role.attachments){root.querySelector('#chat-notice').textContent='当前角色不能添加附件，请移除后再发送。';return;}
    if(!s.snapshot)s.snapshot=C.current();
    invalidatePlans(s,'已被新的补充内容替代');
    s.inputRevision++;s.verification=null;
    const text=s.draft.trim()||'请查看这些附件';
    s.messages.push({role:'user',text,files:s.files,model:s.model,inputRevision:s.inputRevision});J.event(s,'用户消息已保存（合成）');
    s.draft='';s.files=[];s.locked=true;
    window.TaskTitle.auto(s);
    const first=s.phase==='intro';
    if(first)s.topic=/接口|回调/.test(text)?'接口':/日志/.test(text)?'日志':'报表';
    s.preparation=J.prepare(s);J.event(s,'上下文与接入快照已固定（合成）');
    const understanding=C.understandingPolicy(s);s.understanding=understanding;
    if(!understanding.allowed){s.phase=s.preparation.executionPolicy.mode==='collect_only'?'collect_only':'blocked';s.messages.push({role:'system',text:'问题与材料已收集。'+understanding.reason+'。这是保存回执，不是 AI 分析；没有读取附件或创建执行。'});J.event(s,'仅保存材料，未调用理解模型或资源工具');J.sync(s);render(true);return;}
    J.event(s,'对话理解阶段（合成）：获权消息；工具为空；用途额度单独记录，实际用量未上报');
    reply(s,()=>{
      if(first){s.messages.push({role:'assistant',text:!s.preparation.flowDecision.configured?s.preparation.flowDecision.reasons.join('；')+'。你可以继续补充问题，当前不会提交执行。':`已选择${s.system}的${s.environment}。请补充发生时间、预期结果和实际现象；有截图或错误信息也可以一起说明。\n\n这里先演示条件追问，附件内容尚未读取。`});s.phase='details';if(s.preparation.executionPolicy.mode==='collect_only'){s.phase='collect_only';s.messages.at(-1).text='问题已收集。当前用户或环境设置为仅收集，可继续补充；不创建执行或审批。';}}
      else {
        const decision=s.preparation.executionPolicy;
        if(decision.mode==='collect_only'||decision.mode==='blocked'){s.messages.push({role:'assistant',text:decision.mode==='collect_only'?'问题与材料已收集。当前策略为仅收集，不创建执行或审批。':'当前计划不能执行：'+decision.reason});s.phase=decision.mode;J.event(s,decision.reason);}
        else if(decision.mode==='auto_readonly'){s.messages.push({role:'assistant',text:'已匹配允许范围内的自动只读策略，将直接推进合成检查；不会访问真实资源。'});J.event(s,'策略命中自动只读：'+decision.reason);startInspection(s,{preparation:s.preparation},'policy');}
        else {const m={role:'assistant',kind:'permission',preparation:s.preparation,configurationRevision:C.current().revision,text:'收到补充信息。接下来可以先做一次只读检查，请确认下面的范围。'};if(decision.mode==='approval'){m.pendingReview=true;m.decision='等待负责人确认';m.text='当前用户或环境要求逐次审批，已将本计划放入后台审批队列。';J.event(s,'按执行策略进入负责人审批队列');}s.messages.push(m);s.phase='permission';scheduleExpiry(s,s.preparation);}
      }
    },s.preparation);
  }
  function startInspection(s,m,authority='user'){if(!m.preparation?.flowDecision?.configured||!J.planCurrent(s,m.preparation)||['blocked','collect_only'].includes(m.preparation.executionPolicy.mode)){s.phase='blocked';s.messages.push({role:'assistant',text:'当前流程、权限或依赖已变化，请重新确认检查范围。'});J.sync(s);return;}m.pendingReview=false;s.topic=m.preparation.flowDecision.workflowId==='interface'?'接口':m.preparation.flowDecision.workflowId==='logs'?'日志':'报表';s.phase='executing';m.decision=authority==='reviewer'?'负责人已批准本计划':authority==='policy'?'自动只读策略已命中':'已允许本次示例检查';J.event(s,authority==='policy'?'已引用自动只读策略（合成）':authority==='reviewer'?'负责人已批准固定计划（合成）':'当前计划已确认（合成）');s.binding={id:m.preparation.plannedBindingId,provider:m.preparation.provider,state:'active_simulation',actualExecution:'not_run',inputRevision:s.inputRevision,authority,contextRef:m.preparation.id,preparationSnapshot:m.preparation,policyRef:m.preparation.executionPolicy.policyRef,resourceConfigurationRevision:m.preparation.resources.configurationRevision};s.executions??=[];s.executions.push(s.binding);J.event(s,'唯一绑定已建立：'+s.binding.id);reply(s,()=>{s.latestResultRevision++;if(s.binding){s.binding.state='terminal_simulation';s.binding.resultRevision=s.latestResultRevision;}J.event(s,'产物已就绪，结果待用户验证（合成）');s.messages.push({role:'assistant',kind:'result',resultRevision:s.latestResultRevision,inputRevision:s.inputRevision,preparation:m.preparation,text:'示例检查已完成，等待你验证。\n\n演示结论：'+(s.topic==='接口'?'先核对请求参数、响应码和同一时点的服务日志，缩小异常范围。':s.topic==='日志'?'先按发生时间与请求标识关联日志，区分首个异常和后续连带错误。':'报表与明细使用的筛选范围可能不同，建议先对齐时间范围与状态条件，再核对差异。')+'\n\n这是一条合成结果，用于评审对话流程；没有读取实际数据，也没有生成真实证据。'});s.phase='result';},m.preparation);}
  root.addEventListener('input',e=>{if(e.target.id==='chat-title-input'&&renameDraft)renameDraft.value=e.target.value;if(e.target.id==='chat-input'){const s=current();s.draft=e.target.value;const sendButton=root.querySelector('[type=submit]');if(sendButton)sendButton.disabled=!!denied(s)||(!s.draft.trim()&&!s.files.length);}});
  root.addEventListener('keydown',e=>{if(e.target.id==='chat-input'&&e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();send();}});
  function closeRename(){renameDraft=null;root.querySelector('#chat-rename-dialog')?.close();root.querySelector('[data-chat=rename]')?.focus();}
  root.addEventListener('submit',e=>{e.preventDefault();if(e.target.id==='chat-rename-form'){const s=current();if(!renameDraft||renameDraft.id!==s.id||denied(s)){closeRename();return;}const result=window.TaskTitle.rename(s,renameDraft.value,renameDraft.expectedRevision);if(result.error){renameDraft.error=result.error;root.querySelector('#chat-title-error').textContent=result.error;root.querySelector('#chat-title-input').focus();return;}renameDraft=null;J.sync(s);render();root.querySelector('[data-chat=rename]')?.focus();return;}send();});
  root.addEventListener('change',e=>{
    const s=current();
    if(e.target.id==='chat-mobile-history'){renameDraft=null;infoOpen=false;currentId=Number(e.target.value);render();}
    if(e.target.id==='chat-system'&&!s.locked){s.systemId=C.visible().systems.find(x=>x.name===e.target.value)?.id;s.environmentId=null;sync(s,true);render();root.querySelector('#chat-system').focus();}
    if(e.target.id==='chat-environment'&&!s.locked){s.environmentId=C.visible().environments.find(x=>x.system===s.systemId&&x.name===e.target.value)?.id;sync(s,true);render();}
    if(e.target.id==='chat-model'&&!s.busy){const m=C.visible(s.snapshot||C.current(),s.roleId,true,s.environmentId).models.find(x=>x.name===e.target.value);if(m&&m.id!==s.modelId){invalidatePlans(s,'模型已变更，请补充信息重新确认');s.model=m.name;s.modelId=m.id;s.verification=null;s.phase=s.phase==='intro'?'intro':'details';J.event(s,'下轮请求模型偏好已变更；旧执行快照保留');}render();}
    if(e.target.id==='chat-files'&&C.visible().role?.attachments&&!denied(s)){s.files.push(...Array.from(e.target.files,f=>({name:f.name,size:f.size})));render(true);}
  });
  root.addEventListener('click',async e=>{
    const b=e.target.closest('[data-chat]');if(!b)return;
    const s=current(), action=b.dataset.chat;
    if(action==='rename'&&s.locked&&!denied(s)){renameDraft={id:s.id,value:s.title,expectedRevision:s.titleMeta.revision,error:''};render();root.querySelector('#chat-title-input').select();return;}
    if(action==='cancel-rename'){closeRename();return;}
    if(action==='show-info'){infoOpen=true;render();return;}
    if(action==='close-info'){infoOpen=false;root.querySelector('#chat-info-dialog').close();root.querySelector('[data-chat=show-info]')?.focus();return;}
    if(action==='info-tab'){infoTab=b.dataset.tab;render();return;}
    if(action==='stop'){clearTimeout(replyTimers.get(s.id));replyTimers.delete(s.id);replyQueue.delete(s.id);s.busy=false;s.queued=false;queueMicrotask(drainReplies);if(s.binding)s.binding.state='stopped_simulation';s.phase='stopped';J.event(s,'停止本地模拟回复，未发生真实执行');s.messages.push({role:'assistant',text:'已停止本次模拟回复，可以继续补充。'});render(true);return;}
    if(action==='new'){renameDraft=null;infoOpen=false;create();render(true);return;}
    if(action==='session'){renameDraft=null;infoOpen=false;currentId=Number(b.dataset.id);render(true);return;}
    if(action==='prompt'){s.draft=C.visible(s.snapshot||C.current(),s.roleId).systems.find(x=>x.id===s.systemId)?.prompts[Number(b.dataset.prompt)]||'';render(true);return;}
    if(action==='attach'&&C.visible().role?.attachments&&!denied(s)){root.querySelector('#chat-files').click();return;}
    if(action==='remove-file'){s.files.splice(Number(b.dataset.file),1);render(true);return;}
    if(s.busy)return;
    const m=s.messages[Number(b.dataset.message)];
    if(action==='allow'&&m?.kind==='permission'&&!m.decision){if(!check(s))return;if(!J.planCurrent(s,m.preparation)){m.decision='配置已更新，请补充信息重新确认';render();return;}if(m.preparation.executionPolicy.mode==='approval'||!C.visible(C.current(),s.roleId).role?.canApproveRead){root.querySelector('#chat-notice').textContent='当前用户无此计划审批权，请交由负责人确认。';return;}startInspection(s,m);return;}
    if(action==='request-review'&&m?.kind==='permission'&&!m.decision){if(!check(s)||!J.planCurrent(s,m.preparation))return;m.pendingReview=true;m.decision='等待负责人确认';J.event(s,'计划已送负责人，提交人未自批');render(true);return;}
    if(action==='deny'&&m?.kind==='permission'&&!m.decision){m.decision='暂未允许';s.messages.push({role:'assistant',text:'已暂停检查。你可以继续补充信息，准备好后再确认新的检查范围。'});s.phase='details';}
    if(action==='resolved'&&m?.kind==='result'&&!m.verified&&currentResult(s,m)){m.verified=true;s.verification={resultRevision:m.resultRevision,inputRevision:s.inputRevision,subjectId:s.subjectId};J.event(s,'用户确认结果版本 '+m.resultRevision);s.messages.push({role:'user',text:'已确认此示例结果。'});}
    if(action==='continue'&&m?.kind==='result'&&currentResult(s,m)){s.draft='还没有解决，补充情况：';render(true);return;}
    if(action==='download'&&m?.kind==='result'){
      if(denied(s)||!C.visible().role?.download||!J.sourceCurrent(m.preparation)||!currentResult(s,m)&&!m.verified){root.querySelector('#chat-notice').textContent='下载被阻止：范围、来源或产物权限已失效。';return;}
      const file=J.artifact(s,m);const url=URL.createObjectURL(new Blob([file.content],{type:file.mime}));const a=document.createElement('a');a.href=url;a.download=file.name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);J.event(s,'下载合成产物：'+file.id);J.sync(s);return;
    }
    render(true);
  });
  C.subscribe(()=>{
    const s=current();
    if(s.roleId!==C.role()){if(s.busy){clearTimeout(replyTimers.get(s.id));replyTimers.delete(s.id);replyQueue.delete(s.id);s.busy=false;s.queued=false;queueMicrotask(drainReplies);s.messages.push({role:'assistant',text:'预览身份已切换，本次模拟回复已停止。'});}create();}
    for(const item of sessions){
      for(const m of item.messages)if(m.kind==='permission'&&(!m.decision||m.pendingReview)&&!J.planCurrent(item,m.preparation)){m.pendingReview=false;m.decision='配置已更新，请补充信息重新确认';J.event(item,'配置、授权或记忆修订使旧计划失效');}
      if(item.busy&&!runnable(item,item.preparation)){const wasQueued=item.queued;clearTimeout(replyTimers.get(item.id));replyTimers.delete(item.id);replyQueue.delete(item.id);if(wasQueued)rejectQueued(item);else{item.busy=false;item.queued=false;item.phase='details';if(item.binding?.contextRef===item.preparation?.id)item.binding.state='stopped_simulation';J.event(item,'撤权停止本地模拟；真实执行未发生');}queueMicrotask(drainReplies);}
      J.sync(item);
    }
    if(!root.hidden)render();
  });
  J.setApprovalHandler((recordId,contextRef,comment='')=>{const s=sessions.find(x=>'demo-chat-'+x.id===recordId);const m=s?.messages.findLast(x=>x.kind==='permission'&&x.pendingReview);if(!s||!m||denied(s)||!J.planCurrent(s,m.preparation)||m.preparation.id!==contextRef)return {ok:false,reason:'计划、权限或上下文已失效，需要重新提交'};m.reviewComment=comment.trim();if(m.reviewComment)J.event(s,'审批意见（合成）：'+m.reviewComment);startInspection(s,m,'reviewer');return {ok:true};});
  J.setRejectionHandler((recordId,contextRef,reason)=>{const s=sessions.find(x=>'demo-chat-'+x.id===recordId),m=s?.messages.findLast(x=>x.kind==='permission'&&x.pendingReview);if(!s||!m||!J.planCurrent(s,m.preparation)||m.preparation.id!==contextRef)return {ok:false,reason:'计划已失效'};m.pendingReview=false;m.reviewComment=reason;m.decision='负责人已拒绝：'+reason;s.phase='rejected';J.event(s,m.decision);J.sync(s);if(currentId===s.id)render();return {ok:true};});
  J.setStopHandler(recordId=>{const s=sessions.find(x=>'demo-chat-'+x.id===recordId);if(!s||!s.busy||!s.binding||!['active_simulation','queued_simulation'].includes(s.binding.state))return {ok:false,reason:'当前没有可停止的合成执行'};clearTimeout(replyTimers.get(s.id));replyTimers.delete(s.id);replyQueue.delete(s.id);s.busy=false;s.queued=false;queueMicrotask(drainReplies);s.binding.state='stopped_simulation';s.phase='stopped';J.event(s,'执行管理员停止本地模拟，真实执行未发生');J.sync(s);if(currentId===s.id)render();return {ok:true};});
  window.ChatPrototype={render,hide(){renameDraft=null;infoOpen=false;root.querySelectorAll('dialog[open]').forEach(d=>d.close());},inspect:()=>JSON.parse(JSON.stringify(current()))};
})();
