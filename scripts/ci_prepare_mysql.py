"""Future GitHub-hosted job bootstrap only. Never runs against this workspace's container.
Creates scoped users in the job's disposable service and keeps credentials in RUNNER_TEMP.
"""
import json,os,re,secrets,subprocess,time
from pathlib import Path

assert os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_ENVIRONMENT')=='github-hosted','CI bootstrap is restricted to a GitHub-hosted job'
container=os.environ['WB_CI_MYSQL_CONTAINER'];assert re.fullmatch('[0-9a-f]{64}',container),'Exact job service container ID required'
runtime=Path(os.environ['RUNNER_TEMP']).resolve()/'workbench';runtime.mkdir(mode=0o700,exist_ok=False)
docker=['docker','--host=unix:///var/run/docker.sock'];environment=os.environ.copy()
for key in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH']:environment.pop(key,None)
inspect=json.loads(subprocess.check_output(docker+['inspect',container],env=environment))[0]
assert inspect['Config']['Image']=='mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69'
assert inspect['HostConfig']['PortBindings']['3306/tcp']==[{'HostIp':'127.0.0.1','HostPort':'13306'}]
root_password=None
for attempt in range(60):
    logs=subprocess.run(docker+['logs',container],env=environment,capture_output=True,text=True,check=True)
    match=re.search(r'GENERATED ROOT PASSWORD:\s*(\S+)',logs.stdout+logs.stderr)
    if match and 'MySQL init process done' in logs.stdout+logs.stderr:root_password=match.group(1);break
    time.sleep(1)
assert root_password,'Random root password not available; no database changes attempted'
passwords={name:secrets.token_urlsafe(32) for name in ['workbench','workbench_runner','workbench_migration']}
for value in [root_password,*passwords.values()]:print('::add-mask::'+value,flush=True)
root_env=environment|{'MYSQL_PWD':root_password}
for attempt in range(30):
    ready=subprocess.run(docker+['exec','-e','MYSQL_PWD',container,'mysql','-u','root','-e','SELECT 1'],env=root_env,capture_output=True)
    if ready.returncode==0:break
    time.sleep(1)
else:raise RuntimeError('Job service did not become ready; no database changes attempted')
sql="""SET GLOBAL transaction_isolation='READ-COMMITTED';
SET GLOBAL time_zone='+00:00';
CREATE DATABASE workbench_runner;
"""
for name,password in passwords.items():sql+=f"CREATE USER '{name}'@'%' IDENTIFIED BY '{password}';\n"
sql+="""GRANT SELECT,INSERT,UPDATE,DELETE ON workbench_platform.* TO 'workbench'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON workbench_runner.* TO 'workbench_runner'@'%';
GRANT ALL ON workbench_platform.* TO 'workbench_migration'@'%';
GRANT ALL ON workbench_runner.* TO 'workbench_migration'@'%';
"""
result=subprocess.run(docker+['exec','-i','-e','MYSQL_PWD',container,'mysql','-u','root'],input=sql,env=root_env,capture_output=True,text=True)
assert result.returncode==0,'Job database bootstrap failed; details stay private, no automatic retry'
subprocess.run(docker+['cp',container+':/var/lib/mysql/ca.pem',str(runtime/'mysql-ca.pem')],env=environment,check=True)
java_home=Path(os.environ['JAVA_HOME']);trust=runtime/'mysql-trust.p12'
subprocess.run([str(java_home/'bin/keytool'),'-importcert','-noprompt','-alias','job-mysql-ca','-file',str(runtime/'mysql-ca.pem'),'-keystore',str(trust),'-storetype','PKCS12','-storepass','changeit'],check=True,capture_output=True)
url='jdbc:mysql://127.0.0.1:13306/workbench_platform?sslMode=VERIFY_CA&trustCertificateKeyStoreUrl='+trust.as_uri()+'&trustCertificateKeyStorePassword=changeit&trustCertificateKeyStoreType=PKCS12&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
configs={'runtime-private.json':{'WB_DB_URL':url,'WB_DB_USER':'workbench','WB_DB_PASSWORD':passwords['workbench'],'WB_BLOB_ROOT':str(runtime/'blobs'),'WB_PORT':'18080','SPRING_PROFILES_ACTIVE':'synthetic'},'migration-platform-private.json':{'WB_DB_URL':url,'WB_MIGRATION_USER':'workbench_migration','WB_MIGRATION_PASSWORD':passwords['workbench_migration']},'migration-runner-private.json':{'WB_DB_URL':url.replace('/workbench_platform?','/workbench_runner?'),'WB_MIGRATION_USER':'workbench_migration','WB_MIGRATION_PASSWORD':passwords['workbench_migration']}}
for name,content in configs.items():
    file=runtime/name;file.write_text(json.dumps(content));file.chmod(0o600)
variables={**configs['runtime-private.json'],'WB_RUNNER_DB_URL':configs['migration-runner-private.json']['WB_DB_URL'],'WB_RUNNER_PASSWORD':passwords['workbench_runner'],'WB_MIGRATION_USER':'workbench_migration','WB_MIGRATION_PASSWORD':passwords['workbench_migration']}
with Path(os.environ['GITHUB_ENV']).open('a') as output:
    for key,value in variables.items():output.write(key+'='+value+'\n')
for name,location in [('migration-platform-private.json',None),('migration-runner-private.json','filesystem:backend/execution-runner/src/main/resources/db/migration')]:
    command=['python','scripts/synthetic_runtime.py','migrate','--runtime-dir',str(runtime),'--config',str(runtime/name),'--java-home',str(java_home)]
    if location:command+=['--migration-location',location]
    subprocess.run(command,check=True)
print('Prepared the job-only MySQL schemas, scoped runtime users, TLS trust and private configuration')
