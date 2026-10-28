"""Verify the no-model context preparation on the actual owned synthetic HTTP service."""
import argparse,hashlib,json,uuid,datetime,time
from pathlib import Path
import synthetic_service_check as f

p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--validate-api',action='store_true');args=p.parse_args()
if args.validate_api:f.enable_api_contract_checks()
report={'scope':'actual synthetic HTTP/MySQL context preparation; no model or external resources','cases':[]}
def case(name,work):
 try:work();report['cases'].append({'name':name,'result':'pass'});print(name+': pass',flush=True)
 except Exception as error:report['cases'].append({'name':name,'result':'fail','error':str(error)});save();raise
def save():
 report.update({'passed':sum(c['result']=='pass' for c in report['cases']),'total':len(report['cases']),'apiResponseValidation':f.api_checks,'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()});Path(args.output).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
env=f.environment()
def closed(conversation):
 for _ in range(100):
  snapshot=f.call('alice','/conversations/'+conversation)
  if all(x['closed_at'] is not None for x in snapshot['executions']):return snapshot
  time.sleep(.1)
 raise AssertionError('The original binding did not close')
def empty():
 c=f.command('alice','/conversations',{'environmentId':env})['id'];key=str(uuid.uuid4());body={'expectedInputRevision':0,'idempotencyKey':key}
 result=f.call('alice','/conversations/'+c+'/understanding',body);assert result['state']=='needs_input' and result['purpose']=='understanding' and result['tools']==[] and result['modelCalls']==0 and result['questions']
 context=f.call('alice','/contexts/'+result['contextManifestRef']);assert context['manifest']['tools']==[] and context['manifest']['runtime']['tools']==[]
 assert f.call('alice','/conversations/'+c+'/understanding',body)==result
 snapshot=f.call('alice','/conversations/'+c);assert not snapshot['approvals'] and not snapshot['executions']
case('collect_only_understanding_without_execution',empty)
def frozen():
 c,plan,_,_=f.prepared(env);value=plan['plan'];context=f.call('alice','/contexts/'+value['contextManifestRef']);manifest=context['manifest'];assert manifest['purpose']=='investigation'
 digest=hashlib.sha256(json.dumps(manifest,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest();assert digest==value['contextManifestHash']==context['sha256']
 assert len(manifest['attachments'])==2 and len(manifest['configurations'])==3 and manifest['messages'] and all('text' not in m for m in manifest['messages'])
 for actor in ['bob','reviewer','admin']:f.call(actor,'/contexts/'+value['contextManifestRef'],expected=404)
 snapshot=f.call('alice','/conversations/'+c);f.command('alice','/conversations/'+c+'/messages',{'text':'New synthetic scope input','clientMessageId':str(uuid.uuid4()),'expectedRevision':snapshot['revision']})
 f.call('alice','/contexts/'+value['contextManifestRef'],expected=409);assert f.call('alice','/conversations/'+c)['approvals'][0]['state']=='superseded'
 f.command('alice','/conversations/'+c+'/stop',{});assert closed(c)['state']=='awaiting_user'
case('recomputable_plan_context_owner_acl_and_new_input_invalidation',frozen)
def revoked_source():
 c,plan,detail,_=f.prepared(env);key=str(uuid.uuid4());body={'expectedInputRevision':plan['plan']['inputRevision'],'idempotencyKey':key};understanding=f.call('alice','/conversations/'+c+'/understanding',body)
 f.command('alice','/attachments/'+detail['id']+'/revoke',{})
 f.call('alice','/contexts/'+understanding['contextManifestRef'],expected=409);f.call('alice','/conversations/'+c+'/understanding',body,expected=409);assert closed(c)['state']=='handoff'
case('source_revocation_invalidates_context_receipt_and_pending_binding',revoked_source)
save();print(json.dumps({'passed':report['passed'],'total':report['total']}))
