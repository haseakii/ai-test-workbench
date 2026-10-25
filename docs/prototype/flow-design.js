/* Published business check templates for the offline prototype. Not an Agent, planner or authorization service. */
(() => {
 'use strict';
 const initial=[
  {id:'report',name:'报表差异核对',purpose:'核对报表与明细口径，交付差异和证据',tools:'demo-data-mcp::report.read, demo-data-mcp::detail.read',skillIds:['demo-report-check'],readCode:false,enabled:true,revision:1},
  {id:'interface',name:'接口异常排查',purpose:'通过只读日志关联请求，定位异常位置',tools:'demo-log-mcp::logs.search',skillIds:[],readCode:false,enabled:true,revision:1},
  {id:'logs',name:'错误日志分析',purpose:'按时间和请求标识检索错误日志',tools:'demo-log-mcp::logs.search',skillIds:[],readCode:false,enabled:true,revision:1}
 ];
 const names=value=>String(value||'').split(',').map(x=>x.trim()).filter(Boolean);
 function resolve(config,session,workflowId){
  const user=window.ReviewJourney.subject(session.roleId),role=config.roles.find(x=>x.id===user.role),env=config.environments.find(x=>x.id===session.environmentId),flow=config.workflows.find(x=>x.id===workflowId),model=(session.snapshot||config).models.find(x=>x.id===session.modelId),resources=window.ResourceDesign.resolve(config,session.environmentId),reasons=[];
  if(!user||user.status!=='active'||!role||!role.environments.includes(env?.id)||user.scopes&&!user.scopes.includes(env?.id))reasons.push('当前用户没有此环境权限');
  if(!flow?.enabled||!(env?.workflowIds||[]).includes(workflowId))reasons.push('此环境未开放该检查流程');
  if(!(role?.workflowIds||[]).includes(workflowId))reasons.push('当前角色未获准使用该检查流程');
  if(!model||!config.models.some(x=>x.id===model.id&&x.enabled)||!role?.models.includes(model.id))reasons.push('模型预设不可用或未授权');
  const governance=window.GovernanceDesign.decision(config,session);reasons.push(...governance.reasons);
  const required=names(flow?.tools),toolBindings=[];if(flow&&!required.length&&!flow.readCode)reasons.push('流程未配置取证工具或代码来源');
  for(const ref of required){const split=ref.indexOf('::'),connectionId=split>0?ref.slice(0,split):null,name=split>0?ref.slice(split+2):ref;const allowed=resources.mcps.find(m=>m.id===connectionId&&m.enabled&&m.readOnly&&names(m.tools).includes(name));if(allowed)toolBindings.push({connectionId:allowed.id,revision:allowed.revision,name,schemaHash:null});else reasons.push('缺少获准只读工具：'+name);}
  const skillBindings=[];for(const id of flow?.skillIds||[]){const skill=resources.skills.find(x=>x.id===id&&x.enabled&&x.readOnly);if(!skill)reasons.push('缺少流程要求的 Skill');else if((skill.mcpIds||[]).some(mid=>!resources.mcps.some(m=>m.id===mid&&m.enabled&&m.readOnly)))reasons.push('Skill 的 MCP 依赖不完整');else skillBindings.push({id:skill.id,revision:skill.revision,version:skill.version,verifiedContentHash:null});}
  const projects=resources.projects.filter(p=>p.enabled);if(flow?.readCode&&!projects.length)reasons.push('流程需要只读代码项目');
  return {workflowId,workflowName:flow?.name||'未指定流程',workflowRevision:flow?.revision||null,configurationRevision:config.revision,subjectRevision:user?.revision,configured:!reasons.length,reasons:[...new Set(reasons)],toolBindings,skillBindings,codeProjects:flow?.readCode?projects.map(p=>({id:p.id,revision:p.revision,sourceKind:p.sourceKind,snapshotKind:p.snapshotKind,requestedRef:p.requestedRef,actualCommit:null,actualContentHash:null})):[],requestedModelId:model?.id||null,provider:model?.provider||null,agent:model?.agent||null,realAdmission:'unknown',realExecutionAllowed:false};
 }
 function choose(session){const last=session.messages.findLast(x=>x.role==='user')?.text||'',first=session.messages.find(x=>x.role==='user')?.text||'',text=last+'\n'+first;
  if(/写入|改库|执行\s*(update|delete|insert)|直接修改数据|提交代码|部署修复/i.test(last))return {id:null,reason:'本阶段只提供只读检查，不能执行写入或部署。'};
  if(/读取截图|解析附件|分析图片|读取.*表格文件/.test(last))return {id:null,reason:'当前附件仅登记文件名，内容解析尚未接入。'};
  if(/接口|回调|超时/.test(text))return {id:'interface'};if(/日志|堆栈/.test(text))return {id:'logs'};if(/报表|明细|金额|口径/.test(text))return {id:'report'};
  if(session.preparation?.flowDecision?.workflowId)return {id:session.preparation.flowDecision.workflowId};
  return {id:null,reason:'还不能确定检查类型，请说明要核对报表、排查接口还是分析日志。'};
 }
 function list(config,session){return config.workflows.map(w=>({...resolve(config,session,w.id),purpose:w.purpose}));}
 window.FlowDesign={initial,resolve,choose,list};
})();
