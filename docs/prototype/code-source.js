/* Code source form simulation. Never connects Git, reads directories or retains token text. */
(() => {
 'use strict';
 const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const input=(label,key,value)=>`<label class="admin-field">${label}<input data-field="${key}" value="${esc(value)}"></label>`;
 const select=(label,key,value,items)=>`<label class="admin-field">${label}<select data-field="${key}">${items.map(([id,name])=>`<option value="${id}" ${id===value?'selected':''}>${name}</option>`).join('')}</select></label>`;
 const defaults={sourceKind:'git',gitProvider:'gitlab',authMode:'access_token',tokenKind:'personal',username:'oauth2',localPath:'',executionHostRef:'demo-node-1'};
 function editor(x){
  let html=select('代码来源','sourceKind',x.sourceKind||'git',[['git','在线 Git 仓库'],['local','本地文件目录']]);
  if(x.sourceKind==='local')return html+input('执行节点标识','executionHostRef',x.executionHostRef||'')+input('节点上的绝对目录','localPath',x.localPath||'')+`<p class="admin-muted">例如 D:\\test-code\\orders 或 /srv/test-code/orders。目录位于服务端执行节点，不是浏览器所在电脑；普通目录也可使用，不要求存在 .git。</p>`+input('允许读取的相对路径','paths',x.paths)+`<p class="admin-note">运行时制作只读文件快照并记录内容摘要；不会要求填写 Git 分支，也不会使用 Git Token。本页不读取或验证该目录。</p>`;
  html+=select('Git 服务','gitProvider',x.gitProvider||'gitlab',[['gitlab','GitLab（含自建）'],['generic','其他 Git 服务']])+input('Git 项目地址（不含 Token）','address',x.address)+input('默认分支 / 标签 / commit 请求','defaultRef',x.defaultRef)+input('允许读取路径','paths',x.paths)+select('认证方式','authMode',x.authMode||'secret_ref',[['none','无需认证 / 公开仓库'],['access_token','Access Token（HTTPS）'],['ssh','SSH 密钥引用'],['secret_ref','已有受控凭据引用']]);
  if(x.authMode==='access_token')html+=select('Token 类型','tokenKind',x.tokenKind||'personal',[['personal','Personal Access Token'],['project','Project Access Token'],['group','Group Access Token'],['deploy','Deploy Token']])+input('Git HTTPS 用户名','username',x.username||'')+`<label class="admin-field" for="code-access-token">Access Token<input id="code-access-token" type="password" autocomplete="new-password" spellcheck="false" placeholder="仅试填演示值，不输入真实 Token" aria-describedby="code-token-note"></label><p id="code-token-note" class="admin-muted">只读拉取使用 read_repository 范围。保存后只显示配置状态，不回显令牌。原型不会保存输入值或连接 GitLab；请仅使用虚构演示值。</p><div class="service-actions"><button type="button" data-code-token="save">模拟保存 / 替换 Token</button><button type="button" data-code-token="clear" ${x.credentialRef?'':'disabled'}>清除凭据引用</button></div><p class="admin-note">${x.credentialRef?'已有凭据引用 · 真实认证未验证':'尚未配置 Token'}</p>`;
  if(x.authMode!=='none')html+=input(x.authMode==='ssh'?'SSH 密钥引用':'只读凭据引用','credentialRef',x.credentialRef||'');
  return html;
 }
 function validLocal(path){const p=String(path||'').trim().replace(/\\/g,'/');return !!p&&!/[\x00-\x1f]/.test(p)&&!p.split('/').some(v=>v==='..'||v==='.')&&(/^[A-Za-z]:\/[^/].*/.test(p)||/^\/[^/].*/.test(p))&&!/^\/\/|^[A-Za-z]:\/$/.test(p);}
 function issues(x){if(!x.enabled)return [];const out=[],add=(message,field)=>out.push({message:x.name+' '+message,type:'projects',id:x.id,field});
  if(x.sourceKind==='local'){if(!validLocal(x.localPath))add('需要执行节点上的绝对目录，不能是磁盘根目录、网络共享或包含路径跳转。','localPath');if(!/^[A-Za-z0-9_.-]+$/.test(x.executionHostRef||''))add('需要有效的执行节点标识。','executionHostRef');}
  else if(x.authMode==='access_token'){if(!x.address.startsWith('https://'))add('Access Token 只用于 HTTPS Git 地址。','address');if(!x.username?.trim())add('需要 Git HTTPS 用户名；Deploy Token 使用创建时分配的用户名。','username');if(!x.credentialRef)add('尚未配置 Access Token。','credentialRef');}
  else if(x.authMode==='ssh'&&(!x.address.startsWith('ssh://')||!x.credentialRef))add('SSH 认证需要 ssh:// 地址和受控密钥引用。',!x.address.startsWith('ssh://')?'address':'credentialRef');
  return out;
 }
 function resolve(p,ref){const local=p.sourceKind==='local';return {...p,sourceKind:p.sourceKind||'git',address:local?null:p.address,gitProvider:local?null:p.gitProvider,authMode:local?'none':p.authMode,credentialRef:local||p.authMode==='none'?null:p.credentialRef,username:local?null:p.username,defaultRef:local?null:p.defaultRef,requestedRef:local?null:ref||p.defaultRef,actualCommit:null,actualContentHash:null,snapshotKind:local?'directory_manifest':'git_commit',realSourceAccess:'unknown'};}
 window.CodeSource={defaults,editor,issues,resolve};
})();
