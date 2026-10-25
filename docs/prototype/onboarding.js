/* First-use storyboard. No deployment, SSO, account creation or network calls. */
(() => {
  'use strict';
  const root=document.querySelector('#onboarding-root'),J=window.ReviewJourney;
  const state={started:false,identity:'signed_out',requestId:null,reason:''};
  const esc=x=>String(x).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let notice='';
  const user=()=>J.users.find(x=>x.id==='demo-live-tester');
  const button=(id,label,disabled=false)=>`<button data-onboarding="${id}" ${disabled?'disabled':''}>${label}</button>`;
  function render(focus=false){
    const u=user(),pending=u.status==='pending',active=state.started&&u.status==='active';
    root.innerHTML=`<header class="management-topbar"><a class="management-logo" href="#chat">✦ AI 测试协作台</a><a href="#chat">返回工作台</a><small>首次使用 · 离线演练</small></header><main class="onboarding-main"><header><span class="admin-eyebrow">首次开通演练</span><h1 tabindex="-1">从开通到第一次检查</h1><p>日常使用仍从对话开始。这里用固定合成身份走一遍首次申请与管理员开通。</p></header><section class="service-card"><h2>1. 管理员准备工作台</h2><p>正式部署需准备身份源、业务存储、文件存储和受控执行连接。当前只是离线页面，部署、登录和真实执行均未验证。</p><div class="onboarding-links"><a href="#admin-runtime">1. 运行配置</a><a href="#admin-integrations">2. Agent 与模型</a><a href="#admin-resources">3. 代码 / MCP / Skill</a><a href="#admin-systems">4. 系统与环境绑定</a><a href="#admin-workflows">5. 检查流程</a><a href="#admin-permissions">6. 权限与审批人</a></div><p class="admin-muted">先登记运行配置和 Agent，再发布其模型预设；环境选择允许的模型、资源及上下文策略，最后授予成员范围。配置可应用到演示，真实模型发现、资源只读边界与审批桥接仍待验收。</p></section><section class="service-card"><h2>2. 用户登录并申请加入</h2><p>演示身份：当前对话用户（合成测试人员）。不填写密码、邮箱或真实个人信息。</p>${!state.started?`<p>开始后，此合成用户暂时没有系统权限。已有本次对话记录时禁止重置开通状态；刷新可重新演练。</p>${button('begin','演练新用户加入')}`:state.identity==='signed_out'?`<p>尚未完成合成身份校验。</p>${button('identity','模拟企业身份验证成功')}`:!state.requestId?`<p>身份已确认（模拟），尚未加入工作台。</p><label class="admin-field">申请用途<textarea id="join-reason" maxlength="200" placeholder="例如：负责订单系统的测试排查">${esc(state.reason)}</textarea></label>${button('request','提交加入申请')}`:`<p><strong>${active?'已开通':pending?'等待管理员开通':u.status==='suspended'?'账号已暂停':'尚未开通'}</strong> · ${esc(state.requestId)}</p><p>申请用途：${esc(state.reason)}</p><p>加入申请只处理成员资格；每次检查仍按环境和用户执行方式确认或审批。</p>`}</section><section class="service-card"><h2>3. 分配范围，开始检查</h2><p>权限管理员在成员列表打开此用户，选择角色、环境和执行方式，再开通。申请人不能自行获得权限。</p><div class="onboarding-links"><a href="#admin-users">去用户与权限</a>${active?'<a href="#chat" class="admin-primary">进入对话</a>':'<span class="admin-muted">开通后可进入有权限的对话范围</span>'}</div>${active?`<p>已授予 ${u.scopes?.length||0} 个环境；模型与流程仍取已发布配置的交集。后台配置完整不等于真实执行已就绪。</p>`:''}</section><p id="onboarding-feedback" role="status" aria-live="polite">${esc(notice)}</p><details><summary>正式部署与真实试验的验收条件</summary><p>首次管理员由部署负责人通过一次性受控引导绑定企业身份，完成后关闭引导入口；普通申请不得获得管理员角色。服务器重启恢复、真实登录、MCP / ACP 权限与结果真实性须单独验收。</p><p>本页只演示成员开通和现有对话的联动，刷新清空，不构成账号或部署服务。</p></details></main>`;
    if(focus)root.querySelector('h1').focus();
  }
  root.addEventListener('input',e=>{if(e.target.id==='join-reason')state.reason=e.target.value;});
  root.addEventListener('click',e=>{
    const action=e.target.closest('[data-onboarding]')?.dataset.onboarding;if(!action)return;
    const u=user();notice='';
    if(action==='begin'){
      if(state.started)return;
      if(J.records().some(r=>r.owner===u.id)){notice='已有该用户的本次对话记录，不能重置成员状态。请刷新后从首次开通演练开始。';render();return;}
      state.started=true;u.status='unregistered';u.scopes=[];u.revision++;window.ConfigurationPrototype.setRole('tester');J.userChanged();
    }
    if(action==='identity'&&state.started&&state.identity==='signed_out')state.identity='verified_synthetic';
    if(action==='request'&&state.identity==='verified_synthetic'&&!state.requestId){
      if(state.reason.trim().length<5){notice='请填写至少 5 字的申请用途。';render();root.querySelector('#join-reason').focus();return;}
      state.requestId='demo-join-001';u.status='pending';u.joinRequest={id:state.requestId,reason:state.reason.trim(),synthetic:true};u.revision++;J.userChanged();
    }
    render();
  });
  window.OnboardingPrototype={render,inspect:()=>JSON.parse(JSON.stringify(state))};
})();
