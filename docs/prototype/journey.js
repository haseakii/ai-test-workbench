/* Shared offline review ledger. Synthetic identities, contexts, records and files only. */
(() => {
  'use strict';
  const clone=x=>JSON.parse(JSON.stringify(x));
  const users=[
    {id:'demo-tester',name:'合成测试用户',role:'tester',scopes:['orders-test'],status:'active',revision:1},
    {id:'demo-collaborator',name:'合成协作成员',role:'observer',scopes:['orders-test'],status:'active',revision:1},
    {id:'demo-admin',name:'当前合成管理员',role:'access',scopes:['orders-test'],status:'active',revision:1},
    {id:'demo-live-tester',name:'当前对话用户（合成测试人员）',role:'tester',scopes:null,status:'active',revision:1},
    {id:'demo-live-observer',name:'当前对话用户（合成协作成员）',role:'observer',scopes:null,status:'active',revision:1}
  ];
  const records=new Map(),listeners=[],recordListeners=[];let approvalHandler=null,rejectionHandler=null,stopHandler=null;
  const integration={revision:1,mcpSchemaRevision:1,actualCapabilities:'unknown'};
  const memory={id:'demo-memory-1',state:'candidate',mode:'off',sourceAccess:true,revision:1,scope:'orders-test',ownerSubjectId:'demo-live-tester'};
  const subjectId=role=>role==='observer'?'demo-live-observer':'demo-live-tester';
  const subject=role=>clone(users.find(u=>u.id===subjectId(role)));
  const emit=()=>listeners.forEach(fn=>fn());
  function event(s,type){
    s.trace??=[];s.trace.push({id:`demo-chat-${s.id}-event-${s.trace.length+1}`,sequence:s.trace.length+1,type,at:new Date().toISOString()});
  }
  function memoryAllowed(scope,subjectId){const c=window.ConfigurationPrototype?.current(),env=c?.environments.find(e=>e.id===scope),policy=c?.contextPolicies.find(p=>p.id===env?.contextPolicyId);return !!(policy?.enabled&&policy.memory!=='off'&&(policy.memory!=='personal'||memory.ownerSubjectId===subjectId)&&memory.state==='active'&&memory.sourceAccess&&memory.scope===scope);}
  function prepare(s){
    const owner=subject(s.roleId),model=s.snapshot.models.find(m=>m.id===s.modelId),resources=window.ResourceDesign.resolve(window.ConfigurationPrototype.current(),s.environmentId),executionPolicy=window.ConfigurationPrototype.executionPolicy(s),choice=window.FlowDesign.choose(s),flowDecision=choice.id?window.FlowDesign.resolve(window.ConfigurationPrototype.current(),s,choice.id):{configured:false,workflowId:null,workflowName:null,reasons:[choice.reason],toolBindings:[],skillBindings:[],realAdmission:'unknown',realExecutionAllowed:false};
    if(!flowDecision.configured&&!['collect_only','blocked'].includes(executionPolicy.mode)){executionPolicy.mode='blocked';executionPolicy.reason=flowDecision.reasons.join('；');}
    return {dependencyStamp:window.ConfigurationPrototype.dependencyStamp(s,flowDecision.workflowId),understanding:window.ConfigurationPrototype.understandingPolicy(s),id:`demo-context-${s.id}-${s.inputRevision}`,synthetic:true,subjectId:owner.id,validUntil:new Date(Date.now()+5*60*1000).toISOString(),turnId:`demo-turn-${s.id}-${s.inputRevision}`,inputRevision:s.inputRevision,
      configurationRevision:s.snapshot.revision,policyRevision:window.ConfigurationPrototype.current().revision,authorizationRevision:owner.revision,
      systemId:s.systemId,environmentId:s.environmentId,modelId:s.modelId,executionContext:window.ContextQuota.context(window.ConfigurationPrototype.current(),s,flowDecision),quotaSnapshot:executionPolicy.quota,governanceSnapshot:executionPolicy.governance,resources,executionPolicy,flowDecision,approvalSummary:{question:s.messages.find(m=>m.role==='user')?.text||s.title,conditions:s.messages.findLast(m=>m.role==='user')?.text||'',system:s.system,environment:s.environment},plannedBindingId:['collect_only','blocked'].includes(executionPolicy.mode)?null:`demo-chat-binding-${s.id}-${s.inputRevision}`,
      modelObservation:'unknown',requestedModel:s.model,actualModelId:null,provider:model?.provider||'unknown',
      runtimeRef:executionPolicy.governance?.runtime?.id||'demo-runtime-readonly-unverified',codeSnapshot:null,requestedCodeRefs:resources.projects.map(p=>({id:p.id,sourceKind:p.sourceKind,requestedRef:p.requestedRef,actualContentHash:null})),deployedCommit:null,mcpConnectionRef:flowDecision.toolBindings.map(t=>t.connectionId).filter((x,i,a)=>a.indexOf(x)===i).join(',')||null,mcpSchemaRevision:integration.mcpSchemaRevision,integrationRevision:integration.revision,mcpCapability:'unknown',
      messageIds:s.messages.map((m,i)=>`demo-chat-${s.id}-message-${i+1}`),
      attachmentNames:s.messages.flatMap(m=>(m.files||[]).map(f=>f.name)),attachmentContentsRead:false,
      memoryRevision:memory.revision,memoryRefs:executionPolicy.mode!=='collect_only'&&memoryAllowed(s.environmentId,owner.id)?[memory.id]:[],
      excludedMemory:executionPolicy.mode==='collect_only'?'仅收集，不组装执行记忆':memoryAllowed(s.environmentId,owner.id)?null:executionPolicy.governance?.contextPolicy?.memory==='off'?'默认关闭':!memory.sourceAccess?'原来源授权失效':memory.state!=='active'?'未审核启用或已撤销':'环境不匹配'};
  }
  function planCurrent(s,p){
    return !!p&&Date.parse(p.validUntil)>Date.now()&&p.inputRevision===s.inputRevision&&p.modelId===s.modelId&&p.dependencyStamp===window.ConfigurationPrototype.dependencyStamp(s,p.flowDecision?.workflowId)&&p.authorizationRevision===subject(s.roleId).revision&&p.memoryRevision===memory.revision&&p.integrationRevision===integration.revision;
  }
  function sourceCurrent(preparation){return !preparation?.memoryRefs.length||memoryAllowed(preparation.environmentId,preparation.subjectId);}
  function sync(s){
    if(!s.locked||!s.messages.length)return;
    const current=s.messages.findLast(m=>m.kind==='result'),approval=s.messages.findLast(m=>m.kind==='permission');
    const record={id:'demo-chat-'+s.id,owner:s.subjectId,inputRevision:s.inputRevision,scope:s.environmentId,title:s.title,titleMeta:clone(s.titleMeta),titleHistory:clone(s.titleHistory),
      status:s.queued?'排队中':!sourceCurrent(current?.preparation)?'来源失效待核查':s.verification?.resultRevision===s.latestResultRevision?'已确认':s.phase==='result'?'待验证':s.phase==='permission'?(s.messages.some(m=>m.pendingReview)?'待负责人审批':'待确认'):s.phase==='collect_only'?'仅收集':s.phase==='blocked'?'执行已阻止':s.phase==='rejected'?'审批拒绝':s.phase==='stopped'?'已停止（合成）':s.busy?'处理中':'待补充',
      text:s.messages.map(m=>(m.role==='user'?'用户：':'助手：')+(m.kind==='result'&&!sourceCurrent(m.preparation)?'此结果来源已失效，正文隐藏。':m.text)).join('\n\n'),usage:'未上报实际费用 · 无真实调用',
      events:(s.trace||[]).map(e=>e.type),trace:clone(s.trace||[]),
      preparation:clone(s.preparation||current?.preparation||null),resultPreparation:clone(current?.preparation||null),pendingApproval:!!approval?.pendingReview,approval:clone(approval?{decision:approval.decision||'待确认',contextId:approval.preparation.id,validUntil:approval.preparation.validUntil,inputRevision:approval.preparation.inputRevision}:null),approvalHistory:clone(s.messages.filter(m=>m.kind==='permission').map(m=>({contextId:m.preparation.id,decision:m.decision||'待确认',comment:m.reviewComment||'',pendingReview:!!m.pendingReview,inputRevision:m.preparation.inputRevision,validUntil:m.preparation.validUntil,createdAt:new Date(Date.parse(m.preparation.validUntil)-5*60*1000).toISOString(),preparation:m.preparation}))),binding:clone(s.binding||null),executions:clone(s.executions||[]),resultInputRevision:current?.inputRevision||0,
      resultRevision:s.latestResultRevision||0,verification:clone(s.verification||null),synthetic:true};
    if(JSON.stringify(records.get(record.id))!==JSON.stringify(record)){records.set(record.id,record);recordListeners.forEach(fn=>fn());}
  }
  function artifact(s,m){
    const csv='\uFEFF来源,报表金额_EUR,明细金额_EUR,差额_EUR\n离线合成示例_非业务结果,128450,125920,'+(128450-125920)+'\n';
    return {id:`demo-chat-${s.id}-artifact-${m.resultRevision}`,name:`合成差异示例-结果${m.resultRevision}.csv`,content:csv,
      mime:'text/csv;charset=utf-8',resultRevision:m.resultRevision,contextRef:m.preparation.id,synthetic:true};
  }
  window.ReviewJourney={users,subjectId,subject,event,prepare,planCurrent,sourceCurrent,sync,artifact,
    reject(id,contextRef,reason){return rejectionHandler?rejectionHandler(id,contextRef,reason):{ok:false,reason:'没有可拒绝计划'};},setRejectionHandler(fn){rejectionHandler=fn;},stop(id){return stopHandler?stopHandler(id):{ok:false,reason:'没有可停止的执行'};},setStopHandler(fn){stopHandler=fn;},approve(id,contextRef,comment=''){return approvalHandler?approvalHandler(id,contextRef,comment):{ok:false,reason:'没有待处理的计划'};},setApprovalHandler(fn){approvalHandler=fn;},records:()=>clone([...records.values()]),subscribeRecords(fn){recordListeners.push(fn);},memory:()=>clone(memory),memoryAllowed,
    updateMemory(patch){Object.assign(memory,patch);memory.revision++;emit();},
    integrationChanged(reason){integration.revision++;if(reason==='schema')integration.mcpSchemaRevision++;emit();},integration:()=>clone(integration),
    userChanged(){emit();},subscribe(fn){listeners.push(fn);}
  };
})();
