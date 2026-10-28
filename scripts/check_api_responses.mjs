/** Compile all documented response schemas; optional JSON-lines RPC checks actual HTTP replies. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createInterface} from 'node:readline';
import Ajv from '../frontend/node_modules/ajv/dist/2020.js';
import addFormats from '../frontend/node_modules/ajv-formats/dist/index.js';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const openapi=JSON.parse(fs.readFileSync(path.join(root,'contracts/platform.openapi.json'),'utf8'));
const rewrite=v=>JSON.parse(JSON.stringify(v).replaceAll('#/components/schemas/','#/$defs/'));
const id='urn:workbench:openapi-responses';const ajv=new Ajv({strict:true});addFormats(ajv);
ajv.addSchema({$id:id,$defs:rewrite(openapi.components.schemas)});
const routes=[];const identifiers=new Set();let validators=0;
for(const [route,operations] of Object.entries(openapi.paths))for(const [method,operation] of Object.entries(operations)){
 if(identifiers.has(operation.operationId))throw new Error('Duplicate operationId: '+operation.operationId);identifiers.add(operation.operationId);
 const responses={};for(const [status,response] of Object.entries(operation.responses)){
  const schema=response.content?.['application/json']?.schema;if(!schema)continue;
  responses[status]=ajv.compile({$id:id+':'+operation.operationId+':'+status,$defs:rewrite(openapi.components.schemas),...rewrite(schema)});validators++;
 }
 routes.push({route,pattern:new RegExp('^'+route.replace(/[.*+?^$()|[\]\\]/g,'\\$&').replace(/\{[^}]+\}/g,'[^/]+')+'$'),method:method.toUpperCase(),operation,responses});
}
if(process.argv.includes('--check')){console.log(JSON.stringify({paths:Object.keys(openapi.paths).length,operations:identifiers.size,responseValidators:validators}));}
else for await (const line of createInterface({input:process.stdin})){
 try{
  const input=JSON.parse(line);const route=routes.find(r=>r.method===input.method&&r.pattern.test(input.path));
  if(!route)throw new Error('Undocumented route '+input.method+' '+input.path);
  const validate=route.responses[input.status];if(!validate)throw new Error('Undocumented JSON status '+input.status+' for '+route.operation.operationId);
  const ok=validate(input.body);console.log(JSON.stringify({ok,operationId:route.operation.operationId,status:input.status,...(!ok?{errors:validate.errors}:{} )}));
 }catch(error){console.log(JSON.stringify({ok:false,error:String(error)}));}
}
