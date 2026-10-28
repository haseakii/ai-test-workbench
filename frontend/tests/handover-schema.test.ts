import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import canonicalize from 'canonicalize';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

// Wire-only synthetic projections. These cases do not assert export authorization or DB behavior.
const schema=JSON.parse(fs.readFileSync('../contracts/platform-responses.schema.json','utf8'));
const ajv=new Ajv2020({strict:true});addFormats(ajv);ajv.addSchema(schema);
const check=(name:string)=>ajv.compile({$ref:schema.$id+'#/$defs/ResponseHandover'+name});
const id=(n:number)=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const sha='a'.repeat(64),other='b'.repeat(64),at='2026-10-26T12:00:00.000001Z';
const values:Record<string,any>={
 Conversation:{conversationId:id(1),environmentId:id(2),inputRevision:3,revision:4,eventWatermark:6,state:'open'},
 Context:{reference:id(8),sha256:sha,purpose:'investigation',inputRevision:3,state:'active',invalidationCode:null},
 Authority:{kind:'human',subjectId:id(5),qualificationRef:'membership:'+id(5)+':2'},
 CsvScope:{detailAttachmentId:id(6),detailSha256:sha,reportAttachmentId:id(7),reportSha256:other,from:'2026-10-25T00:00:00.000000Z',to:'2026-10-26T00:00:00.000000Z',timeZone:'UTC',currency:'EUR',status:'completed'},
 Execution:{bindingId:id(3),nodeRunId:id(4),providerId:'persistent-synthetic',providerRunId:id(10),state:'terminal',acceptedAt:'2026-10-26T11:00:00.000001Z',closedAt:at,ownership:'inactive',outcome:'succeeded',effectStatus:'verified',snapshotRevision:4,authoritativeSnapshotHash:sha,receiptRef:id(10),stopRequested:false,providerCancelRequested:false,closureProofRef:'fixture-closure:'+id(10),providerEventWatermark:4,providerResumeCursor:'cursor+/=opaque-value'},
 Input:{attachmentId:id(6),role:'detail',sha256:sha,bytes:120,state:'active',authorizationRef:'grant:'+id(5)+':'+id(2)+':2'},
 CsvResult:{differenceCount:2,detailTotal:'350.00',reportTotal:'360.00',totalDifference:'10.00',currency:'EUR',algorithm:'report-minus-detail-v1'},
 Reservation:{bindingId:id(3),subjectId:id(5),period:'2026-10-25',state:'settled',totalAccountKey:'total',modelAccountKey:'model:fixture-v1'},
 AccountBalance:{subjectId:id(5),accountKey:'model:fixture-v1',period:'2026-10-25',turnLimit:100,reserved:0,consumed:1},
 Verification:{artifactId:id(11),resultRevision:1,subjectId:id(5),resolved:false},
 Unresolved:{code:'AWAIT_USER_VERIFICATION',bindingId:id(3),nextCheck:'请核对当前结果。'},
 Responsibility:{nextSubjectId:id(5),status:'assigned',nextChecks:['请核对当前结果。']}
};
values.Operation={approvalId:id(9),bindingId:id(3),approvalRevision:2,state:'approved',operationHash:sha,scope:values.CsvScope,authority:values.Authority,inputRevision:3,environmentRevision:1,authorizationRevision:2,validUntil:'2026-10-26T12:10:00.000000Z'};
values.Artifact={artifactId:id(11),bindingId:id(3),sha256:sha,bytes:100,state:'ready',resultRevision:1,inputRevision:3,result:values.CsvResult};
values.Package={schemaVersion:'fixed-csv-handover-v1',generatedAt:at,conversation:values.Conversation,context:values.Context,operation:values.Operation,executions:[values.Execution],inputs:[values.Input],artifacts:[values.Artifact],reservations:[values.Reservation],accountBalances:[values.AccountBalance],verifications:[values.Verification],unresolved:[values.Unresolved],responsibility:values.Responsibility};
const response=()=>({package:structuredClone(values.Package),packageSha256:createHash('sha256').update(canonicalize(values.Package)!).digest('hex')});

