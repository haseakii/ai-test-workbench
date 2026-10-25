// Tests for design simulation only; not ExecutionProvider conformance or backend acceptance.
const test = require('node:test');
const assert = require('node:assert/strict');
const M = require('../model.js');
const tables = require('../../design/contracts/state-transitions.json');
function step(s, a, p) { return M.act(s, a, p); }
function ready(s = M.createState()) { return step(s, 'conditions', {text:'合成报表，UTC+8，已完成订单，EUR'}); }
function running() { return step(ready(), 'start'); }
function approved() { return step(M.createState('approval'), 'approve', {checked:true}); }
function blocked(s, a, p) {
  const next = step(s,a,p);
  assert.match(next.message,/操作被阻止/);
  assert.equal(next.revision,s.revision);
  assert.equal(next.startCommands,s.startCommands);
  assert.equal(next.permissionSends,s.permissionSends);
  assert.equal(next.operationsExecuted,s.operationsExecuted);
  return next;
}
test('empty conditions preserve the draft and deny dispatch',()=>{const s=blocked(M.createState(),'conditions',{text:' '});assert.equal(s.run,'draft');});
test('completion awaits user verification',()=>{const s=step(running(),'complete');assert.equal(s.run,'succeeded');assert.equal(s.issue,'awaiting_verification');assert.equal(s.verification,null);});
test('user confirmation targets the current result revision',()=>{const s=step(step(running(),'complete'),'verify');assert.equal(s.issue,'resolved');assert.equal(s.verification.resultRevision,1);});
test('reopen preserves result and verifier history',()=>{const old=step(step(running(),'complete'),'verify');const s=step(old,'new_round');assert.equal(s.round,2);assert.equal(s.results[0].verifiedBy,'合成提交人');assert.equal(old.binding.id,'demo-binding-1');assert.equal(s.binding.id,'demo-binding-2');});
test('duplicate start cannot create a second execution',()=>{const s=running();blocked(s,'start');assert.equal(s.startCommands,1);});
test('not_found does not free ownership or enable another node',()=>{const s=step(M.createState('timeout'),'lookup_not_found');assert.equal(s.binding.state,'acceptance_unknown');assert.equal(s.binding.ownerOccupied,true);blocked(s,'new_round');blocked(s,'start');});
test('found submission recovers the original binding without start',()=>{const old=M.createState('timeout');const s=step(old,'lookup_found');assert.equal(s.binding.id,old.binding.id);assert.equal(s.startCommands,1);assert.equal(s.binding.externalRef,'demo-run-1');});
test('final admission fence allows a new binding, never reopens old key',()=>{let s=step(M.createState('timeout'),'close_final');assert.equal(s.binding.state,'rejected');assert(s.binding.admissionFence);const old=s.binding.id;s=step(s,'default_provider',{provider:'direct'});s=step(s,'new_round');assert.notEqual(s.binding.id,old);assert.equal(s.binding.provider,'direct');});
test('changing defaults leaves running binding immutable',()=>{const old=running();const s=step(old,'default_provider',{provider:'direct'});assert.equal(s.defaultProvider,'direct');assert.deepEqual(s.binding,old.binding);blocked(s,'new_round');});
test('disconnect does not clear ownership',()=>{const s=step(running(),'disconnect');assert.equal(s.binding.executionActive,'unknown');assert.equal(s.binding.ownerOccupied,true);blocked(s,'new_round');});
test('cancel request and confirmation are separate',()=>{let s=step(running(),'cancel');assert.equal(s.run,'stopping');assert.equal(s.binding.ownerOccupied,true);s=step(s,'confirm_cancel');assert.equal(s.run,'cancelled');assert.equal(s.issue,'open');assert(M.safeClosed(s));});
test('completion may win a cancellation race, still awaits user verification',()=>{const s=step(step(running(),'cancel'),'complete');assert.equal(s.run,'succeeded');assert.equal(s.binding.outcome,'succeeded');assert.equal(s.issue,'awaiting_verification');});
test('unknown submission cannot be represented as cancelled',()=>{blocked(M.createState('timeout'),'cancel');});
test('approval checkbox and reason are required',()=>{const s=M.createState('approval');blocked(s,'approve',{checked:false});blocked(s,'deny',{comment:' '});assert.equal(step(s,'deny',{comment:'范围需调整'}).approval.decision,'denied');});
test('approval alone does not send or execute',()=>{const s=approved();assert.equal(s.approval.decision,'approved');assert.equal(s.approval.delivery,'ready');assert.equal(s.permissionSends,0);assert.equal(s.operationsExecuted,0);blocked(s,'approve',{checked:true});});
test('delivery alone is not an execution receipt',()=>{const s=step(approved(),'send_permission');assert.equal(s.approval.delivery,'delivered');assert.equal(s.operationsExecuted,0);blocked(s,'complete');});
test('native request expiry invalidates approval and prevents old option replay',()=>{const s=step(approved(),'expire_native');assert.equal(s.approval.decision,'superseded');blocked(s,'send_permission');});
test('safe resume preserves owner, old decision, and requires new approval',()=>{const old=approved();let s=step(step(old,'expire_native'),'prepare_new_permission');assert.equal(s.binding.id,old.binding.id);assert.notEqual(s.approval.requestId,old.approval.requestId);assert.equal(s.approvalHistory[0].decision,'superseded');assert.equal(s.approval.decision,'pending');assert.equal(s.operationsExecuted,0);blocked(s,'send_permission');s=step(s,'approve',{checked:true});s=step(s,'send_permission');s=step(s,'operation_receipt');assert.equal(s.operationsExecuted,1);});
test('business expiry prevents sending',()=>{const s=step(approved(),'expire_business');assert.equal(s.approval.decision,'expired');blocked(s,'send_permission');});
test('plan drift requires evidence revalidation and fresh approval',()=>{let s=step(approved(),'drift');blocked(s,'prepare_new_permission');s=step(s,'refresh_plan');s=step(s,'prepare_new_permission');assert.equal(s.approval.decision,'pending');assert.notEqual(s.approval.planHash,s.approvalHistory[0].planHash);assert.notEqual(s.approval.operationId,s.approvalHistory[0].operationId);blocked(s,'send_permission');});
test('permission delivery unknown cannot be resent or cancelled as safe',()=>{let s=step(approved(),'send_timeout');assert.equal(s.approval.delivery,'delivery_unknown');blocked(s,'send_permission');blocked(s,'prepare_new_permission');const stopping=step(s,'cancel');blocked(stopping,'confirm_cancel');s=step(s,'lookup_delivery');assert.equal(s.permissionSends,1);assert.equal(s.operationsExecuted,0);});
test('late permission delivery observation cannot erase a stop request',()=>{const s=step(step(step(approved(),'send_timeout'),'cancel'),'lookup_delivery');assert.equal(s.run,'stopping');assert.equal(s.binding.state,'stopping');assert.equal(s.stopRequested,true);blocked(s,'operation_receipt');});
test('inner policy deny overrides the business allow',()=>{const s=step(approved(),'inner_deny');assert.equal(s.approval.delivery,'denied_by_provider');blocked(s,'operation_receipt');assert.equal(s.operationsExecuted,0);});
test('unsupported approval bridge never sends',()=>{const s=step(M.createState('bridge'),'approve',{checked:true});blocked(s,'send_permission');assert.equal(s.permissionSends,0);});
test('one operation consumes approval once',()=>{const s=step(step(approved(),'send_permission'),'operation_receipt');assert.equal(s.operationsExecuted,1);blocked(s,'operation_receipt');});
test('sent permissions cannot be renewed on an assumption of no effect',()=>{const s=step(approved(),'send_permission');blocked(s,'expire_native');blocked(s,'drift');});
test('unknown capability and mismatch block dispatch',()=>{for(const scenario of ['mismatch','unknownCapability'])blocked(ready(M.createState(scenario)),'start');});
test('unknown usage blocks new billable work but allows observation and cancel',()=>{const s=step(running(),'usage_unknown');blocked(s,'billable_step');assert.equal(step(s,'cancel').run,'stopping');blocked(ready(M.createState('usage')),'start');});
test('evidence mismatch and corrupt artifacts deny deterministic delivery',()=>{blocked(step(running(),'evidence_mismatch'),'complete');blocked(step(running(),'artifact_corrupt'),'complete');});
test('integrity failure after delivery blocks confirmation without re-execution',()=>{const s=step(step(running(),'complete'),'artifact_corrupt');blocked(s,'verify');assert.equal(s.startCommands,1);});
test('duplicate events have no repeated projection or charge',()=>{const first=step(running(),'duplicate_event');const second=step(first,'duplicate_event');assert.equal(second.events.length,first.events.length);assert.equal(second.eventWatermark,first.eventWatermark);assert.equal(second.modelCalls,first.modelCalls);});
test('handoff keeps unresolved ownership and full evidence',()=>{const s=step(M.createState('timeout'),'handoff');assert.equal(s.issue,'handoff');assert.equal(s.binding.state,'acceptance_unknown');assert.equal(s.binding.ownerOccupied,true);blocked(s,'new_round');});
test('simulation never mutates input and all layer states are declared',()=>{for(const name of Object.keys(M.scenarios)){const s=M.createState(name), copy=JSON.stringify(s);step(s,'default_provider',{provider:'direct'});assert.equal(JSON.stringify(s),copy);for(const [key,value] of Object.entries({issue:s.issue,run:s.run,node:s.node,binding:s.binding.state,approval:s.approval?.decision || 'pending'}))assert(tables.layers[key].states.includes(value));}});
