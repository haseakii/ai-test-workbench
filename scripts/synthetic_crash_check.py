"""Interrupt only the recorded task-owned synthetic jar at an exact private checkpoint.
Uses existing approved accounts/container; retains all data, blobs and fault evidence.
"""
import argparse,concurrent.futures,datetime,hashlib,json,os,re,signal,subprocess,time,uuid
from pathlib import Path
import synthetic_service_check as fixture

p=argparse.ArgumentParser();p.add_argument('--runtime-dir',required=True);p.add_argument('--runtime-config',required=True);p.add_argument('--java-home',required=True);p.add_argument('--mysql-container',default='workbench-mysql-d0');p.add_argument('--output',required=True)
args=p.parse_args();directory=Path(args.runtime_dir).resolve();config=json.loads(Path(args.runtime_config).read_text());jar=Path('backend/platform-server/target/platform-server-0.1.0-SNAPSHOT.jar').resolve();java=(Path(args.java_home)/'bin/java').resolve();fixture.BASE='http://127.0.0.1:'+str(config.get('WB_PORT','18080'))
assert config['SPRING_PROFILES_ACTIVE']=='synthetic' and config['WB_DB_USER']=='workbench'
if args.mysql_container!='workbench-mysql-d0':assert os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_ENVIRONMENT')=='github-hosted' and args.mysql_container==os.environ.get('WB_CI_MYSQL_CONTAINER') and re.fullmatch('[0-9a-f]{64}',args.mysql_container),'Only the task container or exact hosted-job service is allowed'
assert hasattr(os,'pidfd_open') and hasattr(signal,'pidfd_send_signal'),'Linux pidfd required; no fallback signal to an unverified PID'
environment=os.environ.copy();environment['MYSQL_PWD']=config['WB_DB_PASSWORD']
for key in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH']:environment.pop(key,None)
def sql(query):
    result=subprocess.run(['docker','--host=unix:///var/run/docker.sock','exec','-e','MYSQL_PWD',args.mysql_container,'mysql','-u','workbench','--database=workbench_platform','--batch','--skip-column-names','-e',query],env=environment,capture_output=True,text=True,check=True)
    return json.loads(result.stdout)
def runtime(action,private_config):subprocess.run(['python','scripts/synthetic_runtime.py',action,'--runtime-dir',str(directory),'--config',str(private_config),'--java-home',str(java.parent.parent)],check=True)
def observed(binding):
    value=str(uuid.UUID(binding));where="tenant_id=UUID_TO_BIN('00000000-0000-4000-8000-000000000001') AND binding_id=UUID_TO_BIN('"+value+"')"
    return sql("SELECT JSON_OBJECT('bindingState',b.state,'closed',b.closed_at IS NOT NULL,'accepted',b.accepted_at IS NOT NULL,'approvalState',p.state,'providerRows',(SELECT COUNT(*) FROM fixture_submissions WHERE "+where+"),'providerStatus',(SELECT status FROM fixture_submissions WHERE "+where+"),'providerReceipt',(SELECT HEX(receipt_id) FROM fixture_submissions WHERE "+where+"),'providerArtifact',(SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.artifactId')) FROM fixture_submissions WHERE "+where+"),'providerSummaryArtifact',(SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.summaryArtifactId')) FROM fixture_submissions WHERE "+where+"),'providerPhase',(SELECT execution_phase FROM fixture_submissions WHERE "+where+"),'providerAttempts',(SELECT execution_attempts FROM fixture_submissions WHERE "+where+"),'providerOwnerPid',(SELECT owner_pid FROM fixture_submissions WHERE "+where+"),'providerCancelRequested',(SELECT cancel_requested FROM fixture_submissions WHERE "+where+"),'providerArtifactRows',(SELECT COUNT(*) FROM fixture_artifacts WHERE "+where+"),'providerTerminalEvents',(SELECT COUNT(*) FROM fixture_events WHERE "+where+" AND JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.payload.type'))='execution.updated' AND JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.payload.snapshot.state'))='terminal'),'reservationState',(SELECT state FROM quota_reservations WHERE "+where+"),'outboxState',(SELECT state FROM outbox WHERE "+where+"),'artifactRows',(SELECT COUNT(*) FROM artifacts WHERE "+where+"),'acceptedEvents',(SELECT COUNT(*) FROM platform_events e WHERE e.tenant_id=b.tenant_id AND e.conversation_id=b.conversation_id AND e.event_type='execution.accepted'),'occupied',(SELECT occupied FROM active_slots WHERE tenant_id=c.tenant_id AND subject_id=c.subject_id),'reserved',(SELECT COALESCE(SUM(reserved),0) FROM quota_accounts WHERE tenant_id=c.tenant_id AND subject_id=c.subject_id AND account_key='total'),'consumed',(SELECT COALESCE(SUM(consumed),0) FROM quota_accounts WHERE tenant_id=c.tenant_id AND subject_id=c.subject_id AND account_key='total')) FROM execution_bindings b JOIN approvals p ON p.tenant_id=b.tenant_id AND p.binding_id=b.id JOIN conversations c ON c.tenant_id=b.tenant_id AND c.id=b.conversation_id WHERE b.tenant_id=UUID_TO_BIN('00000000-0000-4000-8000-000000000001') AND b.id=UUID_TO_BIN('"+value+"')")
