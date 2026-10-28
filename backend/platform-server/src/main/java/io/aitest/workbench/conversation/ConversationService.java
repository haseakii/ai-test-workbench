package io.aitest.workbench.conversation;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.CommandScope;
import io.aitest.workbench.infrastructure.CommandScope.Kind;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.identity.Scope.Actor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static io.aitest.workbench.infrastructure.Store.*;

@Service
public class ConversationService {
    private final Store db;private final Scope scope;
    public ConversationService(Store db,Scope scope){this.db=db;this.scope=scope;}
    public Map<String,Object> create(Actor a,UUID environment,String key) {
        return db.command(a.id(),"conversation.create",key,Map.of("environmentId",environment.toString()),CommandScope.of(Kind.ENVIRONMENT,environment),()->scope.authorize(a,CommandScope.of(Kind.ENVIRONMENT,environment)),()->{
            scope.environment(a,environment);UUID conversation=UUID.randomUUID();
            db.update("INSERT INTO conversations(tenant_id,id,subject_id,environment_id,title) VALUES(?,?,?,?,?)",id(TENANT),id(conversation),id(a.id()),id(environment),"新问题");
            db.event(conversation,"conversation.created",Map.of());db.audit(a.id(),"conversation.create",conversation,Map.of());
            return Map.of("id",conversation.toString(),"revision",1);
        });
    }
    public Map<String,Object> message(Actor a,UUID conversation,String clientId,String text,long expected,String key) {
        if(text==null||text.isBlank()||text.length()>10000)throw new Problem(422,"INVALID_MESSAGE");
        return db.command(a.id(),"message.save",key,Map.of("conversationId",conversation.toString(),"clientMessageId",clientId,"text",text,"expectedRevision",expected),CommandScope.of(Kind.CONVERSATION,conversation),()->scope.authorize(a,CommandScope.of(Kind.CONVERSATION,conversation)),()->{
            var c=scope.ownConversation(a,conversation);
            var saved=db.rows("SELECT text_content,input_revision FROM messages WHERE tenant_id=? AND conversation_id=? AND client_message_id=?",id(TENANT),id(conversation),Store.key(clientId));
            if(!saved.isEmpty()){
                if(!text.equals(saved.getFirst().get("text_content")))throw new Problem(409,"CLIENT_MESSAGE_CONFLICT");
                return Map.of("status","saved","inputRevision",number(saved.getFirst().get("input_revision")),"revision",number(c.get("revision")));
            }
            if(number(c.get("revision"))!=expected)throw new Problem(409,"REVISION_CONFLICT");
            long input=number(c.get("input_revision"))+1;
            db.update("INSERT INTO messages VALUES(?,?,?,?,?,?)",id(TENANT),id(UUID.randomUUID()),id(conversation),Store.key(clientId),text,input);
            db.update("UPDATE conversations SET input_revision=?,revision=revision+1,state='awaiting_user',title=CASE WHEN title_source='placeholder' THEN ? ELSE title END,title_revision=title_revision+CASE WHEN title_source='placeholder' THEN 1 ELSE 0 END,title_source=CASE WHEN title_source='placeholder' THEN 'automatic' ELSE title_source END WHERE tenant_id=? AND id=?",input,text.substring(0,Math.min(40,text.length())),id(TENANT),id(conversation));
            db.update("UPDATE approvals p JOIN execution_bindings b ON b.tenant_id=p.tenant_id AND b.id=p.binding_id SET p.state='superseded',p.revision=p.revision+1 WHERE p.tenant_id=? AND p.conversation_id=? AND p.state IN ('pending','approved') AND b.closed_at IS NULL",id(TENANT),id(conversation));
            db.update("UPDATE artifacts SET state='superseded' WHERE tenant_id=? AND conversation_id=? AND state='ready'",id(TENANT),id(conversation));
            io.aitest.workbench.execution.ContextManifests.invalidate(db,conversation,"INPUT_CHANGED");
            db.event(conversation,"message.saved",Map.of("text",text,"inputRevision",input));
            return Map.of("status","saved","inputRevision",input,"revision",expected+1);
        });
    }
    public Map<String,Object> rename(Actor a,UUID conversation,String title,long expected,String key) {
        if(title==null||title.isBlank()||title.length()>160)throw new Problem(422,"INVALID_TITLE");
        return db.command(a.id(),"conversation.title",key,Map.of("id",conversation.toString(),"title",title,"expectedTitleRevision",expected),CommandScope.of(Kind.CONVERSATION,conversation),()->scope.authorize(a,CommandScope.of(Kind.CONVERSATION,conversation)),()->{
            var c=scope.ownConversation(a,conversation);
            if(number(c.get("title_revision"))!=expected)throw new Problem(409,"TITLE_REVISION_CONFLICT");
            db.update("UPDATE conversations SET title=?,title_revision=title_revision+1,title_source='manual' WHERE tenant_id=? AND id=?",title,id(TENANT),id(conversation));
            db.event(conversation,"title.updated",Map.of("title",title,"titleRevision",expected+1));
            db.audit(a.id(),"conversation.title",conversation,Map.of("titleRevision",expected+1));
            return Map.of("title",title,"titleRevision",expected+1);
        });
    }
    public List<Map<String,Object>> list(Actor a) {
        scope.active(a);return db.rows("SELECT c.id,c.title,c.state,c.revision FROM conversations c JOIN grants g ON g.tenant_id=c.tenant_id AND g.environment_id=c.environment_id AND g.subject_id=c.subject_id WHERE c.tenant_id=? AND c.subject_id=? AND g.active=TRUE ORDER BY c.id",id(TENANT),id(a.id())).stream().map(r->Map.<String,Object>of("id",uuid(r.get("id")),"title",r.get("title"),"state",r.get("state"),"revision",number(r.get("revision")))).toList();
    }
    public Map<String,Object> snapshot(Actor a,UUID conversation) {
        return db.transaction(()->{
            var c=scope.ownConversation(a,conversation);long watermark=number(c.get("event_sequence"));
            var result=new LinkedHashMap<String,Object>();result.put("id",conversation.toString());result.put("title",c.get("title"));result.put("state",c.get("state"));result.put("revision",number(c.get("revision")));result.put("titleRevision",number(c.get("title_revision")));result.put("inputRevision",number(c.get("input_revision")));result.put("eventWatermark",watermark);result.put("resumeCursor",cursor(a,conversation,watermark));
            result.put("environmentId",uuid(c.get("environment_id")));
            result.put("environmentName",db.one("SELECT name FROM config_objects WHERE tenant_id=? AND id=?",id(TENANT),c.get("environment_id")).get("name"));
            result.put("verifications",db.rows("SELECT v.artifact_id,v.resolved,v.result_revision FROM verifications v JOIN artifacts a ON a.tenant_id=v.tenant_id AND a.id=v.artifact_id WHERE a.tenant_id=? AND a.conversation_id=?",id(TENANT),id(conversation)).stream().map(v->Map.of("artifactId",uuid(v.get("artifact_id")),"resolved",v.get("resolved"),"resultRevision",number(v.get("result_revision")))).toList());
            result.put("messages",db.rows("SELECT text_content text,input_revision inputRevision FROM messages WHERE tenant_id=? AND conversation_id=? ORDER BY input_revision",id(TENANT),id(conversation)));
            result.put("attachments",db.rows("SELECT id,file_name,state,sha256,bytes,source_active FROM attachments WHERE tenant_id=? AND conversation_id=? ORDER BY id",id(TENANT),id(conversation)).stream().filter(r->Boolean.TRUE.equals(r.get("source_active"))).map(r->Map.of("id",uuid(r.get("id")),"name",r.get("file_name"),"state",r.get("state"),"sha256",hex(r.get("sha256")),"bytes",number(r.get("bytes")))).toList());
            result.put("approvals",db.rows("SELECT id,revision,state,plan_json,operation_hash,valid_until FROM approvals WHERE tenant_id=? AND conversation_id=? ORDER BY id",id(TENANT),id(conversation)).stream().map(r->Map.of("id",uuid(r.get("id")),"revision",number(r.get("revision")),"state",r.get("state"),"plan",db.object(r.get("plan_json")),"operationHash",hex(r.get("operation_hash")),"validUntil",r.get("valid_until").toString())).toList());
            result.put("executions",db.rows("SELECT b.id,b.state,b.accepted_at,b.closed_at,b.stopped FROM execution_bindings b WHERE b.tenant_id=? AND b.conversation_id=? ORDER BY b.id",id(TENANT),id(conversation)).stream().map(r->{var v=new LinkedHashMap<>(r);v.put("id",uuid(r.get("id")));return v;}).toList());
            boolean sourcesValid=db.rows("SELECT id FROM attachments WHERE tenant_id=? AND conversation_id=? AND source_active=FALSE",id(TENANT),id(conversation)).isEmpty();
            result.put("artifacts",sourcesValid?db.rows("SELECT id,result_revision,input_revision,summary_json,sha256,state FROM artifacts WHERE tenant_id=? AND conversation_id=? AND state='ready'",id(TENANT),id(conversation)).stream().map(r->Map.of("id",uuid(r.get("id")),"resultRevision",number(r.get("result_revision")),"inputRevision",number(r.get("input_revision")),"summary",db.object(r.get("summary_json")),"sha256",hex(r.get("sha256")),"state",r.get("state"))).toList():List.of());
            return result;
        });
    }
    public String cursor(Actor a,UUID conversation,long sequence) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((conversation+":"+a.id()+":"+sequence).getBytes(StandardCharsets.UTF_8));
    }
    public Map<String,Object> events(Actor a,UUID conversation,String cursor,int limit) {
        if(limit<1||limit>100)throw new Problem(422,"INVALID_LIMIT");
        return db.transaction(()->{
            var c=scope.ownConversation(a,conversation);long after=0;
            if(cursor!=null&&!cursor.isBlank()){
                try {
                    String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":");
                    if(parts.length!=3||!parts[0].equals(conversation.toString())||!parts[1].equals(a.id().toString()))throw new IllegalArgumentException();
                    after=io.aitest.contract.ContractChecks.safeInteger(Long.parseLong(parts[2]));
                }catch(Exception e){throw new Problem(410,"CURSOR_INVALID");}
            }
            if(after>number(c.get("event_sequence")))throw new Problem(410,"SNAPSHOT_REQUIRED");
            var events=db.rows("SELECT sequence,event_type,payload FROM platform_events WHERE tenant_id=? AND conversation_id=? AND sequence>? ORDER BY sequence LIMIT ?",id(TENANT),id(conversation),after,limit);
            long next=events.isEmpty()?after:number(events.getLast().get("sequence"));
            return Map.of("events",events.stream().map(r->Map.of("sequence",number(r.get("sequence")),"type",r.get("event_type"),"payload",db.object(r.get("payload")))).toList(),"cursor",cursor(a,conversation,next),"hasMore",next<number(c.get("event_sequence")));
        });
    }
}
