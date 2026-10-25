/* Synthetic configuration shared by the offline admin and chat previews. Not an API or ACL service. */
(() => {
  'use strict';
  const clone = x => JSON.parse(JSON.stringify(x));
  const initial = {
    revision:1,
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
      {id:'tester',name:'测试人员',canApproveRead:true,environments:['orders-test','orders-stage','payments-test','payments-integration','reports-test','reports-stage'],models:['general','analysis'],attachments:true,download:true,history:true,events:true,approver:'测试负责人',maxCalls:10,concurrency:1},
      {id:'observer',name:'协作成员',canApproveRead:false,environments:['orders-test'],models:['general'],attachments:false,download:false,history:true,events:false,approver:'测试负责人',maxCalls:3,concurrency:1}
    ]
  };
  let published=clone(initial), draft=clone(initial), previewRole='tester';
  const audit=[],listeners=[];
  const current = () => clone(published);
  function visible(config=published,roleId=previewRole,applySubject=true) {
    const subject=applySubject?window.ReviewJourney.subject(roleId):null;
    const role=config.roles.find(x=>x.id===(subject?.role||roleId));
    const models=config.models.filter(x=>x.enabled&&role?.models.includes(x.id)&&(!subject||subject.status==='active'));
    const environments=config.environments.filter(x=>x.enabled&&role?.environments.includes(x.id)&&(!subject||subject.status==='active'&&(!subject.scopes||subject.scopes.includes(x.id)))&&config.systems.some(s=>s.id===x.system&&s.enabled));
    const systems=config.systems.filter(x=>x.enabled&&environments.some(e=>e.system===x.id));
    return clone({role,models,environments,systems});
  }
  function errors(config=draft) {
    const messages=[];
    for(const [key,label] of [['systems','系统'],['models','模型'],['environments','环境']]){
      const names=new Set();
      for(const item of config[key]){
        const name=item.name.trim(),identity=(key==='environments'?item.system+':':'')+name;
        if(!name||name.length>40)messages.push(label+'名称须为 1–40 字。');
        if(names.has(identity))messages.push(label+'名称重复：'+name);names.add(identity);
      }
    }
    for(const sys of config.systems)if(!sys.owner.trim())messages.push(sys.name+'需要负责人。');
    for(const env of config.environments){
      if(!config.systems.some(x=>x.id===env.system))messages.push('环境所属系统不存在。');
      if(env.enabled&&config.systems.some(x=>x.id===env.system&&x.enabled)&&!config.models.some(x=>x.id===env.defaultModel&&x.enabled))messages.push(env.name+'的默认模型未启用。');
    }
    for(const role of config.roles){
      if(!role.approver.trim())messages.push(role.name+'需要审批责任人。');
      if(!Number.isInteger(role.maxCalls)||role.maxCalls<1||role.maxCalls>100)messages.push('单轮调用上限须为 1–100 的整数。');
      if(!Number.isInteger(role.concurrency)||role.concurrency<1||role.concurrency>5)messages.push('并发上限须为 1–5 的整数。');
      const v=visible(config,role.id,false);
      for(const env of v.environments)if(!v.models.some(m=>m.id===env.defaultModel))messages.push(role.name+'无权使用 '+env.name+' 的默认模型。');
    }
    return [...new Set(messages)];
  }
  function changes(){
    const labels={systems:'系统',environments:'环境',models:'模型',roles:'权限'};
    const result=[];
    for(const key of Object.keys(labels))for(const item of draft[key]){
      const old=published[key].find(x=>x.id===item.id);
      if(JSON.stringify(old)!==JSON.stringify(item))result.push(`${labels[key]} · ${item.name}（${item.id}）`);
    }
    return result;
  }
  function access(session){
    const subject=window.ReviewJourney.subject(session.roleId);
    if(subject.status!=='active')return '当前用户已暂停，不能继续检查或读取历史内容。';
    const v=visible(published,session.roleId);
    if(!v.environments.some(x=>x.id===session.environmentId&&x.system===session.systemId)||!v.models.some(x=>x.id===session.modelId))return '当前系统、环境或模型已停用，或不在你的权限范围。请联系管理员，或新建对话选择可用范围。';
    return '';
  }
  window.ReviewJourney.subscribe(()=>listeners.forEach(fn=>fn()));
  window.ConfigurationPrototype={
    current, draft:()=>draft, visible, errors, changes, access,
    role:()=>previewRole,
    setRole(id){if(!published.roles.some(x=>x.id===id))return;previewRole=id;listeners.forEach(fn=>fn());},
    subscribe(fn){listeners.push(fn);},
    discard(){draft=clone(published);},
    publish(){const issues=errors();if(issues.length)return {ok:false,errors:issues};const changed=changes();if(!changed.length)return {ok:false,errors:['没有待应用的改动。']};published=clone(draft);published.revision++;draft=clone(published);audit.unshift({revision:published.revision,actor:'合成配置管理员',at:new Date().toISOString(),changes:changed});listeners.forEach(fn=>fn());return {ok:true,revision:published.revision};},
    audit:()=>clone(audit)
  };
})();
