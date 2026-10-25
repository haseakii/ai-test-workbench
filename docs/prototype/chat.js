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
      const model=(!resetModel&&v.models.find(x=>x.id===s.modelId))||v.models.find(x=>x.id===env?.defaultModel)||v.models[0];
      Object.assign(s,{systemId:sys?.id,environmentId:env?.id,modelId:model?.id,system:sys?.name||'无可用系统',environment:env?.name||'无可用环境',model:model?.name||'无可用模型'});
    }
    return v;
  }
  const denied=s=>C.access(s);
  function check(s){const error=denied(s);if(error){root.querySelector('#chat-notice').textContent=error;return false;}return true;}
  let nextId = 1, currentId, timer = null;
  const sessions = [];
  function create() {
    const s = {id:nextId++, roleId:C.role(),subjectId:J.subjectId(C.role()),inputRevision:0,latestResultRevision:0,verification:null,binding:null,trace:[], title:'新对话', systemId:null,environmentId:null,modelId:null, draft:'', files:[], messages:[], phase:'intro', busy:false, locked:false};
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
    if(m.kind === 'permission') content += `<div class="inline-permission"><strong>确认本次检查范围</strong><p>${esc(s.system)} · ${esc(s.environment)}<br>只读查询与本次问题有关的记录，生成检查摘要；不修改数据。</p><small>离线演示：不会访问任何系统。范围变更需重新确认。</small><div>${m.decision ? `<span>${esc(m.decision)}</span>` : (C.visible(C.current(),s.roleId).role?.canApproveRead?button('allow','允许本次检查',`class="chat-primary" data-message="${i}"`):button('request-review','请负责人确认',`class="chat-primary" data-message="${i}"`))+button('deny','暂不允许',`data-message="${i}"`)}</div></div>`;
    if(m.kind === 'result') {
      if(!J.sourceCurrent(m.preparation))content='<p class="admin-muted">该结果关联的来源已失效，暂不能读取、下载或确认。历史版本保留。</p>';
      const active=currentResult(s,m);
      content+=`<details class="chat-provenance"><summary>检查依据 · 结果 ${m.resultRevision}（合成）</summary><p>上下文：${esc(m.preparation.id)}；请求模型：${esc(m.preparation.requestedModel)}；实际模型未知。<br>代码与工具引用均为演示。附件仅文件名，未解析；使用记忆 ${m.preparation.memoryRefs.length} 项。</p></details><div class="result-choice">${m.verified?'<span>✓ 已确认此示例结果</span>':active?button('resolved','问题已解决',`data-message="${i}"`)+button('continue','继续排查',`data-message="${i}"`):'<span>历史或失效结果，不能确认当前问题</span>'}${active||m.verified&&J.sourceCurrent(m.preparation)?button('download','下载合成差异示例',`data-message="${i}" ${!C.visible().role?.download?'disabled':''}`):''}</div>`;
    }
    return `<article class="chat-message ${m.role}"><div class="message-author">${m.role==='user'?'你':'测试助手 <span>模拟回复</span>'}</div>${content}</article>`;
  }
  function render(focus=false) {
    const s=current(),v=sync(s),blocked=denied(s);J.sync(s);
    const history=sessions.filter(x=>x.subjectId===s.subjectId&&(C.visible().role?.history||x.id===s.id)&&(!denied(x)||x.id===s.id));
    const prompts=v.systems.find(x=>x.id===s.systemId)?.prompts||[];
    root.innerHTML=`<aside class="chat-sidebar"><a class="chat-brand" href="#chat"><span>◈</span> 测试助手</a>${button('new','＋ 新对话','class="new-chat"')}<div class="chat-history-label">最近对话</div><nav aria-label="最近对话">${history.map(x=>button('session',esc(denied(x)?'不可访问的对话':x.title),`data-id="${x.id}" class="session-item ${x.id===s.id?'selected':''}" ${s.busy?'disabled':''}`)).join('')}</nav><div class="chat-sidebar-bottom"><a href="#admin-systems">平台管理 ↗</a><small>离线原型 · 刷新清空对话</small></div></aside>
    <main class="chat-main"><header class="chat-header"><strong>测试助手</strong>${button('new','＋ 新对话','class="mobile-new"')}<select class="mobile-new" id="chat-mobile-history" aria-label="切换对话">${history.map(x=>`<option value="${x.id}" ${x.id===s.id?'selected':''}>${esc(denied(x)?'不可访问的对话':x.title)}</option>`).join('')}</select><span>初始设计 · 离线演示</span></header><div class="chat-scroll" role="log" aria-label="对话记录" aria-live="polite">${blocked ? `<div class="chat-welcome"><h1>暂无可用的检查范围</h1><p>${esc(blocked)}</p></div>` : s.messages.length ? `<div class="message-list">${s.messages.map((m,i)=>message(s,m,i)).join('')}${s.busy?'<div class="replying" role="status">正在生成模拟回复<span>•••</span></div>':''}</div>` : '<div class="chat-welcome"><div class="welcome-symbol">◈</div><h1>今天想检查什么？</h1><p>选好系统和环境，直接描述问题。</p></div>'}</div>
    <div class="chat-bottom">${!s.messages.length?`<div class="quick-prompts">${prompts.map((p,i)=>button('prompt',p,`data-prompt="${i}"`)).join('')}</div>`:''}<form id="chat-form" class="chat-composer"><div class="chat-context"><label>系统 <select id="chat-system" ${s.locked?'disabled':''}>${options(s.locked?[s.system]:v.systems.map(x=>x.name),s.system)}</select></label><label>环境 <select id="chat-environment" ${s.locked?'disabled':''}>${options(s.locked?[s.environment]:v.environments.filter(x=>x.system===s.systemId).map(x=>x.name),s.environment)}</select></label>${s.locked?'<span class="context-fixed" title="新建对话可选择其他系统和环境">本对话已固定</span>':''}</div><label class="sr-only" for="chat-input">描述问题</label><textarea id="chat-input" placeholder="描述你遇到的问题，也可以附上截图或文件…" rows="2">${esc(s.draft)}</textarea><div class="draft-files">${s.files.map((f,i)=>`<span>▧ ${esc(f.name)} ${button('remove-file','×',`data-file="${i}" aria-label="移除 ${esc(f.name)}"`)}</span>`).join('')}</div><div class="composer-tools"><div>${button('attach','＋','class="attach-button" aria-label="添加附件" '+(blocked||!C.visible().role?.attachments?'disabled':''))}<input id="chat-files" type="file" multiple hidden><label class="sr-only" for="chat-model">模型</label><select id="chat-model" aria-label="模型" ${s.busy?'disabled':''}>${options([...new Set([s.model,...v.models.map(x=>x.name)])],s.model)}</select><span class="model-note">演示</span></div>${s.busy?button('stop','停止','class="send-button"'):'<button type="submit" class="send-button" aria-label="发送消息" '+(blocked||(!s.draft.trim()&&!s.files.length)?'disabled':'')+'>↑</button>'}</div></form><div id="chat-notice" role="status">${blocked?esc(blocked):s.files.length?'附件仅展示文件名，未读取内容。':'模拟对话，不连接模型或业务系统。'}</div></div></main>`;
    if(focus)root.querySelector('#chat-input').focus();
    const scroll=root.querySelector('.chat-scroll');scroll.scrollTop=scroll.scrollHeight;
  }
  function reply(s, fn,preparation=null) {
    s.busy=true;J.sync(s);if(currentId===s.id)render();
    timer=setTimeout(()=>{s.busy=false;timer=null;if(!denied(s)&&(!preparation||J.planCurrent(s,preparation)))fn();else {if(s.binding)s.binding.state='stopped_simulation';s.phase='details';J.event(s,'权限或上下文变化，本地模拟停止；真实在途执行需核查');s.messages.push({role:'assistant',text:'范围、权限或上下文已变化，已停止本次模拟回复。'});}J.sync(s);if(currentId===s.id)render(true);},650);
  }
  function send() {
    const s=current(); if(s.busy||(!s.draft.trim()&&!s.files.length)||!check(s))return;
    if(s.files.length&&!C.visible().role.attachments){root.querySelector('#chat-notice').textContent='当前角色不能添加附件，请移除后再发送。';return;}
    if(!s.snapshot)s.snapshot=C.current();
    invalidatePlans(s,'已被新的补充内容替代');
    s.inputRevision++;s.verification=null;
    const text=s.draft.trim()||'请查看这些附件';
    s.messages.push({role:'user',text,files:s.files,model:s.model,inputRevision:s.inputRevision});J.event(s,'用户消息已保存（合成）');
    s.draft='';s.files=[];s.locked=true;
    if(s.title==='新对话')s.title=text.slice(0,22);
    const first=s.phase==='intro';
    if(first)s.topic=/接口|回调/.test(text)?'接口':/日志/.test(text)?'日志':'报表';
    s.preparation=J.prepare(s);J.event(s,'上下文与接入快照已固定（合成）');
    reply(s,()=>{
      if(first){s.messages.push({role:'assistant',text:`已选择${s.system}的${s.environment}。请补充发生时间、预期结果和实际现象；有截图或错误信息也可以一起说明。\n\n这里先演示条件追问，附件内容尚未读取。`});s.phase='details';}
      else {s.messages.push({role:'assistant',kind:'permission',preparation:s.preparation,configurationRevision:C.current().revision,text:'收到补充信息。接下来可以先做一次只读检查，请确认下面的范围。'});s.phase='permission';scheduleExpiry(s,s.preparation);}
    },s.preparation);
  }
  function startInspection(s,m){m.pendingReview=false;m.decision='已允许本次示例检查';J.event(s,'当前计划已确认（合成）');s.binding={id:`demo-chat-binding-${s.id}-${s.inputRevision}`,provider:m.preparation.provider,state:'active_simulation',actualExecution:'not_run'};J.event(s,'唯一绑定已建立：'+s.binding.id);reply(s,()=>{s.latestResultRevision++;if(s.binding)s.binding.state='terminal_simulation';J.event(s,'产物已就绪，结果待用户验证（合成）');s.messages.push({role:'assistant',kind:'result',resultRevision:s.latestResultRevision,inputRevision:s.inputRevision,preparation:m.preparation,text:'示例检查已完成，等待你验证。\n\n演示结论：'+(s.topic==='接口'?'先核对请求参数、响应码和同一时点的服务日志，缩小异常范围。':s.topic==='日志'?'先按发生时间与请求标识关联日志，区分首个异常和后续连带错误。':'报表与明细使用的筛选范围可能不同，建议先对齐时间范围与状态条件，再核对差异。')+'\n\n这是一条合成结果，用于评审对话流程；没有读取实际数据，也没有生成真实证据。'});s.phase='result';},m.preparation);}
  root.addEventListener('input',e=>{if(e.target.id==='chat-input'){const s=current();s.draft=e.target.value;const sendButton=root.querySelector('[type=submit]');if(sendButton)sendButton.disabled=!!denied(s)||(!s.draft.trim()&&!s.files.length);}});
  root.addEventListener('keydown',e=>{if(e.target.id==='chat-input'&&e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();send();}});
  root.addEventListener('submit',e=>{e.preventDefault();send();});
  root.addEventListener('change',e=>{
    const s=current();
    if(e.target.id==='chat-mobile-history'){if(!s.busy)currentId=Number(e.target.value);render();}
    if(e.target.id==='chat-system'&&!s.locked){s.systemId=C.visible().systems.find(x=>x.name===e.target.value)?.id;s.environmentId=null;sync(s,true);render();root.querySelector('#chat-system').focus();}
    if(e.target.id==='chat-environment'&&!s.locked){s.environmentId=C.visible().environments.find(x=>x.system===s.systemId&&x.name===e.target.value)?.id;sync(s,true);render();}
    if(e.target.id==='chat-model'&&!s.busy){const m=C.visible(s.snapshot||C.current(),s.roleId).models.find(x=>x.name===e.target.value);if(m&&m.id!==s.modelId){invalidatePlans(s,'模型已变更，请补充信息重新确认');s.model=m.name;s.modelId=m.id;s.verification=null;s.phase=s.phase==='intro'?'intro':'details';J.event(s,'下轮请求模型偏好已变更；旧执行快照保留');}render();}
    if(e.target.id==='chat-files'&&C.visible().role?.attachments&&!denied(s)){s.files.push(...Array.from(e.target.files,f=>({name:f.name,size:f.size})));render(true);}
  });
  root.addEventListener('click',async e=>{
    const b=e.target.closest('[data-chat]');if(!b)return;
    const s=current(), action=b.dataset.chat;
    if(action==='stop'){clearTimeout(timer);timer=null;s.busy=false;if(s.binding)s.binding.state='stopped_simulation';J.event(s,'停止本地模拟回复，未发生真实执行');s.messages.push({role:'assistant',text:'已停止本次模拟回复，可以继续补充。'});render(true);return;}
    if(action==='new'){if(s.busy){root.querySelector('#chat-notice').textContent='请先停止当前回复，再新建对话。';return;}create();render(true);return;}
    if(action==='session'&&!s.busy){currentId=Number(b.dataset.id);render(true);return;}
    if(action==='prompt'){s.draft=C.visible(s.snapshot||C.current(),s.roleId).systems.find(x=>x.id===s.systemId)?.prompts[Number(b.dataset.prompt)]||'';render(true);return;}
    if(action==='attach'&&C.visible().role?.attachments&&!denied(s)){root.querySelector('#chat-files').click();return;}
    if(action==='remove-file'){s.files.splice(Number(b.dataset.file),1);render(true);return;}
    if(s.busy)return;
    const m=s.messages[Number(b.dataset.message)];
    if(action==='allow'&&m?.kind==='permission'&&!m.decision){if(!check(s))return;if(!J.planCurrent(s,m.preparation)){m.decision='配置已更新，请补充信息重新确认';render();return;}if(!C.visible(C.current(),s.roleId).role?.canApproveRead){root.querySelector('#chat-notice').textContent='当前用户无此计划审批权，请交由负责人确认。';return;}startInspection(s,m);return;}
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
    if(s.roleId!==C.role()){if(s.busy){clearTimeout(timer);timer=null;s.busy=false;s.messages.push({role:'assistant',text:'预览身份已切换，本次模拟回复已停止。'});}create();}
    for(const item of sessions){
      for(const m of item.messages)if(m.kind==='permission'&&(!m.decision||m.pendingReview)&&!J.planCurrent(item,m.preparation)){m.pendingReview=false;m.decision='配置已更新，请补充信息重新确认';J.event(item,'配置、授权或记忆修订使旧计划失效');}
      if(item.busy&&denied(item)){clearTimeout(timer);timer=null;item.busy=false;if(item.binding)item.binding.state='stopped_simulation';J.event(item,'撤权停止本地模拟；真实执行未发生');}
      J.sync(item);
    }
    if(!root.hidden)render();
  });
  J.setApprovalHandler((recordId,contextRef)=>{const s=sessions.find(x=>'demo-chat-'+x.id===recordId);const m=s?.messages.findLast(x=>x.kind==='permission'&&x.pendingReview);if(!s||!m||denied(s)||!J.planCurrent(s,m.preparation)||m.preparation.id!==contextRef)return {ok:false,reason:'计划、权限或上下文已失效，需要重新提交'};startInspection(s,m);return {ok:true};});
  window.ChatPrototype={render,inspect:()=>JSON.parse(JSON.stringify(current()))};
})();
