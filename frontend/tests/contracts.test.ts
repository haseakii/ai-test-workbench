import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import canonicalize from 'canonicalize';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const golden=JSON.parse(fs.readFileSync('../contracts/golden.json','utf8'));
for(const row of golden.cases)test('Java/TypeScript shared vector: '+row.name,()=>{
 const output=canonicalize(JSON.parse(row.input));assert.equal(output,row.canonical);
 assert.equal(createHash('sha256').update(output!).digest('hex'),row.sha256);
});
const schema=JSON.parse(fs.readFileSync('../contracts/core.schema.json','utf8'));
const ajv=new Ajv2020({strict:true});addFormats(ajv);ajv.addSchema(schema);
test('safe integers reject the database-only larger range',()=>{
 const check=ajv.compile({$ref:schema.$id+'#/$defs/safeInteger'});
 assert(check(9007199254740991));assert(!check(9007199254740992));assert(!check(-1));assert(!check(1.5));
});
test('policy identity cannot masquerade as a human signature',()=>{
 const check=ajv.compile({$ref:schema.$id+'#/$defs/authority'});
 assert(check({kind:'human',subjectId:'reviewer',qualificationRef:'q1'}));
 assert(check({kind:'policy',grantId:'g',grantRevision:1,grantHash:'a'.repeat(64),issuerId:'issuer',serviceIdentityRef:'service',evaluationProofRef:'proof'}));
 assert(!check({kind:'policy',subjectId:'reviewer',qualificationRef:'q1'}));
});
test('admission schema binds scope and six-digit UTC time',()=>{
 const check=ajv.compile({$ref:schema.$id+'#/$defs/admissionTicket'});
 const ticket={ticketId:'00000000-0000-4000-8000-000000000001',bindingId:'00000000-0000-4000-8000-000000000002',executionContentHash:'a'.repeat(64),authorizationRevision:1,approvalRef:'approval',approvalRevision:1,notBefore:'2026-10-26T00:00:00.000000Z',notAfter:'2026-10-26T01:00:00.000000Z',authorityRef:'authority'};
 assert(check(ticket));assert(!check({...ticket,notAfter:'2026-10-26T01:00:00+08:00'}));assert(!check({...ticket,authorizationRevision:9007199254740992}));
});
test('state catalogue covers fast terminal and cancellation before active',()=>{
 const states=JSON.parse(fs.readFileSync('../docs/design/contracts/state-transitions.json','utf8'));
 const find=(layer:string,id:string)=>states.layers[layer].transitions.find((r:any)=>r.id===id);
 assert(find('node','N10').from.includes('dispatching'));
 assert(find('node','N09').from.includes('dispatching'));
 assert(find('binding','B12').from.includes('acceptance_unknown'));
 assert(find('binding','B09').from.includes('submitting'));
});
const providerSchema=JSON.parse(fs.readFileSync('../contracts/provider.schema.json','utf8'));
const providerVectors=JSON.parse(fs.readFileSync('../contracts/provider-vectors.json','utf8'));
const providerAjv=new Ajv2020({strict:true});addFormats(providerAjv);providerAjv.addSchema(providerSchema);
test('all twelve full Provider request and response vectors satisfy the generated schema',()=>{
 const methods=JSON.parse(providerSchema.$comment).methods;assert.equal(methods.length,12);
 for(const method of methods)for(const part of ['request','response']){
  const check=providerAjv.compile({$ref:providerSchema.$id+'#/$defs/'+method+(part==='request'?'Request':'Response')});
  assert(check(providerVectors.methods[method][part]),method+' '+part+': '+JSON.stringify(check.errors));
 }
});
test('full start vectors share content, complete spec and command hash domains',()=>{
 const request=providerVectors.methods.start.request.request,spec=structuredClone(request.spec),hash=(v:any)=>createHash('sha256').update(canonicalize(v)!).digest('hex');
 assert.equal(hash(spec),providerVectors.specHash);delete spec.admission;assert.equal(hash(spec),providerVectors.executionContentHash);
 assert.equal(hash({method:'start',body:{submission:request.submission,spec:request.spec}}),providerVectors.commandRequestHash);
});
test('full schema refuses false nonacceptance proof, unknown coercion and unsafe revisions',()=>{
 const lookup=providerAjv.compile({$ref:providerSchema.$id+'#/$defs/SubmissionLookup'});
 assert(!lookup({status:'not_found',observedAt:'2026-10-26T00:00:00.000001Z',authoritativeNonAcceptance:true}));
 const snapshot=providerAjv.compile<Record<string,unknown>>({$ref:providerSchema.$id+'#/$defs/ExecutionSnapshot'}),sample=providerVectors.methods.getSnapshot.response.value;
 assert(snapshot(sample));assert(!snapshot({...sample,executionActive:'false'}));assert(!snapshot({...sample,snapshotRevision:9007199254740992}));
 const response=providerAjv.compile({$ref:providerSchema.$id+'#/$defs/respondPermissionRequest'}),allow=providerVectors.methods.respondPermission.request;
 const forged=structuredClone(allow);forged.request.approvedOperation.authority={kind:'policy',subjectId:'reviewer',qualificationRef:'q'};
 assert(response(allow));assert(!response(forged));
});
