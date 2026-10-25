/* Design-only context assembly and quota selection. No accounting or resource access. */
(() => {
 'use strict';
 const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const fields=[['dailyCalls','每日模型调用次数',10000],['dailyTokens','每日 Token 总量',100000000],['maxCalls','单轮调用上限',100],['concurrency','并发执行上限',5]];
 const defaults={dailyCalls:100,dailyTokens:100000,maxCalls:10,concurrency:1};
 // Offline identity only. Production uses confirmed canonical model IDs, never display names.
 const key=m=>[m.connectionId||'demo-'+m.provider,m.agentId||'demo-agent-'+m.provider,m.quotaModelId||m.id].join('/');
 const valid=q=>fields.every(([f,,max])=>Number.isInteger(q?.[f])&&q[f]>=0&&q[f]<=max);
 function resolve(c,user,modelId){
  const m=c.models.find(x=>x.id===modelId),role=c.roles.find(r=>r.id===user.role);
  if(!m)return {configured:false,reason:'模型未配置',actualUsage:'unknown',realAdmission:'unknown'};
  const pairKey=key(m),own=user.modelQuotas?.[pairKey],limits={...(own||c.quotaDefaults[0])};
  const effective=Object.fromEntries(fields.map(([f])=>[f,limits[f]]));
  effective.maxCalls=Math.min(effective.maxCalls,role?.maxCalls??0);
  effective.concurrency=Math.min(effective.concurrency,role?.concurrency??0);
  const allowed=!!(m.enabled&&role?.models.includes(m.id)&&user.status==='active');
  return {subjectId:user.id,pairKey,source:own?'用户单独设置':'全局默认',configurationRevision:c.revision,userRevision:user.revision,limits:effective,configured:allowed&&valid(limits)&&Object.values(effective).every(v=>v>0),reason:!allowed?'无此模型使用权限':!valid(limits)?'额度配置不合法':Object.values(effective).some(v=>v===0)?'此 Agent / 模型额度为 0，禁止新调用':'额度配置可用，真实余量待核查',actualModelId:null,actualUsage:'unknown',reservationId:null,realAdmission:'unknown'};
 }
 const inputs=(q,attr)=>`<div class="admin-two">${fields.map(([f,label,max])=>`<label class="admin-field">${label}<input type="number" min="0" max="${max}" ${attr(f)} value="${esc(q[f])}" required></label>`).join('')}</div>`;
 function globalEditor(c){return `<details class="admin-details"><summary>全局默认额度 · 所有用户的 Agent / 模型</summary><p>新用户和新增模型自动继承；仅例外成员需要单独配置。0 表示禁止新调用。</p>${inputs(c.quotaDefaults[0],f=>`data-quota-default="${f}"`)}<p class="admin-muted">每日按 Asia/Shanghai 计量；Token 为输入与输出合计。角色的单轮与并发上限仍取更严值，费用未上报不视为零。</p></details>`;}
 function userEditor(c,user){return `<details class="admin-details member-quotas"><summary>Agent / 模型额度</summary><p>默认继承全局设置。额度不会授予模型权限。用量尚未接入。</p>${c.models.map(m=>{const k=key(m),q=user.modelQuotas?.[k],r=resolve(c,user,m.id);return `<section class="quota-row"><h4>${esc(m.agent)} / ${esc(m.name)}</h4><small>${esc(m.provider)} · ${esc(r.source)} · ${r.configured?'真实余量未知':esc(r.reason)}</small><label class="admin-field">额度方式<select data-quota-mode="${esc(k)}"><option value="inherit" ${!q?'selected':''}>继承全局默认</option><option value="custom" ${q?'selected':''}>单独设置</option></select></label>${q?inputs(q,f=>`data-user-quota="${f}" data-quota-key="${esc(k)}"`):`<p>每日 ${c.quotaDefaults[0].dailyCalls} 次 / ${c.quotaDefaults[0].dailyTokens} Token；单轮 ${r.limits.maxCalls} 次，并发 ${r.limits.concurrency}。</p>`}</section>`;}).join('')}</details>`;}
 function context(c,s,flow){
  if(window.ConfigurationPrototype.access(s))return {configurationRevision:c.revision,descriptions:[],environmentFacts:null,resourceBindings:{projects:[],tools:[],skills:[]},configured:false,reasons:['当前用户没有此系统环境或模型的访问权限'],realAdmission:'unknown'};
  const system=c.systems.find(x=>x.id===s.systemId),env=c.environments.find(x=>x.id===s.environmentId);
  const resource=window.ResourceDesign.resolve(c,s.environmentId);
  const reasons=[];if(!system?.enabled||!env?.enabled||env.system!==system.id)reasons.push('系统与环境归属或状态不匹配');
  // Only explicit fields, never serialized configuration/credentials, enter model background.
  const projects=(flow.configured?resource.projects:[]).filter(p=>(flow.codeProjects||[]).some(x=>x.id===p.id));
  const descriptions=[{kind:'system',id:system?.id,revision:c.revision,text:system?.description||''},{kind:'environment',id:env?.id,revision:c.revision,text:env?.description||''},...projects.map(p=>({kind:'project',id:p.id,revision:p.revision,text:p.description||''}))].map(x=>({...x,trust:'configured_background',evidence:false}));
  return {configurationRevision:c.revision,systemId:system?.id,environmentId:env?.id,descriptions,environmentFacts:{timezone:env?.timezone||null,dataDelayMinutes:env?.dataDelayMinutes??null,deploymentRef:env?.deploymentRef||null,verification:'unverified'},resourceBindings:{projects:projects.map(p=>({id:p.id,revision:p.revision,sourceKind:p.sourceKind,requestedRef:p.requestedRef,actualContentHash:null})),tools:flow.configured?flow.toolBindings||[]:[],skills:flow.configured?flow.skillBindings||[]:[]},configured:!reasons.length,reasons,realAdmission:'unknown'};
 }
 window.ContextQuota={defaults,fields,key,valid,resolve,globalEditor,userEditor,context};
})();
