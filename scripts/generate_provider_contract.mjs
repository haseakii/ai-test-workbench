/** One source: reviewed TypeScript declarations -> Java records/port and JSON Schema.
 * Unsupported syntax fails generation. --check is read-only; --write updates generated files. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import ts from '../frontend/node_modules/typescript/lib/typescript.js';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const sourcePath='docs/design/contracts/execution-provider.d.ts';
const source=fs.readFileSync(path.join(root,sourcePath),'utf8');
const sourceHash=createHash('sha256').update(source).digest('hex');
const tree=ts.createSourceFile(sourcePath,source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
const diagnostics=ts.getPreEmitDiagnostics(ts.createProgram([path.join(root,sourcePath)],{strict:true,noEmit:true,target:ts.ScriptTarget.ES2022,types:[]}));
if(diagnostics.length)throw new Error(ts.formatDiagnosticsWithColorAndContext(diagnostics,{getCanonicalFileName:n=>n,getCurrentDirectory:()=>root,getNewLine:()=> '\n'}));
const declarations=new Map(tree.statements.filter(n=>ts.isInterfaceDeclaration(n)||ts.isTypeAliasDeclaration(n)).map(n=>[n.name.text,n]));
const fail=n=>{throw new Error('Unsupported contract syntax: '+n.getText(tree));};
const unwrap=n=>ts.isParenthesizedTypeNode(n)?unwrap(n.type):n;
const literal=n=>{
 n=unwrap(n);if(!ts.isLiteralTypeNode(n))return undefined;
 if(ts.isStringLiteral(n.literal))return n.literal.text;
 if(ts.isNumericLiteral(n.literal))return Number(n.literal.text);
 if(n.literal.kind===ts.SyntaxKind.TrueKeyword)return true;
 if(n.literal.kind===ts.SyntaxKind.FalseKeyword)return false;
 if(n.literal.kind===ts.SyntaxKind.NullKeyword)return null;
 return fail(n);
};
const stringLiterals=n=>{
 n=unwrap(n);
 if(ts.isTypeReferenceNode(n)&&declarations.has(n.typeName.getText(tree))){const d=declarations.get(n.typeName.getText(tree));return ts.isTypeAliasDeclaration(d)?stringLiterals(d.type):undefined;}
 const value=literal(n);if(typeof value==='string')return [value];
 if(ts.isUnionTypeNode(n)){const members=n.types.map(stringLiterals);if(members.every(Boolean))return members.flat();}
 return undefined;
};
const fields=members=>members.map(p=>{
 if(!ts.isPropertySignature(p)||!p.type||p.questionToken)return fail(p);
 return {name:p.name.getText(tree),type:p.type};
});
function variants(node){
 const n=unwrap(node);
 if(ts.isInterfaceDeclaration(n)){
  const inherited=(n.heritageClauses??[]).flatMap(h=>h.types).flatMap(t=>variants(declarations.get(t.expression.getText(tree)))[0]);
  return [[...inherited,...fields(n.members)]];
 }
 if(ts.isTypeAliasDeclaration(n))return variants(n.type);
 if(ts.isTypeLiteralNode(n))return [fields(n.members)];
 if(ts.isTypeReferenceNode(n))return variants(declarations.get(n.typeName.getText(tree))??fail(n));
 if(ts.isUnionTypeNode(n))return n.types.flatMap(variants);
 if(ts.isIntersectionTypeNode(n))return n.types.reduce((out,t)=>out.flatMap(a=>variants(t).map(b=>[...a,...b])),[[]]);
 return fail(n);
}
const atomicSchema={
 Id:{type:'string',minLength:1},SafeInteger:{type:'integer',minimum:0,maximum:9007199254740991},
 Uuid:{type:'string',format:'uuid',minLength:36,maxLength:36},Cursor:{type:'string',minLength:1},
 Instant:{type:'string',format:'date-time',pattern:'^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{6}Z$'},
 Sha256:{type:'string',minLength:64,maxLength:64,pattern:'^[0-9a-f]{64}$'},Decimal:{type:'string',pattern:'^(0|[1-9][0-9]*)(\\.[0-9]+)?$'}
};
function objectSchema(properties){
 return {type:'object',additionalProperties:false,required:properties.map(p=>p.name),properties:Object.fromEntries(properties.map(p=>[p.name,schema(p.type)]))};
}
function schema(node){
 const n=unwrap(node);
 if(ts.isTypeAliasDeclaration(n))return schema(n.type);
 if(ts.isInterfaceDeclaration(n))return objectSchema(variants(n)[0]);
 if(ts.isTypeReferenceNode(n)){
  const name=n.typeName.getText(tree);
  if(name==='Array')return {type:'array',items:schema(n.typeArguments[0])};
  if(!declarations.has(name))return fail(n);
  return {$ref:'#/$defs/'+name};
 }
 if(ts.isArrayTypeNode(n))return {type:'array',items:schema(n.elementType)};
 if(ts.isTypeLiteralNode(n))return objectSchema(fields(n.members));
 if(ts.isIntersectionTypeNode(n))return {oneOf:variants(n).map(objectSchema)};
 if(ts.isUnionTypeNode(n)){
  const values=n.types.map(literal);
  if(values.every(v=>typeof v==='string'))return {type:'string',enum:values};
  return {anyOf:n.types.map(schema)};
 }
 const value=literal(n);if(value!==undefined)return {const:value,type:value===null?'null':typeof value==='number'?'integer':typeof value};
 if(n.kind===ts.SyntaxKind.StringKeyword)return {type:'string'};
 if(n.kind===ts.SyntaxKind.BooleanKeyword)return {type:'boolean'};
 if(n.kind===ts.SyntaxKind.NumberKeyword)return atomicSchema.SafeInteger;
 return fail(n);
}
const defs={};
for(const [name,node] of declarations)if(name!=='ExecutionProvider'&&name!=='Result')defs[name]=atomicSchema[name]??schema(node);
const provider=declarations.get('ExecutionProvider');
const methods=provider.members.map(method=>{
 if(!ts.isMethodSignature(method)||method.parameters.length!==2)return fail(method);
 const result=method.type;
 if(!ts.isTypeReferenceNode(result)||result.typeName.getText(tree)!=='Promise')return fail(result);
 const wrapped=result.typeArguments[0];
 if(!ts.isTypeReferenceNode(wrapped)||wrapped.typeName.getText(tree)!=='Result')return fail(wrapped);
 return {name:method.name.getText(tree),parameters:method.parameters.map(p=>({name:p.name.getText(tree),type:p.type})),value:wrapped.typeArguments[0]};
});
for(const method of methods){
 defs[method.name+'Request']=objectSchema(method.parameters);
 defs[method.name+'Response']={oneOf:[{type:'object',additionalProperties:false,required:['ok','value'],properties:{ok:{const:true},value:schema(method.value)}},{type:'object',additionalProperties:false,required:['ok','error'],properties:{ok:{const:false},error:{$ref:'#/$defs/ProviderError'}}}]};
}

const javaDefinitions=new Map();
const cap=s=>s.replace(/(^|[^a-zA-Z0-9])([a-zA-Z0-9])/g,(_,p,c)=>c.toUpperCase());
const nullable=n=>ts.isUnionTypeNode(unwrap(n))&&unwrap(n).types.some(t=>literal(t)===null);
const nonNull=n=>{n=unwrap(n);return nullable(n)?n.types.filter(t=>literal(t)!==null):[n];};
const withoutNull=n=>{const parts=nonNull(n);return parts.length===1?parts[0]:ts.factory.createUnionTypeNode(parts);};
function javaType(node,stem,boxed=false){
 const n=unwrap(node);
 if(literal(n)===null)return 'Void';
 if(nullable(n)){
  return javaType(withoutNull(n),stem,true);
 }
 if(ts.isTypeReferenceNode(n)){
  const name=n.typeName.getText(tree);
  if(name==='Array')return 'List<'+javaType(n.typeArguments[0],stem+'Item',true)+'>';
  if(name==='KnownBoolean')return name;
  if(name==='SafeInteger')return boxed?'Long':'long';
  if(Object.hasOwn(atomicSchema,name)||stringLiterals(n))return 'String';
  if(name==='T')return 'T';
  if(!declarations.has(name))return fail(n);
  generateJava(name,declarations.get(name));return name+(n.typeArguments?'<' + n.typeArguments.map(t=>javaType(t,stem,true)).join(',')+'>':'');
 }
 if(ts.isArrayTypeNode(n))return 'List<'+javaType(n.elementType,stem+'Item',true)+'>';
 if(ts.isTypeLiteralNode(n)||ts.isIntersectionTypeNode(n)||ts.isUnionTypeNode(n)&&!stringLiterals(n)){
  generateJava(stem,n);return stem;
 }
 if(stringLiterals(n)||n.kind===ts.SyntaxKind.StringKeyword)return 'String';
 if(n.kind===ts.SyntaxKind.NumberKeyword||typeof literal(n)==='number')return boxed?'Long':'long';
 if(n.kind===ts.SyntaxKind.BooleanKeyword||typeof literal(n)==='boolean')return boxed?'Boolean':'boolean';
 return fail(n);
}
function checks(node,field){
 let n=unwrap(node);const optional=nullable(n);if(optional)n=withoutNull(n);
 if(literal(n)===null)return [`if (${field} != null) throw new IllegalArgumentException("${field} must be null");`];
 const code=[];
 const name=ts.isTypeReferenceNode(n)?n.typeName.getText(tree):undefined;
 const primitive=name==='SafeInteger'||n.kind===ts.SyntaxKind.NumberKeyword||n.kind===ts.SyntaxKind.BooleanKeyword||['number','boolean'].includes(typeof literal(n));
 if(!optional&&!primitive)code.push(`Objects.requireNonNull(${field}, "${field}");`);
 if(name==='SafeInteger'||n.kind===ts.SyntaxKind.NumberKeyword||typeof literal(n)==='number')code.push(`ContractChecks.safeInteger(${field});`);
 if(name==='Id')code.push(`if (${field}.isEmpty()) throw new IllegalArgumentException("${field} is empty");`);
 if(name==='Cursor')code.push(`if (${field}.isEmpty()) throw new IllegalArgumentException("${field} is empty");`);
 if(name==='Uuid')code.push(`if (${field}.length()!=36 || !UUID.fromString(${field}).toString().equalsIgnoreCase(${field})) throw new IllegalArgumentException("${field} is not UUID");`);
 if(name==='Instant')code.push(`utc(${field});`);
 if(name==='Sha256')code.push(`if (!${field}.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("${field} is not SHA-256");`);
 if(name==='Decimal')code.push(`if (!${field}.matches("(0|[1-9][0-9]*)([.][0-9]+)?")) throw new IllegalArgumentException("${field} is not Decimal");`);
 const values=stringLiterals(n);
 if(values)code.push(`if (!Set.of(${values.map(JSON.stringify).join(', ')}).contains(${field})) throw new IllegalArgumentException("Invalid ${field}");`);
 const value=literal(n);
 if(typeof value==='number'||typeof value==='boolean')code.push(`if (${field} != ${value}) throw new IllegalArgumentException("Invalid ${field}");`);
 let element;
 if(ts.isArrayTypeNode(n))element=n.elementType;
 if(name==='Array')element=n.typeArguments[0];
 if(element){
  code.push(`${field} = List.copyOf(${field});`);
  const item=checks(element,'item').filter(c=>!c.startsWith('Objects.requireNonNull'));
  if(item.length)code.push(`for (var item : ${field}) { ${item.join(' ')} }`);
 }
 return optional?code.length?[`if (${field} != null) { ${code.join(' ')} }`]:[]:code;
}
function record(name,properties,parent,generic){
 const typeParameter=generic?'<T>':'';
 const components=properties.map(p=>'            '+javaType(p.type,name+cap(p.name))+' '+p.name).join(',\n');
 const validations=properties.flatMap(p=>checks(p.type,p.name));
 return `    public record ${name}${typeParameter}(\n${components}\n    )${parent?' implements '+parent+typeParameter:''} {\n        public ${name} {\n${validations.map(s=>'            '+s).join('\n')}\n        }\n    }`;
}
function generateJava(name,node){
 if(javaDefinitions.has(name))return;
 javaDefinitions.set(name,'');
 const alternatives=variants(node),generic=name==='Result';
 if(alternatives.length===1){javaDefinitions.set(name,record(name,alternatives[0]));return;}
 const discriminator=alternatives[0].find(p=>{
  const tags=[];
  for(const variant of alternatives){
   const property=variant.find(q=>q.name===p.name);if(!property)return false;
   const values=stringLiterals(property.type)??(literal(property.type)!==undefined?[literal(property.type)]:undefined);
   if(!values)return false;tags.push(...values.map(String));
  }
  return new Set(tags).size===tags.length;
 });
 if(!discriminator)throw new Error('No discriminator for '+name);
 const labels=v=>{const n=v.find(p=>p.name===discriminator.name).type;return stringLiterals(n)??[literal(n)];};
 const children=alternatives.map((v,i)=>({properties:v,labels:labels(v),name:name+labels(v).map(x=>cap(String(x))).join('Or')}));
 if(new Set(children.map(c=>c.name)).size!==children.length)throw new Error('Duplicate union variant '+name);
 const tags=children.flatMap(c=>c.labels.map(tag=>`        @JsonSubTypes.Type(value=${c.name}.class, name=${JSON.stringify(String(tag))})`));
 javaDefinitions.set(name,`    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="${discriminator.name}", visible=true)\n    @JsonSubTypes({\n${tags.join(',\n')}\n    })\n    public sealed interface ${name}${generic?'<T>':''} permits ${children.map(c=>c.name).join(', ')} {}`);
 for(const child of children)javaDefinitions.set(child.name,record(child.name,child.properties,name,generic));
}
for(const [name,node] of declarations){
 if(name==='ExecutionProvider'||Object.hasOwn(atomicSchema,name)||name==='KnownBoolean'||stringLiterals(ts.isTypeAliasDeclaration(node)?node.type:node))continue;
 generateJava(name,node);
}
const header=`// Generated from ${sourcePath}; SHA-256 ${sourceHash}. Do not edit.\n`;
const types=header+`package io.aitest.contract;\n\nimport java.util.*;\nimport com.fasterxml.jackson.annotation.*;\n\npublic final class ProviderTypes {\n    private ProviderTypes() {}\n    private static void utc(String value) {\n        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{6}Z")) throw new IllegalArgumentException("Six-digit UTC required");\n        ContractChecks.microsecond(java.time.Instant.parse(value));\n    }\n    public enum KnownBoolean {\n        TRUE(true), FALSE(false), UNKNOWN("unknown");\n        private final Object value;\n        KnownBoolean(Object value) { this.value=value; }\n        @JsonValue public Object value() { return value; }\n        @JsonCreator public static KnownBoolean from(Object value) {\n            for (var option : values()) if (option.value.equals(value)) return option;\n            throw new IllegalArgumentException("KnownBoolean required");\n        }\n    }\n`+[...javaDefinitions.values()].join('\n\n')+'\n}\n';
const port=header+`package io.aitest.contract;\n\nimport java.util.concurrent.CompletionStage;\nimport static io.aitest.contract.ProviderTypes.*;\n\n/** Transport-independent draft boundary. Implementing it does not imply conformance. */\npublic interface ExecutionProvider {\n`+methods.map(m=>`    CompletionStage<Result<${javaType(m.value,m.name+'Value',true)}>> ${m.name}(${m.parameters.map(p=>javaType(p.type,m.name+cap(p.name))+' '+p.name).join(', ')});`).join('\n')+'\n}\n';
const outputs={
 'contracts/core.schema.json':JSON.stringify({$schema:'https://json-schema.org/draft/2020-12/schema',$id:'urn:ai-test-workbench:core:1',title:'Generated core contract projections',$comment:JSON.stringify({source:sourcePath,sourceSha256:sourceHash}),$defs:{...atomicSchema,safeInteger:atomicSchema.SafeInteger,sha256:atomicSchema.Sha256,instant:atomicSchema.Instant,authority:defs.DecisionAuthority,admissionTicket:defs.AdmissionTicket,recoveryPoint:objectSchema([{name:'bindingId',type:declarations.get('BindingRef').members.find(p=>p.name.getText(tree)==='bindingId').type},...declarations.get('ExecutionSnapshot').members.filter(p=>['snapshotRevision','eventWatermark','resumeCursor'].includes(p.name.getText(tree))).map(p=>({name:p.name.getText(tree),type:p.type}))])}},null,2)+'\n',
 'contracts/provider.schema.json':JSON.stringify({$schema:'https://json-schema.org/draft/2020-12/schema',$id:'urn:ai-test-workbench:provider:initial-draft',title:'Provider wire contract; no transport routes implemented',$comment:JSON.stringify({source:sourcePath,sourceSha256:sourceHash,methods:methods.map(m=>m.name)}),$defs:defs},null,2)+'\n',
 'backend/provider-contract/src/main/java/io/aitest/contract/ProviderTypes.java':types,
 'backend/provider-contract/src/main/java/io/aitest/contract/ExecutionProvider.java':port
};
let stale=0;
for(const [name,content] of Object.entries(outputs)){
 const destination=path.join(root,name);
 if(process.argv.includes('--write')){fs.mkdirSync(path.dirname(destination),{recursive:true});fs.writeFileSync(destination,content);}
 else if(!fs.existsSync(destination)||fs.readFileSync(destination,'utf8')!==content){console.error('Stale generated contract: '+name);stale++;}
}
console.log(JSON.stringify({mode:process.argv.includes('--write')?'write':'check',methods:methods.length,definitions:Object.keys(defs).length,stale}));
if(stale)process.exitCode=1;