def wait_marker(file,timeout=15):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if file.exists():return json.loads(file.read_text())
        time.sleep(.1)
    raise AssertionError('Expected private checkpoint was not reached; no process was signalled')
def terminate_owned(marker):
    recorded=int((directory/'platform.pid').read_text());assert recorded==marker['pid']
    metadata=json.loads((directory/'platform-process.json').read_text());assert metadata['pid']==recorded and Path(metadata['sourceJar'])==jar
    artifact=Path(metadata['artifactJar']);assert artifact.is_relative_to(directory/'artifacts') and hashlib.sha256(artifact.read_bytes()).hexdigest()==metadata['sha256']
    assert metadata['sha256']==report['artifactSha256'],'Fault injection must use the same reviewed jar in every window'
    proc=Path('/proc')/str(recorded)
    handle=os.pidfd_open(recorded)
    try:
        argv=(proc/'cmdline').read_bytes().split(b'\0');assert len(argv)>=3 and argv[1]==b'-jar'
        assert Path(os.fsdecode(argv[0])).resolve()==java and Path(os.fsdecode(argv[2])).resolve()==artifact.resolve()
        assert (proc/'exe').resolve()==java
        assert b'SPRING_PROFILES_ACTIVE=synthetic' in (proc/'environ').read_bytes().split(b'\0')
        signal.pidfd_send_signal(handle,signal.SIGKILL)
    finally:os.close(handle)
    end=time.monotonic()+8
    while time.monotonic()<end:
        if not proc.exists() or (proc/'stat').read_text().split()[2]=='Z':break
        time.sleep(.1)
    else:raise AssertionError('Task process termination not confirmed; replacement not started')
    (directory/'platform.pid').unlink() # Only process metadata, never a database or blob.

state=sql("SELECT JSON_OBJECT('bindings',(SELECT COUNT(*) FROM execution_bindings WHERE closed_at IS NULL),'outbox',(SELECT COUNT(*) FROM outbox WHERE state<>'done'))")
assert state=={'bindings':0,'outbox':0},'Pre-existing unresolved work; no fault injection started'
runtime('restart',Path(args.runtime_config)) # Pin the current build before any fixture creation.
phases=['approval_before_commit','dispatch_committed','provider_acceptance_committed','platform_receipt_committed','provider_result_committed','provider_execution_started'];report={'scope':'actual task-owned Java process, approved MySQL, synthetic CSV only; no real provider/fees','artifactSha256':json.loads((directory/'platform-process.json').read_text())['sha256'],'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'cases':[]}
out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True)
def save():
    report['passed']=sum(r['result']=='pass' for r in report['cases']);report['total']=len(report['cases']);out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
