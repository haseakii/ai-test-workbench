"""Exercise the actual local D1/D2 service with synthetic CSV, never real resources."""
import argparse, datetime, hashlib, json, time, uuid
from urllib.parse import urlencode
import atexit,select,subprocess
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

parser=argparse.ArgumentParser()
parser.add_argument('--base-url',default='http://127.0.0.1:18080')
parser.add_argument('--output',default='docs/reviews/implementation-d0-d2/service-checks.json')
parser.add_argument('--runtime-dir')
parser.add_argument('--runtime-config')
parser.add_argument('--java-home')
parser.add_argument('--validate-api',action='store_true')
args=parser.parse_args() if __name__=="__main__" else parser.parse_args([])
BASE=args.base_url
report={'scope':'actual local Java service and MySQL, synthetic identity/backend/data only','cases':[]}
api_validator=None
api_checks={'checked':0,'operations':[],'responseCounts':{}}
fixture_quota_adjustments=[]
def enable_api_contract_checks():
    global api_validator
    if api_validator:return
    root=Path(__file__).resolve().parents[1]
    api_validator=subprocess.Popen(['node',str(root/'scripts/check_api_responses.mjs')],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
    def close():
        api_validator.stdin.close()
        try:api_validator.wait(timeout=3)
        except subprocess.TimeoutExpired:api_validator.kill();api_validator.wait()
    atexit.register(close)
def validate_api(method,path,status,body):
    if not api_validator:return
    api_validator.stdin.write(json.dumps({'method':method,'path':'/api'+path.split('?')[0],'status':status,'body':body},ensure_ascii=False)+'\n');api_validator.stdin.flush()
    assert select.select([api_validator.stdout],[],[],5)[0],'API validator did not respond'
    result=json.loads(api_validator.stdout.readline());assert result['ok'],result
    api_checks['checked']+=1
    if result['operationId'] not in api_checks['operations']:api_checks['operations'].append(result['operationId'])
    counts=api_checks['responseCounts'].setdefault(result['operationId'],{});status=str(result['status']);counts[status]=counts.get(status,0)+1

def call(subject,path,body=None,method=None,expected=200):
    data=None if body is None else json.dumps(body,ensure_ascii=False).encode()
    headers={'X-Test-Subject':subject}
    if data is not None:headers['Content-Type']='application/json'
    req=Request(BASE+'/api'+path,data=data,headers=headers,method=method or ('POST' if body is not None else 'GET'))
    try:
        with urlopen(req,timeout=10) as r:status=r.status;raw=r.read()
    except HTTPError as e:status=e.code;raw=e.read()
    assert status==expected,(subject,path,status,raw[:1000].decode(errors='replace'))
    try:
        value=json.loads(raw);validate_api(req.get_method(),path,status,value);return value
    except json.JSONDecodeError:return raw

def command(subject,path,body,**kw):
    body={'idempotencyKey':str(uuid.uuid4()),**body};return call(subject,path,body,**kw)

def fixture_budget():
    # Repeated local suites retain real consumption. Never reset counters or bypass admission.
    q=call('alice','/quota');today=datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    accounts=[r for r in q['accounts'] if r['period']==today]
    used=max([0,*[r['consumed']+r['reserved'] for r in accounts]])
    limit=min([r['turnLimit'] for r in accounts],default=100)
    if limit-used<20:
        assert all(r['reserved']==0 for r in accounts) and all(s['occupied']==0 for s in q['slots']),'Finish owned work before changing a fixture policy'
        identity=call('alice','/session');new_limit=used+100
        command('admin','/admin/quotas',{'alias':'alice','turnLimit':new_limit,'slotLimit':q['slots'][0]['slotLimit'] if q['slots'] else 1,'expectedAuthorizationRevision':identity['authorizationRevision']})
        after=call('alice','/quota')
        assert [(r['account'],r['period'],r['consumed'],r['reserved']) for r in q['accounts']]==[(r['account'],r['period'],r['consumed'],r['reserved']) for r in after['accounts']]
        fixture_quota_adjustments.append({'subject':'alice','period':today,'beforeLimit':limit,'afterLimit':new_limit,'consumedAndReservedUnchanged':True,'reason':'synthetic repeat-run fixture headroom via audited admin API; no DB privileges or real resources'})
        limit=new_limit
    return limit

def environment(mode='normal'):
    turn_limit=fixture_budget()
    suffix=str(uuid.uuid4())[:8]
    m=command('admin','/admin/configurations',{'kind':'model','name':'fixture-model-'+suffix,'content':{'backend':'persistent_fixture','modelCalls':False}})
    w=command('admin','/admin/configurations',{'kind':'workflow','name':'readonly-'+suffix,'content':{'operation':'reconcile_csv_readonly_v1'}})
    e=command('admin','/admin/configurations',{'kind':'environment','name':'订单测试-'+suffix,'content':{'modelRef':m['id'],'workflowRef':w['id'],'timeZone':'Asia/Shanghai','turnLimit':turn_limit,'testMode':mode}})
    command('admin','/admin/config-publications',{'objects':[{'id':m['id'],'expectedRevision':0,'dependencyRevisions':{}},{'id':w['id'],'expectedRevision':0,'dependencyRevisions':{}},{'id':e['id'],'expectedRevision':0,'dependencyRevisions':{m['id']:0,w['id']:0}}]})
    for alias,role in [('alice','member'),('reviewer','reviewer')]:command('admin','/admin/grants',{'alias':alias,'environmentId':e['id'],'role':role})
    return e['id']

def upload(conversation,name,amounts):
    rows=['order_id,amount,currency,status,occurred_at']
    rows += [f'O{i+1:03},{amount},EUR,completed,2026-10-25T12:00:00Z' for i,amount in enumerate(amounts)]
    data=('\r\n'.join(rows)+'\r\n').encode()
    boundary='wb-'+uuid.uuid4().hex
    key=str(uuid.uuid4())
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="idempotencyKey"\r\n\r\n{key}\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/csv\r\n\r\n'.encode()+data+f'\r\n--{boundary}--\r\n'.encode())
    req=Request(BASE+'/api/conversations/'+conversation+'/attachments',data=body,headers={'X-Test-Subject':'alice','Content-Type':'multipart/form-data; boundary='+boundary},method='POST')
    with urlopen(req,timeout=10) as r:
        value=json.load(r);validate_api('POST','/conversations/'+conversation+'/attachments',r.status,value);return value

def prepared(env,valid_for=600):
    c=command('alice','/conversations',{'environmentId':env})
    command('alice','/conversations/'+c['id']+'/messages',{'text':'报表与明细金额不一致，请检查合成订单。','expectedRevision':1,'clientMessageId':str(uuid.uuid4())})
    d=upload(c['id'],'detail.csv',['100.00','200.00','50.00'])
    r=upload(c['id'],'report.csv',['100.00','190.00','70.00'])
    plan=command('alice','/conversations/'+c['id']+'/prepare',{'scope':{'detailAttachmentId':d['id'],'reportAttachmentId':r['id'],'from':'2026-10-25T00:00:00.000000Z','to':'2026-10-26T00:00:00.000000Z','timeZone':'Asia/Shanghai','currency':'EUR','status':'completed','expectedInputRevision':call('alice','/conversations/'+c['id'])['inputRevision'],'validForSeconds':valid_for}})
    return c['id'],plan,d,r

def approve(p,**kw):
    return command('reviewer','/approvals/'+p['approvalId']+'/decision',{'approve':True,'operationHash':p['operationHash'],'expectedRevision':p['revision'],'reason':'已核对固定范围和材料'},**kw)

def wait_state(c,states,timeout=12):
    until=time.monotonic()+timeout
    while time.monotonic()<until:
        state=call('alice','/conversations/'+c)
        if state['state'] in states:return state
        time.sleep(.15)
    raise AssertionError(('timed_out',state))

def check(name,fn):
    started=time.monotonic()
    try:detail=fn();report['cases'].append({'name':name,'result':'pass','seconds':round(time.monotonic()-started,3),'evidence':detail})
    except Exception as e:report['cases'].append({'name':name,'result':'fail','seconds':round(time.monotonic()-started,3),'error':str(e)})
    print(name+': '+report['cases'][-1]['result'],flush=True)

context={}
def full_flow():
    env=environment();c,p,d,r=prepared(env);approve(p);s=wait_state(c,{'awaiting_verification'})
    artifact=s['artifacts'][0]
    assert artifact['summary']['differenceCount']==2
    assert artifact['summary']['totalDifference']=='10.00'
    data=call('alice','/artifacts/'+artifact['id']+'/download')
    assert hashlib.sha256(data).hexdigest()==artifact['sha256']
    assert b'-10.00' in data and b'20.00' in data
    command('alice','/results/'+artifact['id']+'/verification',{'resultRevision':1,'resolved':True,'opinion':'已复算两条差异，与预期一致'})
    assert call('alice','/conversations/'+c)['state']=='resolved'
    context.update(environment=env,conversation=c,plan=p,artifact=artifact)
    return {'conversationId':c,'bindingId':p['bindingId'],'artifactId':artifact['id'],'csvSha256':artifact['sha256'],'differenceCount':2,'totalDifference':'10.00'}

def denied():
    c=context['conversation'];artifact=context['artifact']['id']
    for path in ['/conversations/'+c,'/conversations/'+c+'/events','/artifacts/'+artifact+'/download']:
        call('bob',path,expected=404)
    membership=call('pending','/session');options=call('pending','/workbench/options')
    if membership['state']!='active':assert options==[]
    assert all(o['id']!=context['environment'] for o in options)
    return {'cross_user_objects_denied':3,'unassigned_environment_absent':True,'membershipState':membership['state'],'allowedEnvironmentCount':len(options)}

def idempotency():
    env=context['environment'];key=str(uuid.uuid4());body={'environmentId':env,'idempotencyKey':key}
    a=call('alice','/conversations',body);b=call('alice','/conversations',body);assert a==b
    call('alice','/conversations',{**body,'environmentId':str(uuid.uuid4())},expected=409)
    return {'conversationId':a['id'],'sameReceipt':True,'changedBodyRejected':True}

def cursor():
    c=context['conversation'];s=call('alice','/conversations/'+c);page=call('alice','/conversations/'+c+'/events?cursor='+s['resumeCursor'])
    assert page['events']==[]
    command('alice','/conversations/'+c+'/title',{'title':'合成差异已复核','expectedTitleRevision':s['titleRevision']},method='PATCH')
    page=call('alice','/conversations/'+c+'/events?cursor='+s['resumeCursor']);assert len(page['events'])==1;assert page['events'][0]['sequence']>s['eventWatermark']
    return {'snapshotWatermark':s['eventWatermark'],'nextSequence':page['events'][0]['sequence']}

def reject_scope():
    c,p,_,_=prepared(context['environment'])
    command('reviewer','/approvals/'+p['approvalId']+'/decision',{'approve':True,'operationHash':'b'*64,'expectedRevision':1,'reason':'已核对但提交错误哈希'},expected=409)
    command('alice','/approvals/'+p['approvalId']+'/decision',{'approve':True,'operationHash':p['operationHash'],'expectedRevision':1,'reason':'提交人不能自行批准'},expected=403)
    command('alice','/conversations/'+c+'/stop',{})
    wait_state(c,{'handoff'})
    return {'tamperedHashRejected':True,'selfApprovalRejected':True,'stoppedBinding':p['bindingId']}

def wait_binding(c,states,timeout=10):
    until=time.monotonic()+timeout
    while time.monotonic()<until:
        s=call('alice','/conversations/'+c)
        if s['executions'] and s['executions'][-1]['state'] in states:return s
        time.sleep(.15)
    raise AssertionError(('binding_timeout',s))

def queued_expiry():
    env=environment('hold_after_accept');first,p,_,_=prepared(env);approve(p)
    wait_binding(first,{'accepted'})
    second,q,_,_=prepared(env,valid_for=2);approve(q)
    s=wait_state(second,{'handoff'},timeout=8)
    assert s['executions'][0]['accepted_at'] is None
    command('alice','/conversations/'+first+'/stop',{});wait_state(first,{'handoff'})
    return {'heldBinding':p['bindingId'],'expiredBinding':q['bindingId'],'expiredTaskAccepted':False}

def dropped_receipt():
    env=environment('drop_receipt');before=call('alice','/quota')
    c,p,_,_=prepared(env);approve(p);s=wait_state(c,{'awaiting_verification'})
    after=call('alice','/quota')
    reservation=next(r for r in call('alice','/conversations/'+c+'/handover')['package']['reservations'] if r['bindingId']==p['bindingId'])
    assert reservation['state']=='settled' and reservation['totalAccountKey']=='total','completed execution must have a settled original reservation'
    assert reservation['modelAccountKey']=='model:'+p['plan']['model']
    frozen={(reservation['totalAccountKey'],reservation['period']),(reservation['modelAccountKey'],reservation['period'])}
    previous={(r['account'],r['period']):r['consumed'] for r in before['accounts']}
    current={(r['account'],r['period']):r['consumed'] for r in after['accounts']}
    assert previous.keys()<=current.keys() and frozen<=current.keys()
    for account,consumed in current.items():
        assert consumed==previous.get(account,0)+(1 if account in frozen else 0),(account,'one consumption on original frozen pair only')
    events=call('alice','/conversations/'+c+'/events?limit=100')['events']
    assert sum(e['type']=='execution.accepted' for e in events)==1
    assert len(s['executions'])==1 and len(s['artifacts'])==1
    return {'bindingId':p['bindingId'],'acceptedEvents':1,'duplicateCharge':False,'frozenPeriod':reservation['period'],'frozenAccounts':sorted(k[0] for k in frozen),'otherPeriodsAndAccountsUnchanged':True}

def bad_artifact():
    env=environment('hash_failure');c,p,_,_=prepared(env);approve(p);s=wait_state(c,{'handoff'})
    assert s['artifacts']==[] and s['executions'][0]['closed_at'] is not None
    events=call('alice','/conversations/'+c+'/events?limit=100')['events']
    assert any(e['payload'].get('reason')=='INTEGRITY_MISMATCH' for e in events)
    return {'bindingId':p['bindingId'],'artifactReady':False,'reason':'INTEGRITY_MISMATCH'}

def source_revocation():
    env=environment();c,p,d,_=prepared(env);approve(p);s=wait_state(c,{'awaiting_verification'});artifact=s['artifacts'][0]
    command('alice','/attachments/'+d['id']+'/revoke',{})
    assert call('alice','/conversations/'+c)['artifacts']==[]
    denied=call('alice','/artifacts/'+artifact['id']+'/download',expected=409)
    assert denied['error']['code']=='SOURCE_REVOKED_OR_CHANGED'
    denied=command('alice','/results/'+artifact['id']+'/verification',{'resultRevision':1,'resolved':True,'opinion':'来源已撤销，不得确认'},expected=409)
    assert denied['error']['code']=='SOURCE_REVOKED_OR_CHANGED'
    approvals=call('reviewer','/admin/approvals')
    assert next(a for a in approvals if a['id']==p['approvalId'])['state']=='approved'
    return {'artifactId':artifact['id'],'derivedResultHidden':True,'downloadDenied':True,'historicalDecisionPreserved':True,'agentSession':'not_applicable_fixture_backend'}

def metadata_and_body():
    c=context['conversation'];title=call('alice','/conversations/'+c)['title'];query=urlencode({'query':title})
    records=call('admin','/admin/records?'+query);assert any(r['conversationId']==c for r in records)
    assert any(r['conversationId']==c for r in call('admin','/admin/executions?'+query))
    assert call('admin','/admin/records?'+urlencode({'query':"' OR 1=1 -- unique-fixture"}))==[]
    assert all('messages' not in r and 'attachments' not in r for r in records)
    call('admin','/conversations/'+c,expected=404)
    call('admin','/artifacts/'+context['artifact']['id']+'/download',expected=404)
    return {'metadataVisible':True,'listedConversationId':c,'boundedMetadataTitleFilter':True,'literalQueryNotSql':True,'bodyAndDownloadDenied':True}

def dependency_cas_and_emergency_guard():
    env=environment();objects=call('admin','/admin/configurations');e=next(o for o in objects if o['id']==env);model=next(o for o in objects if o['id']==e['draft']['modelRef'])
    changed={**e['draft'],'turnLimit':80}
    command('admin','/admin/configurations/'+env+'/draft',{'expectedRevision':1,'expectedDraftHash':e['draftHash'],'content':changed},method='PATCH')
    command('admin','/admin/configurations/'+model['id']+'/draft',{'expectedRevision':1,'expectedDraftHash':model['draftHash'],'content':{**model['draft'],'description':'new semantic dependency'}},method='PATCH')
    command('admin','/admin/config-publications',{'objects':[{'id':model['id'],'expectedRevision':1,'dependencyRevisions':{}}]})
    command('admin','/admin/config-publications',{'objects':[{'id':env,'expectedRevision':1,'dependencyRevisions':{model['id']:1,e['draft']['workflowRef']:1}}]},expected=409)
    command('admin','/admin/configurations/'+env+'/disable',{})
    now=next(o for o in call('admin','/admin/configurations') if o['id']==env)
    assert now['revision']==1 and now['draft']['turnLimit']==80 and now['disabled']
    assert all(o['id']!=env for o in call('alice','/workbench/options'))
    return {'environmentId':env,'publicationConflict':True,'unpublishedDraftPreserved':True,'emergencyDisableEffective':True}

def manual_title_priority():
    env=environment();c=command('alice','/conversations',{'environmentId':env})['id']
    s=call('alice','/conversations/'+c)
    command('alice','/conversations/'+c+'/title',{'title':'新问题','expectedTitleRevision':s['titleRevision']},method='PATCH')
    command('alice','/conversations/'+c+'/messages',{'clientMessageId':str(uuid.uuid4()),'text':'此消息不得覆盖手动标题','expectedRevision':s['revision']})
    now=call('alice','/conversations/'+c);assert now['title']=='新问题'
    command('alice','/conversations/'+c+'/title',{'title':'迟到名称','expectedTitleRevision':s['titleRevision']},method='PATCH',expected=409)
    return {'manualPlaceholderNamePreserved':True,'staleTitleRevisionRejected':True}

def membership_revoke():
    env=environment();c,p,_,_=prepared(env);approve(p);s=wait_state(c,{'awaiting_verification'});artifact=s['artifacts'][0]
    command('admin','/admin/grants/revoke',{'alias':'alice','environmentId':env,'role':'member'})
    call('alice','/conversations/'+c,expected=404);call('alice','/conversations/'+c+'/events',expected=404);call('alice','/artifacts/'+artifact['id']+'/download',expected=404)
    return {'environmentId':env,'conversationEventsAndDownloadDenied':True}

def restart_persistence():
    if not args.runtime_config:raise AssertionError('Explicit local runtime configuration required for restart evidence')
    import subprocess
    c=context['conversation'];before=call('alice','/conversations/'+c)
    subprocess.run(['python','scripts/synthetic_runtime.py','restart','--runtime-dir',args.runtime_dir,'--config',args.runtime_config,'--java-home',args.java_home],check=True)
    after=call('alice','/conversations/'+c)
    assert after['state']==before['state'] and after['executions']==before['executions'] and after['artifacts']==before['artifacts']
    assert hashlib.sha256(call('alice','/artifacts/'+context['artifact']['id']+'/download')).hexdigest()==context['artifact']['sha256']
    return {'conversationId':c,'sameBindingAfterRestart':True,'sameArtifactAfterRestart':True}

def scoped_content_lease():
    c=context['conversation'];env=context['environment']
    command('reviewer','/admin/content-leases',{'conversationId':c,'purpose':'复核合成问题的处理记录','validForSeconds':300},expected=403)
    revision=call('reviewer','/session')['authorizationRevision']
    command('admin','/admin/content-reviewers',{'alias':'reviewer','environmentId':env,'enabled':True,'expectedAuthorizationRevision':revision,'reason':'指定负责人复核此合成环境的正文'})
    command('reviewer','/admin/content-leases',{'conversationId':c,'purpose':'复核合成问题的处理记录','validForSeconds':1})
    body=call('reviewer','/admin/records/'+c+'/content')
    assert body['messages'] and 'attachments' not in body and 'artifacts' not in body
    call('reviewer','/artifacts/'+context['artifact']['id']+'/download',expected=404)
    call('reviewer','/conversations/'+c+'/events',expected=404)
    time.sleep(1.1)
    expired=call('reviewer','/admin/records/'+c+'/content',expected=403)
    assert expired['error']['code']=='CONTENT_LEASE_EXPIRED'
    command('reviewer','/admin/content-leases',{'conversationId':c,'purpose':'再次复核合成问题的处理记录','validForSeconds':300})
    revision=call('reviewer','/session')['authorizationRevision']
    command('admin','/admin/content-reviewers',{'alias':'reviewer','environmentId':env,'enabled':False,'expectedAuthorizationRevision':revision,'reason':'复核完毕撤回本环境正文审阅资格'})
    revoked=call('reviewer','/admin/records/'+c+'/content',expected=403)
    assert revoked['error']['code']=='CONTENT_REVIEW_NOT_GRANTED'
    return {'defaultDenied':True,'scopedReadSucceeded':True,'eventsAndDownloadStillDenied':True,'expiryDenied':True,'revocationDenied':True}

if __name__=="__main__":
    if args.validate_api:enable_api_contract_checks()
    check('actual service synthetic CSV to user verification',full_flow)
    if context:
        check('independent object authorization and pending member boundary',denied)
        check('stable command receipt and changed-content conflict',idempotency)
        check('atomic snapshot cursor resumes after watermark',cursor)
        check('fixed scope approval and stop before external acceptance',reject_scope)
        check('queued approval expiry cannot start after waiting for a slot',queued_expiry)
        check('lost acceptance receipt reconciles one execution and one charge',dropped_receipt)
        check('corrupt output is closed without artifact delivery or regeneration',bad_artifact)
        check('source revocation blocks derived content and preserves old decision',source_revocation)
        check('metadata administration does not grant body or download rights',metadata_and_body)
        check('dependency CAS conflicts preserve drafts and emergency guard',dependency_cas_and_emergency_guard)
        check('manual title priority and independent revision conflict',manual_title_priority)
        check('membership revocation blocks each object interface',membership_revoke)
        check('explicit content qualification and expiring message-only lease',scoped_content_lease)
        if args.runtime_config:check('service restart restores original execution and artifact',restart_persistence)
    report['passed']=sum(c['result']=='pass' for c in report['cases']);report['total']=len(report['cases'])
    report['fixtureQuotaAdjustments']=fixture_quota_adjustments
    if args.validate_api:report['apiContract']=api_checks
    out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'passed':report['passed'],'total':report['total'],'output':str(out)},ensure_ascii=False))
    raise SystemExit(0 if report['passed']==report['total'] else 1)
