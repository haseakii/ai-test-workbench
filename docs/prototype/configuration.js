/* Synthetic configuration shared by the offline admin and chat previews. Not an API or ACL service. */
(() => {
  'use strict';
  const clone = x => JSON.parse(JSON.stringify(x));
  const initial = {
    revision:1,
    quotaDefaults:[{id:'global',name:'全局默认额度',...window.ContextQuota.defaults}],
    ...window.ResourceDesign.initial,
    ...window.GovernanceDesign.initial,
    workflows:clone(window.FlowDesign.initial),
    systems:[
      {id:'orders',name:'订单系统',owner:'订单测试组',description:'订单、明细与报表核对',enabled:true,prompts:['核对报表与明细差异','排查接口异常','分析错误日志']},
      {id:'payments',name:'支付系统',owner:'支付测试组',description:'支付请求、回调与异常定位',enabled:true,prompts:['排查接口异常','分析错误日志']},
      {id:'reports',name:'报表系统',owner:'数据测试组',description:'报表口径与数据差异检查',enabled:true,prompts:['核对报表与明细差异','分析错误日志']}
    ],
    environments:[
      {id:'orders-test',system:'orders',name:'测试环境',enabled:true,defaultModel:'general'},
      {id:'orders-stage',system:'orders',name:'预发环境',enabled:true,defaultModel:'general'},
      {id:'payments-test',system:'payments',name:'测试环境',enabled:true,defaultModel:'general'},
      {id:'payments-integration',system:'payments',name:'联调环境',enabled:true,defaultModel:'general'},
      {id:'reports-test',system:'reports',name:'测试环境',enabled:true,defaultModel:'general'},
      {id:'reports-stage',system:'reports',name:'预发环境',enabled:true,defaultModel:'general'}
    ],
    models:[
      {id:'general',name:'默认模型',provider:'control',agent:'示例 Agent A',enabled:true},
      {id:'analysis',name:'分析模型',provider:'direct',agent:'示例 Agent B',enabled:true}
    ],
    roles:[
      {id:'tester',name:'测试人员',canApproveRead:true,executionMode:'confirm',allowAutoRead:true,environments:['orders-test','orders-stage','payments-test','payments-integration','reports-test','reports-stage'],models:['general','analysis'],attachments:true,download:true,history:true,events:true,approver:'测试负责人',maxCalls:10,concurrency:1},
      {id:'observer',name:'协作成员',canApproveRead:false,executionMode:'confirm',allowAutoRead:false,environments:['orders-test'],models:['general'],attachments:false,download:false,history:true,events:false,approver:'测试负责人',maxCalls:3,concurrency:1}
    ]
  };
  for(const model of initial.models){model.connectionId='demo-'+model.provider;model.agentId='demo-agent-'+model.provider;model.quotaModelId='demo-model-'+model.id;}
  for(const role of initial.roles)role.workflowIds=initial.workflows.map(w=>w.id);
  for(const env of initial.environments){env.allowedModelIds=['general','analysis'];env.contextPolicyId='demo-context-policy';env.description='合成测试环境；数据仅供排查演示。';env.timezone='Asia/Shanghai';env.dataDelayMinutes=15;env.deploymentRef='';env.workflowIds=initial.workflows.map(w=>w.id);env.projectIds=['demo-'+env.system+'-code'];env.projectRefs={};env.mcpIds=['demo-data-mcp','demo-log-mcp'];env.skillIds=['demo-report-check'];env.understandingAllowed=true;env.requireHardModelLimits=false;env.policyMode=env.id.endsWith('-stage')?'require_approval':'allow_auto_readonly';}
  let published=clone(initial), draft=clone(initial), previewRole='tester';
  const audit=[],listeners=[];
  const types=['systems','environments','models','roles','quotaDefaults','workflows','projects','mcps','skills','agentConnections','runtimeProfiles','contextPolicies'];
  const entries=()=>types.flatMap(type=>draft[type].filter(item=>JSON.stringify(item)!==JSON.stringify(published[type].find(x=>x.id===item.id))).map(item=>({key:type+':'+item.id,type,id:item.id,name:item.name})));
  function candidate(keys){const c=clone(published);for(const e of entries().filter(e=>keys.includes(e.key))){const item=clone(draft[e.type].find(x=>x.id===e.id)),i=c[e.type].findIndex(x=>x.id===e.id);if(i<0)c[e.type].push(item);else c[e.type][i]=item;}return c;}
  function preview(keys){const selected=entries().filter(e=>keys.includes(e.key));if(!selected.length)return {ok:false,errors:['当前对象没有待应用改动；其他草稿可通过“选择变更集”显式选择。']};const c=candidate(keys),problems=errors(c);return {ok:!problems.length,errors:problems,selected,candidate:c};}
  const html=x=>String(x).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function selectionFields(keys){return '<fieldset class="config-change-selection"><legend>本次变更集（仅勾选项生效）</legend>'+entries().map(e=>'<label class="admin-check"><input type="checkbox" data-config-entry value="'+html(e.key)+'" '+(keys.includes(e.key)?'checked':'')+'>'+html(e.name)+' <small>'+html(e.type+'/'+e.id)+'</small></label>').join('')+'</fieldset><p>未选草稿保留；依赖缺失时拒绝发布，不自动带上其他改动。</p><p data-publish-error role="alert"></p>';}
  const readSelection=dialog=>[...dialog.querySelectorAll('[data-config-entry]:checked')].map(x=>x.value);
  function publish(keys=entries().map(e=>e.key)){const result=preview(keys);if(!result.ok)return result;const c=result.candidate;for(const e of result.selected){const item=c[e.type].find(x=>x.id===e.id),old=published[e.type].find(x=>x.id===e.id);item.revision=(old?.revision||0)+1;}c.revision=published.revision+1;published=c;for(const e of result.selected){const i=draft[e.type].findIndex(x=>x.id===e.id);draft[e.type][i]=clone(c[e.type].find(x=>x.id===e.id));}draft.revision=c.revision;audit.unshift({revision:c.revision,actor:'合成配置管理员',at:new Date().toISOString(),changes:result.selected.map(e=>e.type+' · '+e.name+'（'+e.id+'）')});listeners.forEach(fn=>fn());return {ok:true,revision:c.revision};}
  function discard(keys=entries().map(e=>e.key)){for(const e of entries().filter(e=>keys.includes(e.key))){const old=published[e.type].find(x=>x.id===e.id),i=draft[e.type].findIndex(x=>x.id===e.id);if(old)draft[e.type][i]=clone(old);else draft[e.type].splice(i,1);}}
  // Semantic dependency stamp for this offline prototype; not a production cryptographic hash.
  function canonical(x){if(Array.isArray(x))return x.map(canonical);if(x&&typeof x==='object')return Object.fromEntries(Object.keys(x).filter(k=>!['name','owner','agent','prompts','revision'].includes(k)).sort().map(k=>[k,canonical(x[k])]));return x;}
  function dependencyStamp(s,workflowId){const c=published,e=c.environments.find(x=>x.id===s.environmentId),m=c.models.find(x=>x.id===s.modelId),r=c.roles.find(x=>x.id===s.roleId),f=c.workflows.find(x=>x.id===workflowId),a=c.agentConnections.find(x=>x.id===m?.connectionId);const skills=c.skills.filter(x=>f?.skillIds?.includes(x.id));const toolRefs=String(f?.tools||'').split(',').map(x=>x.trim()).filter(Boolean);const mids=[...new Set([...toolRefs.map(x=>x.split('::')[0]),...skills.flatMap(x=>x.mcpIds||[])])];return JSON.stringify(canonical({system:c.systems.find(x=>x.id===s.systemId),environment:e?{id:e.id,system:e.system,enabled:e.enabled,description:e.description,timezone:e.timezone,dataDelayMinutes:e.dataDelayMinutes,deploymentRef:e.deploymentRef,contextPolicyId:e.contextPolicyId,policyMode:e.policyMode,understandingAllowed:e.understandingAllowed,requireHardModelLimits:e.requireHardModelLimits,modelAllowed:e.allowedModelIds?.includes(s.modelId),flowAllowed:e.workflowIds?.includes(workflowId),projectIds:f?.readCode?e.projectIds:[],projectRefs:f?.readCode?e.projectRefs:{},mcpIds:mids.filter(id=>e.mcpIds?.includes(id)),skillIds:skills.map(x=>x.id).filter(id=>e.skillIds?.includes(id))}:null,model:m,role:r?{id:r.id,environmentAllowed:r.environments.includes(s.environmentId),modelAllowed:r.models.includes(s.modelId),workflowAllowed:r.workflowIds?.includes(workflowId),canApproveRead:r.canApproveRead,executionMode:r.executionMode,allowAutoRead:r.allowAutoRead,approver:r.approver,maxCalls:r.maxCalls,concurrency:r.concurrency}:null,flow:f,skills,tools:c.mcps.filter(x=>mids.includes(x.id)),projects:f?.readCode?c.projects.filter(x=>e?.projectIds?.includes(x.id)):[],connection:a,runtime:c.runtimeProfiles.find(x=>x.id===a?.runtimeId),contextPolicy:c.contextPolicies.find(x=>x.id===e?.contextPolicyId),quotas:c.quotaDefaults}));}
  function executionAccess(s,p){if(access(s))return access(s);const c=published,e=c.environments.find(x=>x.id===s.environmentId),r=c.roles.find(x=>x.id===window.ReviewJourney.subject(s.roleId).role);if(!p)return '';const connection=c.agentConnections.find(x=>x.id===p.governanceSnapshot?.connection?.id),runtime=c.runtimeProfiles.find(x=>x.id===p.governanceSnapshot?.runtime?.id);if(!connection?.enabled||!runtime?.enabled||!runtime.readOnly||runtime.terminal)return '执行连接或运行权限已撤销';if(!c.workflows.some(x=>x.id===p.flowDecision?.workflowId&&x.enabled)||!r?.workflowIds?.includes(p.flowDecision?.workflowId)||!e?.workflowIds?.includes(p.flowDecision?.workflowId))return '流程使用资格已撤销';for(const t of p.flowDecision?.toolBindings||[]){const m=c.mcps.find(x=>x.id===t.connectionId);if(!e.mcpIds?.includes(t.connectionId)||!m?.enabled||!m.readOnly||!String(m.tools).split(',').map(x=>x.trim()).includes(t.name))return '工具授权已撤销';}for(const pjt of p.flowDecision?.codeProjects||[])if(!e.projectIds?.includes(pjt.id)||!c.projects.some(x=>x.id===pjt.id&&x.enabled))return '代码访问资格已撤销';for(const skill of p.flowDecision?.skillBindings||[])if(!e.skillIds?.includes(skill.id)||!c.skills.some(x=>x.id===skill.id&&x.enabled&&x.readOnly))return '方法访问资格已撤销';return '';}
  function understandingPolicy(s){const c=published,e=c.environments.find(x=>x.id===s.environmentId),u=window.ReviewJourney.subject(s.roleId),r=c.roles.find(x=>x.id===u.role),q=window.ContextQuota.resolve(c,u,s.modelId,s.environmentId);const collected=u.executionMode==='collect_only'||r?.executionMode==='collect_only'||e?.policyMode==='collect_only';return {allowed:!access(s)&&!collected&&e?.understandingAllowed!==false&&q.configured,purpose:'understanding',tools:[],attachmentContentsAllowed:false,reason:collected?'仅收集，不调用模型':e?.understandingAllowed===false?'尚未授权对话理解；材料已保存，等待管理员处理':!q.configured?q.reason:'仅允许获权消息理解，不授权代码或数据库工具',quota:q,actualExecution:'not_run'};}

  const current = () => clone(published);
  function visible(config=published,roleId=previewRole,applySubject=true,environmentId=null) {
    const subject=applySubject?window.ReviewJourney.subject(roleId):null;
    const role=config.roles.find(x=>x.id===(subject?.role||roleId));
    const models=config.models.filter(x=>x.enabled&&config.agentConnections.some(a=>a.id===x.connectionId&&a.enabled)&&(!environmentId||config.environments.find(e=>e.id===environmentId)?.allowedModelIds?.includes(x.id))&&role?.models.includes(x.id)&&(!subject||subject.status==='active'));
    const environments=config.environments.filter(x=>x.enabled&&role?.environments.includes(x.id)&&(!subject||subject.status==='active'&&(!subject.scopes||subject.scopes.includes(x.id)))&&config.systems.some(s=>s.id===x.system&&s.enabled));
    const systems=config.systems.filter(x=>x.enabled&&environments.some(e=>e.system===x.id));
    return clone({role,models,environments,systems});
  }
  function issues(config=draft){
    const messages=[],add=(message,type,id,field)=>messages.push({message,type,id,field});
    for(const [key,label] of [['systems','系统'],['models','模型'],['environments','环境'],['workflows','流程']]){
      const names=new Set();for(const item of config[key]){const name=item.name.trim(),identity=(key==='environments'?item.system+':':'')+name;
        if(!name||name.length>40)add(label+'名称须为 1–40 字。',key,item.id,'name');if(names.has(identity))add(label+'名称重复：'+name,key,item.id,'name');names.add(identity);
      }
    }
    for(const sys of config.systems)if(!sys.owner.trim())add(sys.name+'需要负责人。','systems',sys.id,'owner');
    for(const env of config.environments){if(!config.systems.some(x=>x.id===env.system))add('环境所属系统不存在。','environments',env.id,'system');if(env.enabled&&config.systems.some(x=>x.id===env.system&&x.enabled)&&!config.models.some(x=>x.id===env.defaultModel&&x.enabled))add(env.name+'的默认模型未启用。','environments',env.id,'defaultModel');}
    for(const role of config.roles){
      if(!role.approver.trim())add(role.name+'需要审批责任人。','roles',role.id,'approver');
      if(!Number.isInteger(role.maxCalls)||role.maxCalls<1||role.maxCalls>100)add('单轮调用上限须为 1–100 的整数。','roles',role.id,'maxCalls');
      if(!Number.isInteger(role.concurrency)||role.concurrency<1||role.concurrency>5)add('并发上限须为 1–5 的整数。','roles',role.id,'concurrency');
      const v=visible(config,role.id,false);for(const env of v.environments)if(!config.models.some(m=>m.id===env.defaultModel&&m.enabled&&role.models.includes(m.id)))add(role.name+'无权使用 '+env.name+' 的默认模型。','roles',role.id,'models');
    }
    for(const w of config.workflows)if((w.skillIds||[]).some(id=>!config.skills.some(x=>x.id===id&&(!w.enabled||x.enabled))))add('流程引用的 Skill 不存在或已停用。','workflows',w.id,'skillIds');
    if(!window.ContextQuota.valid(config.quotaDefaults[0]))add('额度须为范围内的非负整数，0 表示禁止新调用。','quotaDefaults','global','dailyCalls');
    for(const env of config.environments){try{new Intl.DateTimeFormat('zh-CN',{timeZone:env.timezone||'Asia/Shanghai'});}catch{add('请填写有效的 IANA 时区。','environments',env.id,'timezone');}if(env.dataDelayMinutes!==undefined&&(!Number.isInteger(env.dataDelayMinutes)||env.dataDelayMinutes<0||env.dataDelayMinutes>10080))add('数据延迟须为 0–10080 分钟的整数。','environments',env.id,'dataDelayMinutes');}
    return [...messages,...window.ResourceDesign.issues(config),...window.GovernanceDesign.issues(config)];
  }
  const errors=(config=draft)=>[...new Set(issues(config).map(x=>x.message))];
  function changes(){
    const labels={agentConnections:'Agent 连接',runtimeProfiles:'运行配置',contextPolicies:'上下文策略',quotaDefaults:'默认额度',workflows:'流程',systems:'系统',environments:'环境',models:'模型',roles:'权限',projects:'代码项目',mcps:'MCP',skills:'Skill'};
    const result=[];
    for(const key of Object.keys(labels))for(const item of draft[key]){
      const old=published[key].find(x=>x.id===item.id);
      if(JSON.stringify(old)!==JSON.stringify(item))result.push(`${labels[key]} · ${item.name}（${item.id}）`);
    }
    return result;
  }
  function access(session){
    const subject=window.ReviewJourney.subject(session.roleId);
    if(subject.status!=='active')return subject.status==='pending'?'加入申请待管理员开通，尚无检查和内容访问权限。':subject.status==='unregistered'?'请先完成身份验证并申请加入工作台。':'当前用户已暂停，不能继续检查或读取历史内容。';
    const v=visible(published,session.roleId,true,session.environmentId);
    if(!v.environments.some(x=>x.id===session.environmentId&&x.system===session.systemId)||!v.models.some(x=>x.id===session.modelId))return '当前系统、环境或模型已停用，或不在你的权限范围。请联系管理员，或新建对话选择可用范围。';
    return '';
  }
  function executionPolicy(session){
    const user=window.ReviewJourney.subject(session.roleId),v=visible(published,session.roleId),env=published.environments.find(x=>x.id===session.environmentId),requested=user.executionMode||v.role?.executionMode||'confirm',resources=window.ResourceDesign.resolve(published,session.environmentId);
    const governance=window.GovernanceDesign.decision(published,session);
    const quota=window.ContextQuota.resolve({...published,models:session.snapshot?.models||published.models},user,session.modelId,session.environmentId);
    let mode=requested,reason='用户与角色策略';
    if(access(session)){mode='blocked';reason='用户或范围已不可用';}
    else if(requested==='collect_only'||env?.policyMode==='collect_only'){mode='collect_only';reason='用户或环境限制为仅收集问题';}
    else if(!governance.configured){mode='blocked';reason=governance.reasons.join('；');}
    else if(!quota.configured){mode='blocked';reason=quota.reason;}
    else if(resources.mcps.some(x=>!x.readOnly)||resources.skills.some(x=>!x.readOnly)){mode='blocked';reason='资源涉及非只读能力，本阶段不允许执行';}
    else if(env?.policyMode==='require_approval'||requested==='approval'){mode='approval';reason='用户或环境要求负责人逐次审批';}
    else if(requested==='auto_readonly'&&(!v.role?.allowAutoRead||!resources.projects.length&&!resources.mcps.length)){mode='approval';reason='自动范围不满足，降为负责人审批';}
    return {requested,mode,reason,readOnly:true,policyRevision:published.revision,userRevision:user.revision,policyRef:'role/'+v.role?.id+'@'+published.revision+'/subject@'+user.revision,approver:v.role?.approver,maxCalls:quota.limits?.maxCalls,concurrency:quota.limits?.concurrency,quota,governance,realAdmission:'unknown'};
  }
  window.ReviewJourney.subscribe(()=>listeners.forEach(fn=>fn()));
  window.ConfigurationPrototype={
    current, draft:()=>draft, visible, errors, issues, changes, access,executionPolicy,
    role:()=>previewRole,
    setRole(id){if(!published.roles.some(x=>x.id===id))return;previewRole=id;listeners.forEach(fn=>fn());},
    subscribe(fn){listeners.push(fn);},
    entries, preview, selectionFields, readSelection, publish, discard, dependencyStamp, executionAccess, understandingPolicy,
    audit:()=>clone(audit)
  };
})();
