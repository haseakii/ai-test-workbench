"""Targeted audit regressions against the actual local synthetic service."""
import argparse,json,time,uuid
from pathlib import Path
import synthetic_service_check as fixture
from urllib.request import Request,urlopen
from urllib.error import HTTPError

parser=argparse.ArgumentParser()
parser.add_argument('--base-url',default='http://127.0.0.1:18080')
parser.add_argument('--output',required=True)
parser.add_argument('--validate-api',action='store_true')
args=parser.parse_args();fixture.BASE=args.base_url
if args.validate_api:fixture.enable_api_contract_checks()
results=[]
def check(name,fn):
    try:detail=fn();results.append({'name':name,'result':'pass','evidence':detail})
    except Exception as e:results.append({'name':name,'result':'fail','error':str(e)})
    print(name+': '+results[-1]['result'],flush=True)

def revoked_receipt(route):
    env=fixture.environment();key=str(uuid.uuid4());body={'environmentId':env,'idempotencyKey':key}
    original=fixture.call('alice','/conversations',body)
    fixture.command('admin','/admin/grants/revoke',{'alias':'alice','environmentId':env,'role':'member'})
    try:
        if route=='replay':fixture.call('alice','/conversations',body,expected=404)
        elif route=='read':fixture.call('alice','/commands/'+original['commandId'],expected=404)
        else:fixture.call('alice','/command-lookups',{'kind':'conversation.create','idempotencyKey':key},expected=404)
    finally:fixture.command('admin','/admin/grants',{'alias':'alice','environmentId':env,'role':'member'})
    return {'commandId':original['commandId'],'currentScopeDenied':True}

def reject_fraction():
    objects=fixture.call('admin','/admin/configurations')
    existing=next(x for x in objects if x['kind']=='environment')
    fixture.command('admin','/admin/configurations',{'kind':'environment','name':'fraction-rejection-'+uuid.uuid4().hex[:8],'content':{**existing['draft'],'turnLimit':1.5}},expected=422)
    return {'fractionRejectedBeforePersistence':True}

def stale_result():
    env=fixture.environment();c,p,_,_=fixture.prepared(env);fixture.approve(p)
    before=fixture.wait_state(c,{'awaiting_verification'});artifact=before['artifacts'][0]
    fixture.command('alice','/conversations/'+c+'/messages',{'clientMessageId':str(uuid.uuid4()),'text':'新增材料改变当前输入修订','expectedRevision':before['revision']})
    after=fixture.call('alice','/conversations/'+c)
    fixture.command('alice','/results/'+artifact['id']+'/verification',{'resultRevision':1,'resolved':True,'opinion':'旧结果不能解决新输入'},expected=409)
    assert after['inputRevision']==before['inputRevision']+1 and after['artifacts']==[],after
    return {'conversationId':c,'oldResultExcluded':True}

def same_message_new_command_key():
    env=fixture.environment();c=fixture.command('alice','/conversations',{'environmentId':env})['id'];client=str(uuid.uuid4())
    body={'clientMessageId':client,'text':'重传同一条已保存消息','expectedRevision':1}
    first=fixture.command('alice','/conversations/'+c+'/messages',body)
    second=fixture.command('alice','/conversations/'+c+'/messages',body)
    snapshot=fixture.call('alice','/conversations/'+c)
    assert first['inputRevision']==second['inputRevision']==1 and len(snapshot['messages'])==1
    return {'conversationId':c,'savedMessages':1}

def unsupported_backend():
    fixture.command('admin','/admin/configurations',{'kind':'model','name':'unsupported-backend-'+uuid.uuid4().hex[:8],'content':{'backend':'unapproved_real_backend','modelCalls':True}},expected=422)
    return {'realBackendConfigurationRejected':True}

def new_attachment_invalidates_result():
    env=fixture.environment();c,p,_,_=fixture.prepared(env);fixture.approve(p)
    before=fixture.wait_state(c,{'awaiting_verification'});artifact=before['artifacts'][0]
    fixture.upload(c,'additional.csv',['1.00'])
    after=fixture.call('alice','/conversations/'+c)
    assert after['inputRevision']==before['inputRevision']+1 and after['artifacts']==[]
    fixture.call('alice','/artifacts/'+artifact['id']+'/download',expected=409)
    return {'attachmentAdvancesInputRevision':True,'oldDownloadDenied':True}

def conflicting_client_message():
    env=fixture.environment();c=fixture.command('alice','/conversations',{'environmentId':env})['id'];client=str(uuid.uuid4())
    body={'clientMessageId':client,'text':'原始消息内容','expectedRevision':1}
    fixture.command('alice','/conversations/'+c+'/messages',body)
    denied=fixture.command('alice','/conversations/'+c+'/messages',{**body,'text':'同一消息引用但内容不同'},expected=409)
    assert denied['error']['code']=='CLIENT_MESSAGE_CONFLICT'
    assert len(fixture.call('alice','/conversations/'+c)['messages'])==1
    return {'sameClientIdDifferentContentRejected':True}

