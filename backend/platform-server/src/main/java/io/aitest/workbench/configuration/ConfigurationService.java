package io.aitest.workbench.configuration;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.CommandScope;
import io.aitest.workbench.infrastructure.CommandScope.Kind;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.identity.Scope.Actor;
import io.aitest.contract.CanonicalJson;
import org.springframework.stereotype.Service;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;

@Service
public class ConfigurationService {
    private final Store db; private final Scope scope;
    public ConfigurationService(Store db,Scope scope){this.db=db;this.scope=scope;}
    public Map<String,Object> create(Actor actor,String kind,String name,Map<String,Object> content,String key) {
        scope.admin(actor);
        if(name==null||name.isBlank()||name.length()>160)throw new Problem(422,"INVALID_CONFIGURATION");
        validate(kind,content);
        return db.command(actor.id(),"configuration.create",key,Map.of("kind",kind,"name",name,"content",content),CommandScope.admin(),()->scope.authorize(actor,CommandScope.admin()),()->{
            scope.admin(actor); UUID object=UUID.randomUUID();
            db.update("INSERT INTO config_objects(tenant_id,id,kind,name,draft_json) VALUES(?,?,?,?,?)",id(TENANT),id(object),kind,name,db.json(content));
            db.audit(actor.id(),"configuration.draft",object,Map.of("kind",kind));
            return Map.of("id",object.toString(),"revision",0,"draftBase",0);
        });
    }
    public List<Map<String,Object>> list(Actor a) {
        scope.admin(a);
        return db.rows("SELECT id,kind,name,revision,draft_base,disabled,draft_json,published_json FROM config_objects WHERE tenant_id=? ORDER BY name",id(TENANT))
            .stream().map(this::view).toList();
    }
    private Map<String,Object> view(Map<String,Object> r) {
        var v=new LinkedHashMap<String,Object>();v.put("id",uuid(r.get("id")));v.put("kind",r.get("kind"));v.put("name",r.get("name"));v.put("revision",number(r.get("revision")));v.put("draftBase",number(r.get("draft_base")));v.put("disabled",r.get("disabled"));v.put("draft",db.object(r.get("draft_json")));v.put("draftHash",CanonicalJson.hash(db.json(db.object(r.get("draft_json")))));if(r.get("published_json")!=null)v.put("published",db.object(r.get("published_json")));return v;
    }
    public Map<String,Object> draft(Actor a,UUID object,long expected,String expectedDraftHash,Map<String,Object> content,String key){
        scope.admin(a);if(expectedDraftHash==null||!expectedDraftHash.matches("[a-f0-9]{64}"))throw new Problem(422,"DRAFT_HASH_REQUIRED");return db.command(a.id(),"configuration.draft",key,Map.of("id",object.toString(),"expectedRevision",expected,"expectedDraftHash",expectedDraftHash,"content",content),CommandScope.admin(),()->scope.authorize(a,CommandScope.admin()),()->{
            scope.admin(a);var r=db.one("SELECT revision,kind,draft_json FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),id(object));
            validate(r.get("kind").toString(),content);
            if(number(r.get("revision"))!=expected||!CanonicalJson.hash(db.json(db.object(r.get("draft_json")))).equals(expectedDraftHash))throw new Problem(409,"REVISION_CONFLICT");
            db.update("UPDATE config_objects SET draft_json=?,draft_base=? WHERE tenant_id=? AND id=?",db.json(content),expected,id(TENANT),id(object));
            db.audit(a.id(),"configuration.draft",object,Map.of("expectedRevision",expected));return Map.of("id",object.toString(),"draftBase",expected);
        });
    }
    public record Publication(String id,long expectedRevision,Map<String,Long> dependencyRevisions){}
    public Map<String,Object> publish(Actor a,List<Publication> selected,String key) {
        scope.admin(a);if(selected.isEmpty())throw new Problem(422,"EMPTY_CHANGESET");
        return db.command(a.id(),"configuration.publish",key,selected,CommandScope.admin(),()->scope.authorize(a,CommandScope.admin()),()->{
            scope.admin(a);var ids=new HashSet<String>();
            for(var item:selected)if(!ids.add(item.id()))throw new Problem(422,"DUPLICATE_OBJECT");
            var objects=new LinkedHashMap<Publication,Map<String,Object>>();
            for(var item:selected) {
                var r=db.one("SELECT * FROM config_objects WHERE tenant_id=? AND id=? FOR UPDATE",id(TENANT),id(item.id()));
                if(number(r.get("revision"))!=item.expectedRevision()||number(r.get("draft_base"))!=item.expectedRevision())throw new Problem(409,"REVISION_CONFLICT");
                var content=db.object(r.get("draft_json"));
                validate(r.get("kind").toString(),content);
                var used=new HashSet<String>();
                for(String ref:List.of("modelRef","workflowRef"))if(content.containsKey(ref))used.add(content.get(ref).toString());
                if(!used.equals(item.dependencyRevisions().keySet()))throw new Problem(409,"DEPENDENCIES_CHANGED");
                for(String ref:used) {
                    var dep=db.one("SELECT revision,disabled,kind FROM config_objects WHERE tenant_id=? AND id=? FOR UPDATE",id(TENANT),id(ref));
                    String required=Objects.equals(content.get("modelRef"),ref)?"model":"workflow";
                    if(!required.equals(dep.get("kind")))throw new Problem(422,"DEPENDENCY_KIND_MISMATCH");
                    if(number(dep.get("revision"))!=item.dependencyRevisions().get(ref)||Boolean.TRUE.equals(dep.get("disabled")))throw new Problem(409,"DEPENDENCY_REVISION_CONFLICT");
                    if(number(dep.get("revision"))==0&&!ids.contains(ref))throw new Problem(422,"UNPUBLISHED_DEPENDENCY");
                }
                objects.put(item,r);
            }
            for(var entry:objects.entrySet()){
                var item=entry.getKey();var r=entry.getValue();long revision=item.expectedRevision()+1;
                var content=db.object(r.get("draft_json"));var frozen=new LinkedHashMap<>(content);
                var dependencies=new TreeMap<String,Long>();
                item.dependencyRevisions().forEach((ref,base)->dependencies.put(ref,base+(ids.contains(ref)?1:0)));
                frozen.put("dependencyRevisions",dependencies);String encoded=db.json(frozen);
                db.update("INSERT INTO config_versions VALUES(?,?,?,?,?)",id(TENANT),id(item.id()),revision,encoded,hex(CanonicalJson.hash(encoded)));
                db.update("UPDATE config_objects SET revision=?,published_json=?,draft_base=? WHERE tenant_id=? AND id=?",revision,encoded,revision,id(TENANT),id(item.id()));
                db.audit(a.id(),"configuration.publish",UUID.fromString(item.id()),Map.of("revision",revision,"dependencies",dependencies));
            }
            return Map.of("published",selected.stream().map(i->Map.of("id",i.id(),"revision",i.expectedRevision()+1)).toList());
        });
    }
    public Map<String,Object> grant(Actor a,String alias,UUID environment,String role,String key) {
        scope.admin(a);
        if(!Set.of("member","reviewer").contains(role))throw new Problem(422,"INVALID_ROLE");
        return db.command(a.id(),"membership.grant",key,Map.of("alias",alias,"environmentId",environment.toString(),"role",role),CommandScope.admin(),()->scope.authorize(a,CommandScope.admin()),()->{
            scope.admin(a);scope.environment(a,environment);var target=scope.actor(alias);
            if(target.id().equals(a.id()))throw new Problem(403,"SELF_ESCALATION");
            db.update("UPDATE memberships SET state='active',role_name=?,revision=revision+1 WHERE tenant_id=? AND subject_id=?",role,id(TENANT),id(target.id()));
            var existing=db.rows("SELECT revision FROM grants WHERE tenant_id=? AND subject_id=? AND environment_id=?",id(TENANT),id(target.id()),id(environment));
            if(existing.isEmpty())db.update("INSERT INTO grants(tenant_id,subject_id,environment_id) VALUES(?,?,?)",id(TENANT),id(target.id()),id(environment));
            else db.update("UPDATE grants SET active=TRUE,content_read=FALSE,revision=revision+1 WHERE tenant_id=? AND subject_id=? AND environment_id=?",id(TENANT),id(target.id()),id(environment));
            if(db.rows("SELECT occupied FROM active_slots WHERE tenant_id=? AND subject_id=?",id(TENANT),id(target.id())).isEmpty())db.update("INSERT INTO active_slots(tenant_id,subject_id) VALUES(?,?)",id(TENANT),id(target.id()));
            db.audit(a.id(),"membership.grant",target.id(),Map.of("environmentId",environment.toString(),"role",role));
            return Map.of("subjectId",target.id().toString(),"state","active");
        });
    }
    public Map<String,Object> revoke(Actor a,String alias,UUID environment,String key) {
        scope.admin(a);return db.command(a.id(),"membership.revoke",key,Map.of("alias",alias,"environmentId",environment.toString()),CommandScope.admin(),()->scope.authorize(a,CommandScope.admin()),()->{
            scope.admin(a);var target=scope.actor(alias);
            db.update("UPDATE grants SET active=FALSE,revision=revision+1 WHERE tenant_id=? AND subject_id=? AND environment_id=?",id(TENANT),id(target.id()),id(environment));
            for(var conversation:db.rows("SELECT id FROM conversations WHERE tenant_id=? AND subject_id=? AND environment_id=?",id(TENANT),id(target.id()),id(environment)))io.aitest.workbench.execution.ContextManifests.invalidate(db,UUID.fromString(uuid(conversation.get("id"))),"AUTHORIZATION_REVOKED");
            db.update("UPDATE memberships SET revision=revision+1 WHERE tenant_id=? AND subject_id=?",id(TENANT),id(target.id()));
            db.update("UPDATE execution_bindings b JOIN conversations c ON c.tenant_id=b.tenant_id AND c.id=b.conversation_id SET b.stopped=TRUE WHERE c.tenant_id=? AND c.subject_id=? AND c.environment_id=? AND b.closed_at IS NULL",id(TENANT),id(target.id()),id(environment));
            db.audit(a.id(),"membership.revoke",target.id(),Map.of("environmentId",environment.toString()));
            return Map.of("state","revoked");
        });
    }
    public Map<String,Object> disable(Actor a,UUID object,String key) {
        scope.admin(a);return db.command(a.id(),"configuration.disable",key,Map.of("id",object.toString()),CommandScope.admin(),()->scope.authorize(a,CommandScope.admin()),()->{
            scope.admin(a);db.update("UPDATE config_objects SET disabled=TRUE WHERE tenant_id=? AND id=?",id(TENANT),id(object));
            db.audit(a.id(),"configuration.disable",object,Map.of());return Map.of("state","disabled");
        });
    }
    public List<Map<String,Object>> members(Actor a) {
        scope.admin(a);return db.rows("SELECT s.id,CONVERT(s.alias USING utf8mb4) alias,m.role_name,m.state,m.revision FROM subjects s JOIN memberships m ON m.subject_id=s.id WHERE m.tenant_id=?",id(TENANT)).stream().map(r->Map.<String,Object>of("id",uuid(r.get("id")),"alias",r.get("alias"),"role",r.get("role_name"),"state",r.get("state"),"revision",number(r.get("revision")))).toList();
    }
    public List<Map<String,Object>> options(Actor a) {
        if(!a.state().equals("active"))return List.of();
        return db.rows("SELECT c.id,c.name,c.revision FROM config_objects c JOIN grants g ON g.environment_id=c.id AND g.tenant_id=c.tenant_id WHERE c.tenant_id=? AND g.subject_id=? AND g.active=TRUE AND c.kind='environment' AND c.disabled=FALSE AND c.revision>0",id(TENANT),id(a.id())).stream().map(r->Map.<String,Object>of("id",uuid(r.get("id")),"name",r.get("name"),"revision",number(r.get("revision")))).toList();
    }
    static void validate(String kind,Map<String,Object> content){
        if(kind==null||content==null)throw new Problem(422,"INVALID_CONFIGURATION");
        Set<String> allowed=switch(kind){
            case "model" -> Set.of("backend","modelCalls","description");
            case "workflow" -> Set.of("operation","description");
            case "environment" -> Set.of("modelRef","workflowRef","timeZone","turnLimit","testMode","description");
            default -> throw new Problem(422,"INVALID_CONFIGURATION");
        };
        if(!allowed.containsAll(content.keySet())||content.values().stream().anyMatch(Objects::isNull))throw new Problem(422,"INVALID_CONFIGURATION_FIELDS");
        if(content.containsKey("description")&&(!(content.get("description") instanceof String text)||text.length()>1000))throw new Problem(422,"INVALID_DESCRIPTION");
        switch(kind){
            case "model" -> {if(!"persistent_fixture".equals(content.get("backend"))||!Boolean.FALSE.equals(content.get("modelCalls")))throw new Problem(422,"SYNTHETIC_BACKEND_REQUIRED");}
            case "workflow" -> {if(!"reconcile_csv_readonly_v1".equals(content.get("operation")))throw new Problem(422,"UNSUPPORTED_OPERATION");}
            case "environment" -> {
                for(String ref:List.of("modelRef","workflowRef")){if(!(content.get(ref) instanceof String text))throw new Problem(422,"MISSING_DEPENDENCY");id(text);}
                if(content.get("modelRef").equals(content.get("workflowRef")))throw new Problem(422,"DEPENDENCY_KIND_MISMATCH");
                if(!(content.get("timeZone") instanceof String zone))throw new Problem(422,"TIME_ZONE_REQUIRED");
                try{java.time.ZoneId.of(zone);}catch(java.time.DateTimeException e){throw new Problem(422,"INVALID_TIME_ZONE");}
                if(content.containsKey("turnLimit"))number(content.get("turnLimit"));
                if(content.containsKey("testMode")&&!Set.of("normal","drop_receipt","hold_after_accept","hash_failure").contains(content.get("testMode")))throw new Problem(422,"UNSUPPORTED_TEST_MODE");
            }
        }
    }
}
