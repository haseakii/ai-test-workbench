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
  const records=new Map(),listeners=[],recordListeners=[];let approvalHandler=null;
  const integration={revision:1,mcpSchemaRevision:1,actualCapabilities:'unknown'};
  const memory={id:'demo-memory-1',state:'candidate',mode:'off',sourceAccess:true,revision:1,scope:'orders-test'};
  const subjectId=role=>role==='observer'?'demo-live-observer':'demo-live-tester';
  const subject=role=>clone(users.find(u=>u.id===subjectId(role)));
  const emit=()=>listeners.forEach(fn=>fn());
  function event(s,type){
    s.trace??=[];s.trace.push({id:`demo-chat-${s.id}-event-${s.trace.length+1}`,sequence:s.trace.length+1,type,at:new Date().toISOString()});
  }
  function memoryAllowed(scope){return memory.mode!=='off'&&memory.state==='active'&&memory.sourceAccess&&memory.scope===scope;}
  function prepare(s){
    const owner=subject(s.roleId),model=s.snapshot.models.find(m=>m.id===s.modelId);
    return {id:`demo-context-${s.id}-${s.inputRevision}`,synthetic:true,validUntil:new Date(Date.now()+5*60*1000).toISOString(),turnId:`demo-turn-${s.id}-${s.inputRevision}`,inputRevision:s.inputRevision,
      configurationRevision:s.snapshot.revision,policyRevision:window.ConfigurationPrototype.current().revision,authorizationRevision:owner.revision,
      systemId:s.systemId,environmentId:s.environmentId,modelId:s.modelId,
      modelObservation:'unknown',requestedModel:s.model,actualModelId:null,provider:model?.provider||'unknown',
      runtimeRef:'demo-runtime-readonly',codeSnapshot:'demo-commit-a',deployedCommit:'demo-commit-a',mcpConnectionRef:'demo-tools-readonly',mcpSchemaRevision:integration.mcpSchemaRevision,integrationRevision:integration.revision,mcpCapability:'unknown',
      messageIds:s.messages.map((m,i)=>`demo-chat-${s.id}-message-${i+1}`),
      attachmentNames:s.messages.flatMap(m=>(m.files||[]).map(f=>f.name)),attachmentContentsRead:false,
      memoryRevision:memory.revision,memoryRefs:memoryAllowed(s.environmentId)?[memory.id]:[],
      excludedMemory:memoryAllowed(s.environmentId)?null:memory.mode==='off'?'默认关闭':!memory.sourceAccess?'原来源授权失效':memory.state!=='active'?'未审核启用或已撤销':'环境不匹配'};
  }
  function planCurrent(s,p){
    return !!p&&Date.parse(p.validUntil)>Date.now()&&p.inputRevision===s.inputRevision&&p.modelId===s.modelId&&p.policyRevision===window.ConfigurationPrototype.current().revision&&p.authorizationRevision===subject(s.roleId).revision&&p.memoryRevision===memory.revision&&p.integrationRevision===integration.revision;
  }
  function sourceCurrent(preparation){return !preparation?.memoryRefs.length||memoryAllowed(preparation.environmentId);}
  function sync(s){
    if(!s.locked||!s.messages.length)return;
    const current=s.messages.findLast(m=>m.kind==='result');
    const record={id:'demo-chat-'+s.id,owner:s.subjectId,scope:s.environmentId,title:s.title+'（本次离线对话）',
      status:!sourceCurrent(current?.preparation)?'来源失效待核查':s.verification?.resultRevision===s.latestResultRevision?'已确认':s.phase==='result'?'待验证':s.phase==='permission'?(s.messages.some(m=>m.pendingReview)?'待负责人审批':'待确认'):s.busy?'处理中':'待补充',
      text:s.messages.map(m=>(m.role==='user'?'用户：':'助手：')+(m.kind==='result'&&!sourceCurrent(m.preparation)?'此结果来源已失效，正文隐藏。':m.text)).join('\n\n'),usage:'未上报实际费用 · 无真实调用',
      events:(s.trace||[]).map(e=>e.type),trace:clone(s.trace||[]),
      preparation:clone(current?.preparation||s.preparation||null),binding:clone(s.binding||null),
      resultRevision:s.latestResultRevision||0,verification:clone(s.verification||null),synthetic:true};
    if(JSON.stringify(records.get(record.id))!==JSON.stringify(record)){records.set(record.id,record);recordListeners.forEach(fn=>fn());}
  }
  function artifact(s,m){
    const csv='\uFEFF来源,报表金额_EUR,明细金额_EUR,差额_EUR\n离线合成示例_非业务结果,128450,125920,'+(128450-125920)+'\n';
    return {id:`demo-chat-${s.id}-artifact-${m.resultRevision}`,name:`合成差异示例-结果${m.resultRevision}.csv`,content:csv,
      mime:'text/csv;charset=utf-8',resultRevision:m.resultRevision,contextRef:m.preparation.id,synthetic:true};
  }
  window.ReviewJourney={users,subjectId,subject,event,prepare,planCurrent,sourceCurrent,sync,artifact,
    approve(id,contextRef){return approvalHandler?approvalHandler(id,contextRef):{ok:false,reason:'没有待处理的计划'};},setApprovalHandler(fn){approvalHandler=fn;},records:()=>clone([...records.values()]),subscribeRecords(fn){recordListeners.push(fn);},memory:()=>clone(memory),memoryAllowed,
    updateMemory(patch){Object.assign(memory,patch);memory.revision++;emit();},
    integrationChanged(reason){integration.revision++;if(reason==='schema')integration.mcpSchemaRevision++;emit();},integration:()=>clone(integration),
    userChanged(){emit();},subscribe(fn){listeners.push(fn);}
  };
})();
