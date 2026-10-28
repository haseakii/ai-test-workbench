/** Keep the owner-only handover response shape aligned with the immutable Java projection. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const java=fs.readFileSync(path.join(root,'backend/platform-server/src/main/java/io/aitest/workbench/handover/HandoverPackage.java'),'utf8');
const file=path.join(root,'contracts/platform-responses.schema.json');
const response=JSON.parse(fs.readFileSync(file,'utf8'));
const records=new Map();
const recordSource=new Map();
const declarations=[...java.matchAll(/public record (\w+)\s*\(([\s\S]*?)\)\s*\{/g)];
for(const [index,match] of declarations.entries()){
 const fields=match[2].split(',').map(field=>{const parts=field.trim().match(/^([\w<>]+)\s+(\w+)$/);if(!parts)throw new Error('Unsupported handover field: '+field);return [parts[2],parts[1]];});
 records.set(match[1],fields);
 recordSource.set(match[1],java.slice(match.index,declarations[index+1]?.index??java.length));
}
const enums=new Map([...java.matchAll(/public enum (\w+)\s*\{([^}]+)\}/g)].map(match=>[match[1],match[2].split(',').map(value=>value.trim())]));
const name=record=>'ResponseHandover'+(record==='HandoverPackage'?'Package':record);
const ref=record=>({$ref:'#/$defs/'+name(record)});
const nullable=value=>({anyOf:[value,{type:'null'}]});
const safe={type:'integer',minimum:0,maximum:9007199254740991};
const uuid={type:'string',format:'uuid',pattern:'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'};
const sha={type:'string',pattern:'^[a-f0-9]{64}$'};
const utc={type:'string',format:'date-time',pattern:'^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{6}Z$'};
const opaque={type:'string',pattern:'^[A-Za-z0-9_.:-]{1,180}$'};
const decimal={type:'string',pattern:'^-?(0|[1-9][0-9]{0,16})\\.[0-9]{2}$'};
const period={type:'string',format:'date',pattern:'^[0-9]{4}-[0-9]{2}-[0-9]{2}$'};
const text={type:'string',minLength:1,maxLength:1000,pattern:'^[^\\u0000-\\u001f\\u007f-\\u009f]+$',not:{pattern:'^[\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200a\\u2028\\u2029\\u205f\\u3000]*$'}};
const choices={
 'Conversation.state':['open','awaiting_user','awaiting_verification','resolved','handoff','archived_unconfirmed'],
 'Context.purpose':['understanding','investigation'],
 'Authority.kind':['human','policy','unknown'],
 'CsvScope.status':['completed','pending','cancelled'],
 'Operation.state':['pending','approved','denied','superseded','unknown'],
 'Execution.state':['prepared','submitting','acceptance_unknown','accepted','active','waiting_permission','reconciling','stopping','terminal','rejected','unknown'],
 'Execution.outcome':['succeeded','failed','cancelled','rejected'],
 'Input.role':['detail','report','other'],
 'Artifact.state':['ready','superseded','revoked','unknown'],
 'Reservation.state':['reserved','consumed','released','settled','unknown'],
 'Responsibility.status':['assigned','unassigned']
};
for(const [key,expected] of Object.entries(choices)){
 const [record,field]=key.split('.');
 const match=recordSource.get(record)?.match(new RegExp('choice\\(\\s*'+field+'\\s*,\\s*((?:"[^"]+"\\s*,?\\s*)+)\\)'));
 const actual=match?[...match[1].matchAll(/"([^"]+)"/g)].map(value=>value[1]):null;
 if(JSON.stringify(actual)!==JSON.stringify(expected))throw new Error('Handover choice values changed: '+key);
}
if(!java.includes('VERSION="fixed-csv-handover-v1"'))throw new Error('Handover schema version changed');
const nullableFields=new Set([
 'HandoverPackage.context','HandoverPackage.operation',
 'Context.inputRevision','Context.invalidationCode',
 'Authority.subjectId','Authority.qualificationRef','Operation.authority',
 'Operation.inputRevision','Operation.environmentRevision','Operation.authorizationRevision','Operation.validUntil',
 'Execution.providerId','Execution.providerRunId','Execution.acceptedAt','Execution.closedAt','Execution.outcome',
 'Execution.snapshotRevision','Execution.authoritativeSnapshotHash','Execution.receiptRef',
 'Execution.providerCancelRequested','Execution.closureProofRef','Execution.providerEventWatermark','Execution.providerResumeCursor',
 'Input.authorizationRef','Artifact.result','Reservation.totalAccountKey','Reservation.modelAccountKey',
 'Unresolved.bindingId','Responsibility.nextSubjectId'
]);
function fieldSchema(record,field,type){
 const key=record+'.'+field;let schema;
 if(type.startsWith('List<'))schema={type:'array',items:fieldSchema(record,field+'[]',type.slice(5,-1)),...(record==='HandoverPackage'?{uniqueItems:true}:{})};
 else if(type==='long'||type==='Long')schema=safe;
 else if(type==='boolean'||type==='Boolean')schema={type:'boolean'};
 else if(type==='UUID')schema=uuid;
 else if(records.has(type))schema=ref(type);
 else if(enums.has(type))schema={type:'string',enum:enums.get(type)};
 else if(type==='String'){
  if(key==='HandoverPackage.schemaVersion')schema={const:'fixed-csv-handover-v1'};
  else if(choices[key])schema={type:'string',enum:choices[key]};
  else if(field.toLowerCase().includes('sha256')||field==='operationHash'||field==='authoritativeSnapshotHash')schema=sha;
  else if(['generatedAt','from','to','acceptedAt','closedAt','validUntil'].includes(field))schema=utc;
  else if(field==='period')schema=period;
  else if(field==='currency')schema={type:'string',pattern:'^[A-Z]{3}$'};
  else if(['detailTotal','reportTotal','totalDifference'].includes(field))schema=decimal;
  else if(key==='CsvResult.algorithm')schema={const:'report-minus-detail-v1'};
  else if(key==='CsvScope.timeZone')schema={type:'string',minLength:1,$comment:'The server additionally validates this value with java.time.ZoneId.of.'};
  else if(key==='Execution.providerResumeCursor')schema={type:'string',minLength:1,maxLength:4096,pattern:'^[^\\u0000-\\u001f\\u007f-\\u009f]+$'};
  else if(key==='Unresolved.nextCheck'||key==='Responsibility.nextChecks[]')schema=text;
  else schema=opaque;
 }else throw new Error('Unknown handover field type: '+key+' '+type);
 return nullableFields.has(key)?nullable(schema):schema;
}
const generated={};
for(const [record,fields] of records){
 generated[name(record)]={type:'object',additionalProperties:false,required:fields.map(([field])=>field),properties:Object.fromEntries(fields.map(([field,type])=>[field,fieldSchema(record,field,type)]))};
}
const required=(property,value)=>({properties:{[property]:value},required:[property]});
const condition=(field,value,then)=>({if:required(field,{const:value}),then});
generated[name('Context')].allOf=[condition('state','active',required('invalidationCode',{type:'null'}))];
generated[name('Authority')].allOf=[condition('kind','human',required('subjectId',uuid))];
generated[name('Execution')].allOf=[{if:required('closedAt',{type:'string'}),then:required('ownership',{const:'inactive'})}];
generated[name('Reservation')].oneOf=[
 {properties:{totalAccountKey:{type:'null'},modelAccountKey:{type:'null'}}},
 {properties:{totalAccountKey:{const:'total'},modelAccountKey:{type:'string',pattern:'^model:[A-Za-z0-9_.:-]{1,174}$'}}}
];
generated[name('Responsibility')].allOf=[
 condition('status','assigned',required('nextSubjectId',uuid)),
 condition('status','unassigned',required('nextSubjectId',{type:'null'}))
];
generated.ResponseHandover={type:'object',additionalProperties:false,required:['package','packageSha256'],properties:{package:ref('HandoverPackage'),packageSha256:sha},$comment:'The package hash covers canonical JSON of the package projection. The server also enforces arithmetic totals, reference integrity, identity uniqueness and ordering, and time-range ordering; JSON Schema does not substitute for those checks.'};

// Fail on Java shape drift rather than silently broadening a new field to arbitrary JSON.
const known={
 HandoverPackage:'schemaVersion generatedAt conversation context operation executions inputs artifacts reservations accountBalances verifications unresolved responsibility',
 Conversation:'conversationId environmentId inputRevision revision eventWatermark state',
 Context:'reference sha256 purpose inputRevision state invalidationCode',Authority:'kind subjectId qualificationRef',
 CsvScope:'detailAttachmentId detailSha256 reportAttachmentId reportSha256 from to timeZone currency status',
 Operation:'approvalId bindingId approvalRevision state operationHash scope authority inputRevision environmentRevision authorizationRevision validUntil',
 Execution:'bindingId nodeRunId providerId providerRunId state acceptedAt closedAt ownership outcome effectStatus snapshotRevision authoritativeSnapshotHash receiptRef stopRequested providerCancelRequested closureProofRef providerEventWatermark providerResumeCursor',
 Input:'attachmentId role sha256 bytes state authorizationRef',CsvResult:'differenceCount detailTotal reportTotal totalDifference currency algorithm',
 Artifact:'artifactId bindingId sha256 bytes state resultRevision inputRevision result',Reservation:'bindingId subjectId period state totalAccountKey modelAccountKey',
 AccountBalance:'subjectId accountKey period turnLimit reserved consumed',Verification:'artifactId resultRevision subjectId resolved',
 Unresolved:'code bindingId nextCheck',Responsibility:'nextSubjectId status nextChecks'
};
if(records.size!==Object.keys(known).length)throw new Error('Handover record catalogue changed; review the response contract');
for(const [record,fields] of records)if(fields.map(([field])=>field).join(' ')!==known[record])throw new Error('Handover field catalogue changed: '+record);
for(const key of nullableFields){const [record,field]=key.split('.');if(!records.get(record)?.some(([found])=>found===field))throw new Error('Unknown nullable handover field: '+key);}
const metadata=JSON.parse(response.$comment);metadata.operations.handover={$ref:'#/$defs/ResponseHandover'};
const expected={...response,$defs:{...response.$defs,...generated},$comment:JSON.stringify(metadata)};
const output=JSON.stringify(expected,null,2)+'\n';
if(process.argv.includes('--check')){
 if(fs.readFileSync(file,'utf8')!==output)throw new Error('Handover response schema is stale; run node scripts/generate_handover_schema.mjs');
 console.log(JSON.stringify({handoverRecords:records.size,handoverDefinitions:Object.keys(generated).length,operation:'handover',stale:0}));
}else{fs.writeFileSync(file,output);console.log(JSON.stringify({handoverRecords:records.size,handoverDefinitions:Object.keys(generated).length,operation:'handover'}));}