def published_plan_invalidates_approval():
    env=fixture.environment();c,p,_,_=fixture.prepared(env)
    e=next(x for x in fixture.call('admin','/admin/configurations') if x['id']==env)
    fixture.command('admin','/admin/configurations/'+env+'/draft',{'expectedRevision':1,'expectedDraftHash':e['draftHash'],'content':{**e['draft'],'description':'new fixed environment scope'}},method='PATCH')
    fixture.command('admin','/admin/config-publications',{'objects':[{'id':env,'expectedRevision':1,'dependencyRevisions':e['published']['dependencyRevisions']}]})
    denied=fixture.approve(p,expected=409);assert denied['error']['code']=='CONFIGURATION_CHANGED'
    fixture.command('alice','/conversations/'+c+'/stop',{});fixture.wait_state(c,{'handoff'})
    return {'environmentRevision':2,'oldApprovalRejected':True}

def stream(c,cursor,subject='alice'):
    return urlopen(Request(fixture.BASE+'/api/conversations/'+c+'/stream',headers={'X-Test-Subject':subject,'Last-Event-ID':cursor}),timeout=4)

def frame(connection):
    event={};data=[]
    while True:
        raw=connection.readline()
        if not raw:raise EOFError('SSE connection ended before a complete event')
        line=raw.decode().rstrip('\r\n')
        if not line:
            if event or data:break
            continue
        if ':' in line:
            name,value=line.split(':',1)
            if name=='data':data.append(value.lstrip())
            else:event[name]=value.strip()
    if data:event['data']=json.loads('\n'.join(data))
    return event

def sse_resume_and_revocation():
    env=fixture.environment();c=fixture.command('alice','/conversations',{'environmentId':env})['id']
    snapshot=fixture.call('alice','/conversations/'+c);watermark=snapshot['eventWatermark']
    with stream(c,snapshot['resumeCursor']) as first:
        assert frame(first)['data']['events']==[]
        fixture.command('alice','/conversations/'+c+'/messages',{'clientMessageId':str(uuid.uuid4()),'text':'W 后第一条持久事件','expectedRevision':snapshot['revision']})
        page=frame(first)
        while not page['data']['events']:page=frame(first)
        assert page['data']['events'][0]['sequence']==watermark+1
        cursor=page['id'];assert cursor==page['data']['cursor']
    snapshot=fixture.call('alice','/conversations/'+c)
    with stream(c,cursor) as second:
        assert frame(second)['data']['events']==[]
        fixture.command('alice','/conversations/'+c+'/messages',{'clientMessageId':str(uuid.uuid4()),'text':'恢复后下一条持久事件','expectedRevision':snapshot['revision']})
        page=frame(second)
        while not page['data']['events']:page=frame(second)
        assert page['data']['events'][0]['sequence']==watermark+2
        fixture.command('admin','/admin/grants/revoke',{'alias':'alice','environmentId':env,'role':'member'})
        try:
            denied=frame(second);assert denied['event']=='authorization-lost' and 'events' not in denied['data']
            fixture.call('alice','/conversations/'+c+'/events',expected=404)
        finally:fixture.command('admin','/admin/grants',{'alias':'alice','environmentId':env,'role':'member'})
    return {'snapshotWatermark':watermark,'resumedSequences':[watermark+1,watermark+2],'revocationStopsBodyEvents':True}

def cross_subject_same_key():
    env=fixture.environment();key=str(uuid.uuid4());body={'environmentId':env,'idempotencyKey':key}
    owner=fixture.call('alice','/conversations',body)
    # Bob has no local receipt or environment access: Alice's key must not produce 409.
    denied=fixture.call('bob','/conversations',body,expected=404);assert denied['error']['code']=='NOT_FOUND'
    missing=fixture.call('bob','/command-lookups',{'kind':'conversation.create','idempotencyKey':key})
    assert missing=={'status':'not_found','authoritativeNonAcceptance':False}
    fixture.call('bob','/commands/'+owner['commandId'],expected=404)
    fixture.command('admin','/admin/grants',{'alias':'bob','environmentId':env,'role':'member'})
    try:
        second=fixture.call('bob','/conversations',body)
        assert owner['id']!=second['id'] and owner['commandId']!=second['commandId']
        assert fixture.call('alice','/conversations',body)==owner
        assert fixture.call('bob','/conversations',body)==second
        conflict=fixture.call('bob','/conversations',{**body,'environmentId':str(uuid.uuid4())},expected=409)
        assert conflict['error']['code']=='IDEMPOTENCY_CONFLICT'
    finally:fixture.command('admin','/admin/grants/revoke',{'alias':'bob','environmentId':env,'role':'member'})
    return {'foreignSubjectCollisionDoesNotReturn409':True,'foreignReceiptDenied':True,'sameKeySeparateReceipts':True,'ownChangedBodyReturns409':True}

