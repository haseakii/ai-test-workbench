package io.aitest.workbench;

import io.aitest.contract.CanonicalJson;
import io.aitest.workbench.api.WorkbenchApi;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Export only actual controller routes. Provider is an in-process port, not invented HTTP paths. */
class ApiContractTest {
    static final Map<String,Object> SAFE=Map.of("type","integer","minimum",0,"maximum",9_007_199_254_740_991L);
    final Map<String,Object> definitions=new TreeMap<>();
    final JsonMapper json=JsonMapper.builder().build();
    Object schema(Type type){
        if(type instanceof ParameterizedType p){
            if(p.getRawType()==List.class)return Map.of("type","array","items",schema(p.getActualTypeArguments()[0]));
            if(p.getRawType()==Map.class)return Map.of("type","object","additionalProperties",schema(p.getActualTypeArguments()[1]));
            throw new IllegalArgumentException(type.toString());
        }
        Class<?> cls=(Class<?>)type;
        if(cls==Object.class)return Map.of();
        if(cls==String.class)return Map.of("type","string");
        if(cls==UUID.class)return Map.of("type","string","format","uuid");
        if(cls==long.class||cls==Long.class||cls==int.class||cls==Integer.class)return SAFE;
        if(cls==boolean.class||cls==Boolean.class)return Map.of("type","boolean");
        if(!cls.isRecord())throw new IllegalArgumentException("Unsupported request type: "+type);
        String name=cls.getSimpleName();
        if(!definitions.containsKey(name)){
            definitions.put(name,Map.of());var properties=new TreeMap<String,Object>();var required=new ArrayList<String>();
            for(var component:cls.getRecordComponents()){required.add(component.getName());properties.put(component.getName(),schema(component.getGenericType()));}
            definitions.put(name,Map.of("type","object","additionalProperties",false,"required",required,"properties",properties));
        }
        return Map.of("$ref","#/components/schemas/"+name);
    }
    @SuppressWarnings("unchecked") Map<String,Object> document()throws Exception {
        var responsesSource=json.readValue(Files.readString(Path.of("../../contracts/platform-responses.schema.json")),Map.class);
        var responseMetadata=json.readValue(responsesSource.get("$comment").toString(),Map.class);
        var responseOperations=(Map<String,Object>)responseMetadata.get("operations");
        definitions.putAll((Map<String,Object>)json.readValue(json.writeValueAsString(responsesSource.get("$defs")).replace("#/$defs/","#/components/schemas/"),Map.class));
        var paths=new TreeMap<String,Map<String,Object>>();
        var methods=new ArrayList<>(Arrays.asList(WorkbenchApi.class.getDeclaredMethods()));methods.sort(Comparator.comparing(Method::getName));
        for(var method:methods){
            String verb,route,media="application/json";String[] consumes={};
            if(method.isAnnotationPresent(GetMapping.class)){var m=method.getAnnotation(GetMapping.class);verb="get";route=m.value()[0];if(m.produces().length>0)media=m.produces()[0];}
            else if(method.isAnnotationPresent(PostMapping.class)){var m=method.getAnnotation(PostMapping.class);verb="post";route=m.value()[0];consumes=m.consumes();}
            else if(method.isAnnotationPresent(PatchMapping.class)){var m=method.getAnnotation(PatchMapping.class);verb="patch";route=m.value()[0];}
            else continue;
            String full="/api"+route;if(full.endsWith("/download"))media="text/csv";
            String operationId=method.getName()+(verb.equals("post")&&method.getName().equals("quota")?"Post":"");
            var operation=new TreeMap<String,Object>();operation.put("operationId",operationId);
            var parameters=new ArrayList<Object>();var multipart=new TreeMap<String,Object>();
            for(var parameter:method.getParameters()){
                if(parameter.isAnnotationPresent(RequestBody.class))operation.put("requestBody",Map.of("required",true,"content",Map.of("application/json",Map.of("schema",schema(parameter.getParameterizedType())))));
                if(parameter.isAnnotationPresent(PathVariable.class))parameters.add(Map.of("name",parameter.getName(),"in","path","required",true,"schema",schema(parameter.getParameterizedType())));
                if(parameter.isAnnotationPresent(RequestParam.class)){
                    var annotation=parameter.getAnnotation(RequestParam.class);
                    if(Arrays.asList(consumes).contains("multipart/form-data"))multipart.put(parameter.getName(),parameter.getType()==MultipartFile.class?Map.of("type","string","format","binary"):schema(parameter.getParameterizedType()));
                    else parameters.add(Map.of("name",parameter.getName(),"in","query","required",annotation.required()&&annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE),"schema",schema(parameter.getParameterizedType())));
                }
                if(parameter.isAnnotationPresent(RequestHeader.class)){var annotation=parameter.getAnnotation(RequestHeader.class);parameters.add(Map.of("name",annotation.value(),"in","header","required",annotation.required(),"schema",schema(parameter.getParameterizedType())));}
            }
            if(!multipart.isEmpty())operation.put("requestBody",Map.of("required",true,"content",Map.of("multipart/form-data",Map.of("schema",Map.of("type","object","additionalProperties",false,"required",new ArrayList<>(multipart.keySet()),"properties",multipart)))));
            if(!parameters.isEmpty())operation.put("parameters",parameters);
            Object responseSchema=media.equals("application/json")?responseOperations.get(operationId):Map.of("type","string");
            if(responseSchema==null)throw new IllegalStateException("Missing reviewed response schema for "+operationId);
            responseSchema=json.readValue(json.writeValueAsString(responseSchema).replace("#/$defs/","#/components/schemas/"),Object.class);
            var responses=new TreeMap<String,Object>();responses.put("200",Map.of("description","Successful synthetic response","content",Map.of(media,Map.of("schema",responseSchema))));
            for(String status:List.of("401","403","404","409","410","413","422","503"))responses.put(status,Map.of("description","See current authorization, revision and reconciliation rules","content",Map.of("application/json",Map.of("schema",Map.of("$ref","#/components/schemas/ApiError")))));
            operation.put("responses",responses);paths.computeIfAbsent(full,k->new TreeMap<>()).put(verb,operation);
        }
        definitions.put("ApiError",Map.of("type","object","required",List.of("error"),"properties",Map.of("error",Map.of("type","object","required",List.of("code"),"properties",Map.of("code",Map.of("type","string"),"retryAdvice",Map.of("type","string"))),"correlationId",Map.of("type","string"))));
        return Map.of("openapi","3.1.0","info",Map.of("title","Isolated synthetic workbench API","version","0.1.0","description","Generated from actual controller routes and request records, with reviewed response schemas checked against HTTP traces. Single synthetic tenant and test identity only; no production login, backend transport or deployment claim."),"servers",List.of(Map.of("url","http://127.0.0.1:18080")),"security",List.of(Map.of("SyntheticSubject",List.of())),"paths",paths,"components",Map.of("schemas",definitions,"securitySchemes",Map.of("SyntheticSubject",Map.of("type","apiKey","in","header","name","X-Test-Subject","description","Server-resolved local test fixture alias; only enabled by isolated synthetic startup boundary."))));
    }
    @Test void committedOpenApiMatchesActualRoutesAndRequestRecords()throws Exception {
        var value=document();var file=Path.of("../../contracts/platform.openapi.json");
        String canonical=CanonicalJson.canonicalize(json.writeValueAsString(value));
        if("true".equals(System.getenv("WB_UPDATE_API_CONTRACT")))Files.writeString(file,json.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n");
        assertTrue(Files.exists(file),"Run once with WB_UPDATE_API_CONTRACT=true to export reviewable contract");
        assertEquals(canonical,CanonicalJson.canonicalize(Files.readString(file)));
        var paths=(Map<?,?>)value.get("paths");assertTrue(paths.size()>=25);assertFalse(paths.containsKey("/provider/start"));
    }
    @Test void strictCommandRecordsRejectMissingOrNullDecisionFlagsAndFields(){
        var codec=new io.aitest.workbench.bootstrap.JsonConfiguration().strictJsonMapper();
        assertThrows(RuntimeException.class,()->codec.readValue("{\"operationHash\":\"hash\",\"expectedRevision\":1,\"reason\":\"reviewed\",\"idempotencyKey\":\"k\"}",WorkbenchApi.Decision.class));
        assertThrows(RuntimeException.class,()->codec.readValue("{\"approve\":null,\"operationHash\":\"hash\",\"expectedRevision\":1,\"reason\":\"reviewed\",\"idempotencyKey\":\"k\"}",WorkbenchApi.Decision.class));
        assertThrows(RuntimeException.class,()->codec.readValue("{\"environmentId\":null,\"idempotencyKey\":\"k\"}",WorkbenchApi.NewConversation.class));
    }
}