test('complete fixed CSV handover wire projection satisfies the response contract',()=>{
 const validate=check('');const vector=response();assert(validate(vector),JSON.stringify(validate.errors));
 assert.equal(vector.packageSha256,createHash('sha256').update(canonicalize(vector.package)!).digest('hex'));
 assert.equal(JSON.parse(schema.$comment).operations.handover.$ref,'#/$defs/ResponseHandover');
});
test('each Java handover record requires every field and refuses additional fields',()=>{
 for(const [name,sample] of Object.entries(values) as [string,Record<string,unknown>][]){
  const validate=check(name),definition=schema.$defs['ResponseHandover'+name];assert(validate(sample),name+JSON.stringify(validate.errors));
  assert.equal(definition.additionalProperties,false,name);assert.deepEqual([...definition.required].sort(),Object.keys(sample).sort(),name);
  assert(!validate({...sample,rawBody:'must not leak'}),name+' additional field');
  for(const field of Object.keys(sample)){const missing={...sample};delete missing[field];assert(!validate(missing),name+'.'+field+' missing');}
 }
});
test('nullable historical facts retain explicit null instead of invented references',()=>{
 const vector=response();vector.package.context=null;vector.package.operation=null;
 Object.assign(vector.package.executions[0],{providerId:null,providerRunId:null,state:'acceptance_unknown',acceptedAt:null,closedAt:null,ownership:'unknown',outcome:null,effectStatus:'unknown',snapshotRevision:null,authoritativeSnapshotHash:null,receiptRef:null,providerCancelRequested:null,closureProofRef:null,providerEventWatermark:null,providerResumeCursor:null});
 Object.assign(vector.package.reservations[0],{state:'reserved',totalAccountKey:null,modelAccountKey:null});
 vector.package.artifacts[0].result=null;vector.package.inputs[0].authorizationRef=null;
 vector.package.responsibility={nextSubjectId:null,status:'unassigned',nextChecks:[]};
 assert(check('')(vector));assert(!check('Conversation')({...values.Conversation,conversationId:null}));assert(!check('Context')({...values.Context,sha256:null}));
});
test('handover numbers enforce nonnegative safe integers on primitive and nullable fields',()=>{
 const validate=check('Conversation');assert(validate({...values.Conversation,inputRevision:Number.MAX_SAFE_INTEGER}));
 for(const inputRevision of [-1,1.5,9007199254740992,'3',null])assert(!validate({...values.Conversation,inputRevision}));
 for(const snapshotRevision of [-1,1.5,9007199254740992,'4'])assert(!check('Execution')({...values.Execution,snapshotRevision}));
 assert(!check('Input')({...values.Input,bytes:-1}));assert(!check('AccountBalance')({...values.AccountBalance,consumed:9007199254740992}));
});
test('wire identifiers hashes times and exact Decimal text have strict lexical contracts',()=>{
 for(const conversationId of ['bad',id(1).toUpperCase().replace('4000','400G'),'00000000000040008000000000000001'])assert(!check('Conversation')({...values.Conversation,conversationId}));
 for(const sha256 of ['A'.repeat(64),'a'.repeat(63),null])assert(!check('Input')({...values.Input,sha256}));
 for(const generatedAt of ['2026-10-26T12:00:00Z','2026-10-26T12:00:00.0000001Z','2026-10-26T12:00:00.000001+00:00','2026-02-30T12:00:00.000000Z'])assert(!check('Package')({...values.Package,generatedAt}));
 for(const detailTotal of ['1e3','01.00','+1.00','1.0','100000000000000000.00',1.00])assert(!check('CsvResult')({...values.CsvResult,detailTotal}));
 assert(check('CsvResult')({...values.CsvResult,detailTotal:'-9999999999999900.00',reportTotal:'9999999999999900.00',totalDifference:'19999999999999800.00'}));
 assert(!check('Reservation')({...values.Reservation,period:'2026-02-30'}));assert(!check('CsvScope')({...values.CsvScope,currency:'eur'}));
});
test('states and approval identity constraints match the Java projection',()=>{
 assert(!check('Context')({...values.Context,state:'active',invalidationCode:'INPUT_CHANGED'}));assert(check('Context')({...values.Context,state:'stale',invalidationCode:'INPUT_CHANGED'}));
 assert(!check('Authority')({...values.Authority,kind:'human',subjectId:null}));assert(check('Authority')({kind:'unknown',subjectId:null,qualificationRef:null}));
 assert(!check('Execution')({...values.Execution,ownership:'unknown'}));assert(!check('Execution')({...values.Execution,state:'succeeded'}));assert(!check('Execution')({...values.Execution,outcome:'unknown'}));
 assert(!check('Responsibility')({...values.Responsibility,status:'unassigned'}));assert(!check('Responsibility')({...values.Responsibility,nextSubjectId:null}));
 assert(!check('Operation')({...values.Operation,state:'accepted'}));assert(!check('Input')({...values.Input,role:'prompt'}));
});
test('frozen reservation pair is either explicit unknown or exact total and model keys',()=>{
 assert(check('Reservation')({...values.Reservation,totalAccountKey:null,modelAccountKey:null}));
 for(const patch of [{totalAccountKey:null},{modelAccountKey:null},{totalAccountKey:'other'},{modelAccountKey:'model:'},{modelAccountKey:'model:/private/path'}])assert(!check('Reservation')({...values.Reservation,...patch}));
});
test('stop requests and provider recovery facts stay separate and retain bounded raw cursors',()=>{
 const validate=check('Execution');assert(validate({...values.Execution,stopRequested:true,providerCancelRequested:false}));
 for(const stopRequested of [null,'false',0])assert(!validate({...values.Execution,stopRequested}));
 for(const providerCancelRequested of ['false',0])assert(!validate({...values.Execution,providerCancelRequested}));
 assert(validate({...values.Execution,providerCancelRequested:null,providerEventWatermark:null,providerResumeCursor:null,closureProofRef:null}));
 for(const providerEventWatermark of [-1,1.5,9007199254740992])assert(!validate({...values.Execution,providerEventWatermark}));
 for(const providerResumeCursor of ['', 'line1\nline2', 'x'.repeat(4097)])assert(!validate({...values.Execution,providerResumeCursor}));
 assert(validate({...values.Execution,providerResumeCursor:'opaque+/=cursor'}));assert(!validate({...values.Execution,closureProofRef:'/private/path'}));
});
test('handover forbids command envelopes private paths and control characters',()=>{
 const vector=response();assert(!check('')({...vector,commandId:id(12)}));assert(!check('')({...vector,packageSha256:'bad'}));
 assert(!check('Execution')({...values.Execution,providerRunId:'https://private-host/run'}));assert(!check('Input')({...values.Input,authorizationRef:'/private/path'}));
 for(const nextCheck of ['', '   ', 'line1\nline2', 'x'.repeat(1001)])assert(!check('Unresolved')({...values.Unresolved,nextCheck}));
 assert(!check('Unresolved')({...values.Unresolved,nextCheck:'\u1680\u2000\u3000'}));assert(check('Unresolved')({...values.Unresolved,nextCheck:'\u00a0'}));
 assert(!check('Responsibility')({...values.Responsibility,nextChecks:['line1\u0085line2']}));
});
