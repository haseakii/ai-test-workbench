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
  for(const env of initial.environments){env.allowedModelIds=['general','analysis'];env.contextPolicyId='demo-context-policy';env.description='合成测试环境；数据仅供排查演示。';env.timezone='Asia/Shanghai';env.dataDelayMinutes=15;env.deploymentRef='';env.workflowIds=initial.workflows.map(w=>w.id);env.projectIds=['demo-'+env.system+'-code'];env.projectRefs={};env.mcpIds=['demo-data-mcp','demo-log-mcp'];env.skillIds=['demo-report-check'];env.policyMode=env.id.endsWith('-stage')?'require_approval':'allow_auto_readonly';}
  let published=clone(initial), draft=clone(initial), previewRole='tester';
  const audit=[],listeners=[];
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
    const quota=window.ContextQuota.resolve({...published,models:session.snapshot?.models||published.models},user,session.modelId);
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
    discard(){draft=clone(published);},
    publish(){const issues=errors();if(issues.length)return {ok:false,errors:issues};const changed=changes();if(!changed.length)return {ok:false,errors:['没有待应用的改动。']};for(const type of ['projects','mcps','skills','workflows','agentConnections','runtimeProfiles','contextPolicies'])for(const item of draft[type]){const old=published[type].find(x=>x.id===item.id);if(old&&JSON.stringify(item)!==JSON.stringify(old))item.revision=old.revision+1;}published=clone(draft);published.revision++;draft=clone(published);audit.unshift({revision:published.revision,actor:'合成配置管理员',at:new Date().toISOString(),changes:changed});listeners.forEach(fn=>fn());return {ok:true,revision:published.revision};},
    audit:()=>clone(audit)
  };
})();
