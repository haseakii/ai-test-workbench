"""Offline ACP wire fixture. No model, files, terminal, network or resource operations."""
import json,os,sys
pending=None
last_cancelled=None
def emit(value):
    sys.stdout.write(json.dumps(value,separators=(',',':'))+'\n');sys.stdout.flush()
def result(ident,value):emit({'jsonrpc':'2.0','id':ident,'result':value})
for line in sys.stdin:
    request=json.loads(line)
    if 'method' not in request:
        if request.get('id')==700 and pending is not None:
            result(pending,{'permissionResponse':request.get('result')});pending=None
        continue
    method=request['method']
    if method=='initialize':
        result(request['id'],{'protocolVersion':1,'agentCapabilities':{'loadSession':False,'promptCapabilities':{'image':False,'audio':False,'embeddedContext':False}},'authMethods':[],'agentInfo':{'name':'offline-stdio-fixture','version':'1'}})
    elif method=='session/new':result(request['id'],{'sessionId':'synthetic-session'})
    elif method=='session/cancel':last_cancelled=request['params']['sessionId']
    elif method=='_fixture/permission':
        pending=request['id']
        emit({'jsonrpc':'2.0','id':700,'method':'session/request_permission','params':{'sessionId':'synthetic-session','toolCall':{'toolCallId':'synthetic-tool','title':'no-op-fixture','kind':'read'},'options':[{'optionId':'once','name':'Allow once','kind':'allow_once'},{'optionId':'deny','name':'Reject','kind':'reject_once'}]}})
    elif method=='_fixture/state':
        result(request['id'],{'lastCancelled':last_cancelled,'hasDatabaseSecrets':any(k.startswith(('WB_DB_','WB_MIGRATION_','WB_RUNNER_')) for k in os.environ),'resourceOperations':0})
    elif 'id' in request:
        emit({'jsonrpc':'2.0','id':request['id'],'error':{'code':-32601,'message':'Unsupported fixture method'}})