def command_kind_namespace():
    env=fixture.environment();key=str(uuid.uuid4());conversation=fixture.call('alice','/conversations',{'environmentId':env,'idempotencyKey':key})
    saved=fixture.call('alice','/conversations/'+conversation['id']+'/messages',{'clientMessageId':str(uuid.uuid4()),'text':'同一主体同一键，不同命令命名空间','expectedRevision':1,'idempotencyKey':key})
    assert saved['commandId']!=conversation['commandId']
    for kind,expected in [('conversation.create',conversation),('message.save',saved)]:
        assert fixture.call('alice','/command-lookups',{'kind':kind,'idempotencyKey':key})==expected
    assert fixture.call('alice','/command-lookups',{'kind':'conversation.title','idempotencyKey':key})['status']=='not_found'
    return {'sameKeyDifferentKindsAreIndependent':True,'lookupsRespectKind':True}

def authentication_precedes_known_key():
    env=fixture.environment();key=str(uuid.uuid4());body={'environmentId':env,'idempotencyKey':key}
    fixture.call('alice','/conversations',body)
    req=Request(fixture.BASE+'/api/conversations',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'},method='POST')
    try:
        with urlopen(req,timeout=5):raise AssertionError('Missing identity accepted')
    except HTTPError as e:
        assert e.code==401
        assert json.load(e)['error']['code']=='TEST_IDENTITY_REQUIRED'
    return {'knownKeyWithoutIdentityReturns401':True,'cachedResponseUnavailable':True}

def artifact_receipt_rechecks_source():
    env=fixture.environment();c,p,source,_=fixture.prepared(env);fixture.approve(p)
    artifact=fixture.wait_state(c,{'awaiting_verification'})['artifacts'][0];key=str(uuid.uuid4())
    body={'resultRevision':1,'resolved':True,'opinion':'当前版本复核通过','idempotencyKey':key}
    receipt=fixture.call('alice','/results/'+artifact['id']+'/verification',body)
    assert receipt['state']=='resolved'
    fixture.command('alice','/attachments/'+source['id']+'/revoke',{})
    denied=fixture.call('alice','/results/'+artifact['id']+'/verification',body,expected=409)
    assert denied['error']['code']=='SOURCE_REVOKED_OR_CHANGED'
    denied=fixture.call('alice','/commands/'+receipt['commandId'],expected=409)
    assert denied['error']['code']=='SOURCE_REVOKED_OR_CHANGED'
    return {'equalArtifactCommandReauthorized':True,'cachedArtifactReceiptReadReauthorized':True,'sourceRevoked':True}

check('R01 mutation receipt replay checks current object scope',lambda:revoked_receipt('replay'))
check('R02 commandId lookup checks current object scope',lambda:revoked_receipt('read'))
check('R03 idempotency-key lookup checks current object scope',lambda:revoked_receipt('lookup'))
check('R04 fractional configuration quota is rejected',reject_fraction)
check('R05 new input invalidates old current result',stale_result)
check('R06 clientMessageId deduplicates across command keys',same_message_new_command_key)
check('R07 synthetic configuration rejects real backend claims',unsupported_backend)
check('R08 new attachment supersedes old result and download',new_attachment_invalidates_result)
check('R09 clientMessageId rejects changed content',conflicting_client_message)
check('R10 publishing environment invalidates old plan',published_plan_invalidates_approval)
check('R11 SSE snapshot W reconnects W+1 and revocation ends access',sse_resume_and_revocation)
check('R12 subject namespace prevents foreign-key existence and receipt disclosure',cross_subject_same_key)
check('R13 command kind namespaces identical keys and lookups',command_kind_namespace)
check('R14 authentication precedes known idempotency key',authentication_precedes_known_key)
check('R15 cached artifact receipt rechecks current source permission',artifact_receipt_rechecks_source)
report={'scope':'actual Java/MySQL synthetic service audit regressions','passed':sum(r['result']=='pass' for r in results),'total':len(results),'cases':results}
report['fixtureQuotaAdjustments']=fixture.fixture_quota_adjustments
if args.validate_api:report['apiContract']=fixture.api_checks
out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passed':report['passed'],'total':report['total']}))
raise SystemExit(0 if report['passed']==report['total'] else 1)