for phase in phases:
    started=time.monotonic();entry={'name':phase,'result':'fail'}
    try:
        assert hashlib.sha256(jar.read_bytes()).hexdigest()==report['artifactSha256'],'Source jar changed during fault verification'
        env=fixture.environment();c,plan,_,_=fixture.prepared(env);binding=plan['bindingId'];before=observed(binding);blobs=set(Path(config['WB_BLOB_ROOT']).glob('*.blob'))
        private_dir=directory/('fault-'+phase+'-'+uuid.uuid4().hex[:8]);private_dir.mkdir(mode=0o700)
        private_config=private_dir/'runtime-private.json';private_config.write_text(json.dumps({**config,'WB_FAULT_PHASE':phase,'WB_FAULT_BINDING_ID':binding,'WB_FAULT_ROOT':str(private_dir)}));private_config.chmod(0o600)
        runtime('restart',private_config)
        body={'approve':True,'operationHash':plan['operationHash'],'expectedRevision':plan['revision'],'reason':'已核对本次合成故障检查固定范围','idempotencyKey':str(uuid.uuid4())}
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            approval=pool.submit(fixture.call,'reviewer','/approvals/'+plan['approvalId']+'/decision',body)
            marker=wait_marker(private_dir/'checkpoint.json');assert marker['phase']==phase and marker['bindingId']==binding
            try:at_cut=observed(binding)
            finally:terminate_owned(marker)
            runtime('start',Path(args.runtime_config))
            try:approval.result(timeout=2)
            except Exception:
                entry['approvalReplyLost']=True
                if phase!='approval_before_commit':
                    receipt=fixture.call('reviewer','/command-lookups',{'kind':'approval.decide','idempotencyKey':body['idempotencyKey']});assert receipt['state']=='approved'
        if phase=='approval_before_commit':
            assert at_cut['approvalState']=='pending' and at_cut['outboxState'] is None and at_cut['reserved']==0
            recovered=observed(binding);assert recovered['approvalState']=='pending' and recovered['providerRows']==0
            missing=fixture.call('reviewer','/command-lookups',{'kind':'approval.decide','idempotencyKey':body['idempotencyKey']});assert missing['status']=='not_found'
            fixture.call('reviewer','/approvals/'+plan['approvalId']+'/decision',body)
        elif phase=='dispatch_committed':
            assert at_cut['reserved']==1 and at_cut['occupied']==1 and at_cut['providerRows']==0
            end=time.monotonic()+45
            while time.monotonic()<end:
                recovered=observed(binding)
                if recovered['bindingState']=='acceptance_unknown':break
                time.sleep(.3)
            assert recovered['bindingState']=='acceptance_unknown' and recovered['reserved']==1 and recovered['occupied']==1
            assert recovered['providerRows']==0 and recovered['consumed']==before['consumed']
            entry['unknownRecovery']=recovered
            fixture.command('alice','/conversations/'+c+'/stop',{});fixture.wait_state(c,{'handoff'})
        else:
            assert at_cut['providerRows']==1 and at_cut['occupied']==1
            if phase=='provider_acceptance_committed':assert not at_cut['accepted'] and at_cut['reserved']==1
            if phase=='platform_receipt_committed':assert at_cut['accepted'] and at_cut['reserved']==0 and at_cut['consumed']==before['consumed']+1
            if phase=='provider_result_committed':assert at_cut['providerStatus']=='succeeded' and at_cut['artifactRows']==0 and at_cut['providerArtifact']
            if phase=='provider_execution_started':
                assert at_cut['providerStatus']=='executing' and at_cut['providerPhase']=='computing' and at_cut['providerAttempts']==1
                assert at_cut['providerOwnerPid']==marker['pid'] and at_cut['accepted'] and at_cut['reserved']==0 and at_cut['consumed']==before['consumed']+1
                assert at_cut['providerArtifactRows']==0 and at_cut['artifactRows']==0 and at_cut['providerArtifact'] is None and at_cut['providerSummaryArtifact'] is None
                assert not (set(Path(config['WB_BLOB_ROOT']).glob('*.blob'))-blobs),'Computing checkpoint is before every output write'
                end=time.monotonic()+45
                while time.monotonic()<end:
                    recovered=observed(binding)
                    if recovered['providerStatus']=='outcome_unknown' and recovered['bindingState']=='reconciling':break
                    time.sleep(.3)
                assert recovered['providerStatus']=='outcome_unknown' and recovered['bindingState']=='reconciling' and not recovered['closed']
                assert recovered['providerPhase']=='computing' and recovered['providerAttempts']==1 and recovered['providerReceipt']==at_cut['providerReceipt']
                assert recovered['occupied']==1 and recovered['reserved']==0 and recovered['reservationState']=='consumed' and recovered['consumed']==before['consumed']+1
                assert recovered['providerArtifactRows']==0 and recovered['artifactRows']==0
                entry['unknownRecovery']=recovered
                observations=[]
                # There is no public Provider lookup route. Read the current conversation and
                # original durable submission while the original Worker reconciles that key.
                # These reads never create a plan, call start, or drive provider computation.
                for _ in range(3):
                    snapshot=fixture.call('alice','/conversations/'+c)
                    execution=next(x for x in snapshot['executions'] if x['id']==binding)
                    stable=observed(binding)
                    assert execution['state']=='reconciling' and execution['closed_at'] is None
                    assert stable['providerStatus']=='outcome_unknown' and stable['providerAttempts']==1 and stable['providerReceipt']==at_cut['providerReceipt']
                    assert stable['occupied']==1 and stable['reserved']==0 and stable['consumed']==before['consumed']+1 and stable['providerArtifactRows']==0 and stable['providerTerminalEvents']==0
                    observations.append({'bindingState':stable['bindingState'],'providerStatus':stable['providerStatus'],'providerReceipt':stable['providerReceipt'],'providerAttempts':stable['providerAttempts'],'occupied':stable['occupied'],'consumed':stable['consumed']})
                    time.sleep(.25)
                entry['originalSubmissionObservations']=observations
                stop_key=str(uuid.uuid4());stop_body={'idempotencyKey':stop_key}
                fixture.call('alice','/conversations/'+c+'/stop',stop_body);fixture.wait_state(c,{'handoff'},timeout=45)
                # Lost/repeated stop receipts must not create another cancellation settlement.
                fixture.call('alice','/conversations/'+c+'/stop',stop_body)
                cancelled=observed(binding);assert cancelled['providerStatus']=='cancelled' and cancelled['providerCancelRequested'] and cancelled['providerPhase']=='terminal' and cancelled['providerAttempts']==1 and cancelled['providerTerminalEvents']==1
                assert cancelled['providerReceipt']==at_cut['providerReceipt'] and cancelled['consumed']==before['consumed']+1
        if phase not in {'dispatch_committed','provider_execution_started'}:
            snapshot=fixture.wait_state(c,{'awaiting_verification'},timeout=45);artifact=snapshot['artifacts'][0]
            assert artifact['summary']['totalDifference']=='10.00' and artifact['summary']['usage']['modelCalls']==0
            if phase=='provider_result_committed':assert artifact['id']==at_cut['providerArtifact']
        after=observed(binding);new_blobs=set(Path(config['WB_BLOB_ROOT']).glob('*.blob'))-blobs
        assert after['closed'] and after['occupied']==0 and after['reserved']==0 and after['providerRows']==1
        expected=0 if phase=='dispatch_committed' else 1
        expected_artifacts=0 if phase in {'dispatch_committed','provider_execution_started'} else 1
        expected_blobs=2*expected_artifacts
        assert after['consumed']-before['consumed']==expected and after['acceptedEvents']==expected and after['artifactRows']==expected_artifacts and len(new_blobs)==expected_blobs
        assert after['providerArtifactRows']==expected_blobs
        if expected_artifacts:assert after['providerAttempts']==1 and after['providerSummaryArtifact']
        if at_cut['providerReceipt']:assert after['providerReceipt']==at_cut['providerReceipt']
        entry.update({'result':'pass','bindingId':binding,'marker':marker,'before':before,'atInterruption':at_cut,'afterRecovery':after,'newArtifactBlobCount':len(new_blobs),'platformArtifactCount':expected_artifacts,'platformTurnDelta':expected,'modelCalls':0,'seconds':round(time.monotonic()-started,3)})
    except Exception as error:
        entry['error']=str(error);report['cases'].append(entry);save();print(phase+': fail: '+str(error),flush=True);raise
    report['cases'].append(entry);save();print(phase+': pass ('+str(entry['seconds'])+'s)',flush=True)
print(json.dumps({'passed':report['passed'],'total':report['total'],'output':str(out)}))
