import {CommandJournal,Identity} from './command-journal';
import canonicalize from 'canonicalize';
export class ApiError extends Error {
  constructor(public status:number, public code:string) {
    super(({
      PLAN_STALE:'检查计划已失效，请重新核对范围。',
      REVISION_CONFLICT:'记录已更新，请刷新后比较修改。',
      TITLE_REVISION_CONFLICT:'问题名称已被更新，请重新确认。',
      IDEMPOTENCY_CONFLICT:'提交内容与原请求不同，请核查原记录。',
      EXECUTION_OWNER_OCCUPIED:'本问题仍有未关闭的执行，请先等待核查。',
      APPROVER_REQUIRED:'当前身份没有这项操作的审批权。',
      ADMIN_REQUIRED:'当前身份没有管理权限。',
      MEMBER_NOT_ACTIVE:'成员尚未开通，或已被暂停。',
      NOT_FOUND:'当前记录不可访问，请检查权限或重新选择。',
      DEPENDENCY_REVISION_CONFLICT:'关联配置已变化，草稿仍保留，请重新预览。',
      SOURCE_REVOKED_OR_CHANGED:'必要材料已失效，结果不能继续使用。',
      INTEGRITY_MISMATCH:'文件完整性检查失败，请联系负责人核查。',
      RESULT_STALE:'输入条件已变化，请对新的结果进行验证。',
      DOWNLOAD_DENIED:'当前文件不可下载，请核查权限和来源状态。',
      STALE_IDENTITY_RESPONSE:'身份已切换，请重新操作。',
      STALE_DOWNLOAD_SCOPE:'问题或来源状态已变化，请核查后重新下载。',
    } as Record<string,string>)[code]??'操作未完成，请刷新记录并核查当前权限或输入。');
  }
}
let activeSubject='';
let subjectGeneration=0;
const journal=new CommandJournal(()=>sessionStorage,(...args)=>request(...args),()=>window.dispatchEvent(new Event('submission-changed')));
export function activateSubject(subject:string){if(activeSubject!==subject){if(activeSubject)journal.lock();subjectGeneration++;}activeSubject=subject;}
export function activateIdentity(identity:Identity){if(identity.subject!==activeSubject)throw new ApiError(409,'STALE_IDENTITY_RESPONSE');journal.activate(identity);}
export const pendingCommand=()=>journal.pending();
export const resumeCommand=()=>journal.resume();
export function clearIdentityPayloads(){journal.lock();for(const key of Object.keys(sessionStorage))if(key.startsWith('wb-view:')||key.startsWith('wb-config-flow:'))sessionStorage.removeItem(key);}
export async function uploadAttachment(subject:string,path:string,file:File,environmentId:string){
 const sha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await file.arrayBuffer()))).map(x=>x.toString(16).padStart(2,'0')).join('');
 return journal.command(subject,path,{fileName:file.name,mediaType:file.type,sha256},'POST',environmentId,file);
}
export async function request<T=any>(subject:string, path:string, init:RequestInit={}):Promise<T>{
  const generation=subjectGeneration;
  const headers=new Headers(init.headers);headers.set('X-Test-Subject',subject);
  if(init.body && !(init.body instanceof FormData))headers.set('Content-Type','application/json');
  const response=await fetch('/api'+path,{...init,headers,cache:'no-store'});
  if(generation!==subjectGeneration||activeSubject&&activeSubject!==subject)throw new ApiError(409,'STALE_IDENTITY_RESPONSE');
  if(!response.ok){
    let code='REQUEST_FAILED';try{code=(await response.json()).error?.code??code;}catch{}
    if(response.status===401||response.status===403)window.dispatchEvent(new Event('permission-lost'));
    throw new ApiError(response.status,code);
  }
  const data=await response.json();
  if(generation!==subjectGeneration||activeSubject&&activeSubject!==subject)throw new ApiError(409,'STALE_IDENTITY_RESPONSE');
  return data;
}
export const command=(subject:string,path:string,body:object,method='POST',environmentId='')=>journal.command(subject,path,body,method,environmentId);
/** Each reconnect starts from an atomic snapshot watermark; partial frames are never acknowledged. */
export function followConversation(subject:string,id:string,update:(snapshot:any)=>void,failed:(error:Error)=>void){
  const lifetime=new AbortController();
  const delay=()=>new Promise<void>(resolve=>{const done=()=>{clearTimeout(timer);lifetime.signal.removeEventListener('abort',done);resolve();};const timer=setTimeout(done,500);lifetime.signal.addEventListener('abort',done,{once:true});});
  void (async()=>{while(!lifetime.signal.aborted){
    let connection:AbortController|undefined;
    try{
      const snapshot=await request<any>(subject,'/conversations/'+id,{signal:lifetime.signal});
      if(lifetime.signal.aborted)return;update(snapshot);
      connection=new AbortController();const abort=()=>connection?.abort();lifetime.signal.addEventListener('abort',abort,{once:true});
      try{
        const response=await fetch('/api/conversations/'+id+'/stream',{headers:{'X-Test-Subject':subject,'Last-Event-ID':snapshot.resumeCursor},signal:connection.signal,cache:'no-store'});
        if(!response.ok){let code='STREAM_FAILED';try{code=(await response.json()).error?.code??code;}catch{}throw new ApiError(response.status,code);}
        if(!response.body)throw new Error('事件连接暂不可用');
        const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='',refresh=false;
        try{while(!lifetime.signal.aborted&&!refresh){
          const part=await reader.read();if(part.done)break;
          buffer+=decoder.decode(part.value,{stream:true}).replace(/\r\n/g,'\n');
          let end:number;
          while((end=buffer.indexOf('\n\n'))>=0){
            const frame=buffer.slice(0,end);buffer=buffer.slice(end+2);
            let name='',data='',eventId='';for(const line of frame.split('\n')){if(line.startsWith('event:'))name=line.slice(6).trim();else if(line.startsWith('id:'))eventId=line.slice(3).trim();else if(line.startsWith('data:'))data+=line.slice(5).trimStart()+'\n';}
            if(activeSubject&&activeSubject!==subject)throw new ApiError(409,'STALE_IDENTITY_RESPONSE');
            if(name==='authorization-lost')throw new ApiError(403,'NOT_FOUND');
            if(name==='platform'){
              const page=JSON.parse(data);if(eventId!==page.cursor||!Array.isArray(page.events))throw new Error('事件连接需要恢复');
              if(page.events.length){
                if(page.events[0].sequence!==snapshot.eventWatermark+1)throw new Error('事件连接需要恢复');
                refresh=true;break;
              }
            }
          }
        }}finally{await reader.cancel().catch(()=>{});}
      }finally{lifetime.signal.removeEventListener('abort',abort);connection.abort();}
    }catch(e){
      if(lifetime.signal.aborted)return;
      if(e instanceof ApiError&&[401,403,404].includes(e.status)){failed(e);return;}
      if(e instanceof ApiError&&e.code==='STALE_IDENTITY_RESPONSE')return;
      // Transport loss, 410 cursors and bounded stream capacity recover through a fresh snapshot.
    }
    if(!lifetime.signal.aborted)await delay();
  }})();
  return()=>lifetime.abort();
}
export async function download(subject:string,id:string,currentScope:()=>boolean=()=>true){
  const generation=subjectGeneration;
  const response=await fetch('/api/artifacts/'+id+'/download',{headers:{'X-Test-Subject':subject},cache:'no-store'});
  if(!response.ok)throw new ApiError(response.status,'DOWNLOAD_DENIED');
  const bytes=await response.blob();if(generation!==subjectGeneration||activeSubject&&activeSubject!==subject)throw new ApiError(409,'STALE_IDENTITY_RESPONSE');
  if(!currentScope())throw new ApiError(409,'STALE_DOWNLOAD_SCOPE');
  const url=URL.createObjectURL(bytes);const link=document.createElement('a');link.href=url;link.download='difference.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
/** No export payload is cached. A late response must still belong to the current view. */
export async function downloadHandover(subject:string,id:string,currentScope:()=>boolean){
  const generation=subjectGeneration;
  const current=()=>{if(generation!==subjectGeneration||activeSubject!==subject)throw new ApiError(409,'STALE_IDENTITY_RESPONSE');if(!currentScope())throw new ApiError(409,'STALE_DOWNLOAD_SCOPE');};
  current();
  const value=await request<any>(subject,'/conversations/'+id+'/handover');current();
  if(value?.package?.schemaVersion!=='fixed-csv-handover-v1'||value.package.conversation?.conversationId!==id||!/^([a-f0-9]{64})$/.test(value.packageSha256))throw new ApiError(409,'INTEGRITY_MISMATCH');
  const canonical=canonicalize(value.package);if(typeof canonical!=='string')throw new ApiError(409,'INTEGRITY_MISMATCH');
  const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(canonical)))).map(x=>x.toString(16).padStart(2,'0')).join('');
  current();if(digest!==value.packageSha256)throw new ApiError(409,'INTEGRITY_MISMATCH');
  const bytes=new Blob([JSON.stringify({package:JSON.parse(canonical),packageSha256:digest},null,2)+'\n'],{type:'application/json'});
  const url=URL.createObjectURL(bytes);
  try{current();const link=document.createElement('a');link.href=url;link.download='handover-'+id+'.json';link.click();}
  finally{setTimeout(()=>URL.revokeObjectURL(url),1000);}
}
