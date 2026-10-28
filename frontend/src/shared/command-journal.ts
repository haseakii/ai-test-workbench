import canonicalize from 'canonicalize';
export type Identity={subject:string;subjectId:string};
type RecordBody=Record<string,any>;
export type Submission={identity:Identity;path:string;method:string;kind:string;key:string;digest:string;body?:RecordBody;fixed:RecordBody;attempted:boolean;context:{environmentId:string;conversationId:string}};
type Transport=(subject:string,path:string,init?:RequestInit)=>Promise<any>;
const prefix='wb-submission:v1:';
const message={pending:'另有待核查提交，请先核查原提交，不能换键重发。',changed:'原提交结果尚未确认，不能更改内容。请先核查原提交。',content:'切换身份已清除原请求正文。请重新填写相同内容，以原提交身份继续核查。',storage:'浏览器无法保存提交身份，请启用本标签页存储后重试。'};
const digest=async(value:object)=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(canonicalize(value)!)))).map(x=>x.toString(16).padStart(2,'0')).join('');
export function commandKind(path:string,method:string){
 const pairs:[RegExp,string][]=[[/^\/membership-requests$/,'membership.request'],[/^\/conversations$/,'conversation.create'],[/\/messages$/,'message.save'],[/\/attachments$/,'attachment.import'],[/\/title$/,'conversation.title'],[/\/understanding$/,'understanding.prepare'],[/\/prepare$/,'execution.prepare'],[/\/stop$/,'execution.stop'],[/^\/approvals\/.+\/decision$/,'approval.decide'],[/^\/results\/.+\/verification$/,'result.verify'],[/^\/admin\/configurations$/,'configuration.create'],[/\/draft$/,'configuration.draft'],[/\/disable$/,'configuration.disable'],[/^\/admin\/config-publications$/,'configuration.publish'],[/^\/admin\/grants$/,'membership.grant'],[/^\/admin\/grants\/revoke$/,'membership.revoke'],[/^\/admin\/content-reviewers$/,'content.eligibility'],[/^\/admin\/content-leases$/,'content.lease']];
 const found=pairs.find(([pattern])=>pattern.test(path));if(!found||!['POST','PATCH'].includes(method))throw new Error('此操作尚不支持持久提交身份。');return found[1];
}
/** One unresolved logical submission per tab/identity. No automatic replay on activation/reload. */
export class CommandJournal {
 private identity:Identity|null=null;
 private inFlight=false;
 private preparing=false;
 private generation=0;
 private files=new Map<string,File>();
 constructor(private storage:()=>Storage,private transport:Transport,private changed:()=>void=()=>{}){}
 private slot(identity=this.identity){if(!identity)throw new Error('身份尚未验证，请稍后重试。');return prefix+identity.subjectId;}
 lock(){
  const old=this.pending();if(old){delete old.body;this.write(old);}this.files.clear();this.identity=null;this.generation++;
 }
 activate(identity:Identity){if(this.identity?.subjectId!==identity.subjectId&&this.identity)this.lock();if(!this.identity)this.generation++;this.identity=identity;this.changed();}
 pending():Submission|null{
  if(!this.identity)return null;
  try{const value=this.storage().getItem(this.slot());if(!value)return null;const row=JSON.parse(value) as Submission;
   if(row.identity.subjectId!==this.identity.subjectId||row.identity.subject!==this.identity.subject)throw new Error();return row;
  }catch{throw new Error(message.storage);}
 }
 private write(record:Submission){try{this.storage().setItem(this.slot(record.identity),JSON.stringify(record));}catch{throw new Error(message.storage);}this.changed();}
 private clear(record:Submission){this.storage().removeItem(this.slot(record.identity));this.files.delete(record.key);this.changed();}
 private assertIdentity(record:Submission){if(!this.identity||this.identity.subject!==record.identity.subject||this.identity.subjectId!==record.identity.subjectId)throw new Error('身份已切换，原提交不会在当前身份重试。');}
 private async execute(record:Submission,phase:(value:'lookup'|'send')=>void){
  const generation=this.generation;const check=()=>{this.assertIdentity(record);if(generation!==this.generation)throw new Error('身份已切换，请显式核查原提交。');};check();
  if(record.attempted){
   phase('lookup');
   const receipt=await this.transport(record.identity.subject,'/command-lookups',{method:'POST',body:JSON.stringify({kind:record.kind,idempotencyKey:record.key})});
   check();
   if(receipt.status!=='not_found'){this.clear(record);return receipt;}
  }
  if(!record.body)throw new Error(message.content);
  record.attempted=true;this.write(record); // Persist before fetch, including requests that never arrive.
  check();phase('send');
  let body:BodyInit=JSON.stringify({...record.body,idempotencyKey:record.key});
  if(record.kind==='attachment.import'){
   const file=this.files.get(record.key);if(!file)throw new Error('请重新选择原文件，以原提交身份核查并继续。');
   const form=new FormData();form.set('file',file);form.set('idempotencyKey',record.key);body=form;
  }
  const result=await this.transport(record.identity.subject,record.path,{method:record.method,body,signal:AbortSignal.timeout(15000)});
  check();this.clear(record);return result;
 }
 private async guarded(record:Submission){
  if(this.inFlight)throw new Error('提交仍在进行，请稍后核查。');this.inFlight=true;
  const phase:{value:'lookup'|'send'}={value:'lookup'};
  try{return await this.execute(record,value=>{phase.value=value;});}catch(error){
   const status=(error as {status?:number}).status;
   // A definite rejection can end this attempt. Transport/5xx remain unknown.
   if(status&&[400,401,403,404,409,410,422].includes(status)&&(error as {code?:string}).code!=='STALE_IDENTITY_RESPONSE'){
    if(phase.value==='send')this.clear(record);else{delete record.body;this.write(record);}
   }
   throw error;
  }finally{this.inFlight=false;this.changed();}
 }
 async command(subject:string,path:string,body:RecordBody,method='POST',environmentId='',file?:File){
  if(this.preparing||this.inFlight)throw new Error('提交仍在进行，请稍后核查。');this.preparing=true;
  try{
  if(!this.identity||this.identity.subject!==subject)throw new Error('身份尚未验证，请稍后重试。');
  const identity={...this.identity},generation=this.generation;body=structuredClone(body);let record=this.pending();
  if(record){
   if(record.path!==path||record.method!==method)throw new Error(message.pending);
   const original={...body,...record.fixed};if(await digest(original)!==record.digest)throw new Error(message.changed);
   this.assertIdentity(record);if(generation!==this.generation)throw new Error('身份已切换，请重新核查。');record.body=original;this.write(record);
  }else{
   const fixed:RecordBody={};for(const field of ['clientMessageId','expectedRevision','expectedTitleRevision','expectedDraftHash','operationHash','expectedAuthorizationRevision'])if(field in body)fixed[field]=body[field];
   record={identity,path,method,kind:commandKind(path,method),key:crypto.randomUUID(),digest:await digest(body),body:structuredClone(body),fixed,attempted:false,context:{environmentId,conversationId:path.match(/^\/conversations\/([^/]+)/)?.[1]??''}};
   this.assertIdentity(record);if(generation!==this.generation)throw new Error('身份已切换，请重新核查。');this.write(record);
  }
  if(file)this.files.set(record.key,file);return await this.guarded(record);
  }finally{this.preparing=false;}
 }
 async resume(){const record=this.pending();if(!record)throw new Error('没有待核查提交。');return {submission:record,result:await this.guarded(record)};}
}
