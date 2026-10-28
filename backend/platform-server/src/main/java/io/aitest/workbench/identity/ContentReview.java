package io.aitest.workbench.identity;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.CommandScope;
import io.aitest.workbench.infrastructure.CommandScope.Kind;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.identity.Scope.Actor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;

@Service
public class ContentReview {
    private final Store db;private final Scope scope;
    public ContentReview(Store db,Scope scope){this.db=db;this.scope=scope;}
    public Map<String,Object> eligibility(Actor admin,String alias,UUID environment,boolean enabled,long expected,String reason,String key){
        scope.admin(admin);if(reason==null||reason.length()<5||reason.length()>1000)throw new Problem(422,"REASON_REQUIRED");
        return db.command(admin.id(),"content.eligibility",key,Map.of("alias",alias,"environmentId",environment.toString(),"enabled",enabled,"expectedAuthorizationRevision",expected,"reason",reason),CommandScope.admin(),()->scope.authorize(admin,CommandScope.admin()),()->{
            scope.admin(admin);var target=scope.actor(alias);scope.reviewer(target);scope.environment(target,environment);
            if(admin.id().equals(target.id()))throw new Problem(403,"SELF_ESCALATION");
            if(target.revision()!=expected)throw new Problem(409,"REVISION_CONFLICT");
            db.update("UPDATE grants SET content_read=?,revision=revision+1 WHERE tenant_id=? AND subject_id=? AND environment_id=?",enabled,id(TENANT),id(target.id()),id(environment));
            db.update("UPDATE memberships SET revision=revision+1 WHERE tenant_id=? AND subject_id=?",id(TENANT),id(target.id()));
            db.audit(admin.id(),"content.eligibility",target.id(),Map.of("environmentId",environment.toString(),"enabled",enabled,"reason",reason));
            return Map.of("state",enabled?"granted":"revoked","authorizationRevision",expected+1);
        });
    }
    private Map<String,Object> contentGrant(Actor a,UUID conversation){
        return scope.contentGrant(a,conversation);
    }
    public Map<String,Object> lease(Actor a,UUID conversation,String purpose,int seconds,String key){
        if(purpose==null||purpose.length()<5||purpose.length()>1000||seconds<1||seconds>300)throw new Problem(422,"INVALID_CONTENT_LEASE");
        return db.command(a.id(),"content.lease",key,Map.of("conversationId",conversation.toString(),"purpose",purpose,"seconds",seconds),CommandScope.of(Kind.CONTENT,conversation),()->scope.authorize(a,CommandScope.of(Kind.CONTENT,conversation)),()->{
            var grant=contentGrant(a,conversation);long authorization=scope.actor(a.alias()).revision();Instant until=db.now().plusSeconds(seconds);
            var prior=db.rows("SELECT subject_id FROM content_leases WHERE tenant_id=? AND conversation_id=? AND subject_id=?",id(TENANT),id(conversation),id(a.id()));
            if(prior.isEmpty())db.update("INSERT INTO content_leases VALUES(?,?,?,?,?,?,?)",id(TENANT),id(conversation),id(a.id()),LocalDateTime.ofInstant(until,ZoneOffset.UTC),purpose,authorization,number(grant.get("revision")));
            else db.update("UPDATE content_leases SET valid_until=?,purpose=?,authorization_revision=?,grant_revision=? WHERE tenant_id=? AND conversation_id=? AND subject_id=?",LocalDateTime.ofInstant(until,ZoneOffset.UTC),purpose,authorization,number(grant.get("revision")),id(TENANT),id(conversation),id(a.id()));
            db.audit(a.id(),"content.lease",conversation,Map.of("purpose",purpose,"validUntil",until.toString(),"scope","messages_only"));
            return Map.of("conversationId",conversation.toString(),"validUntil",until.toString(),"scope","messages_only");
        });
    }
    public Map<String,Object> read(Actor a,UUID conversation){
        return db.transaction(()->{
            var lease=validLease(a,conversation);
            var messages=db.rows("SELECT text_content text,input_revision inputRevision FROM messages WHERE tenant_id=? AND conversation_id=? ORDER BY input_revision LIMIT 100",id(TENANT),id(conversation));
            db.audit(a.id(),"content.read",conversation,Map.of("purpose",lease.get("purpose"),"scope","messages_only"));
            return Map.of("conversationId",conversation.toString(),"messages",messages,"validUntil",instant(lease.get("valid_until")).toString());
        });
    }
    private Map<String,Object> validLease(Actor a,UUID conversation){
        var grant=contentGrant(a,conversation);
        var lease=db.one("SELECT * FROM content_leases WHERE tenant_id=? AND conversation_id=? AND subject_id=?",id(TENANT),id(conversation),id(a.id()));
        if(!db.now().isBefore(instant(lease.get("valid_until")))||number(lease.get("authorization_revision"))!=scope.actor(a.alias()).revision()||number(lease.get("grant_revision"))!=number(grant.get("revision")))throw new Problem(403,"CONTENT_LEASE_EXPIRED");
        return lease;
    }
    public Map<String,Object> status(Actor a,UUID conversation){return db.transaction(()->Map.of("validUntil",instant(validLease(a,conversation).get("valid_until")).toString(),"scope","messages_only"));}
    public List<Map<String,Object>> records(Actor a){
        return records(a,"");
    }
    public List<Map<String,Object>> records(Actor a,String query){
        scope.active(a);var role=scope.actor(a.alias()).role();boolean admin=role.equals("admin");
        if(!admin&&!role.equals("reviewer"))throw new Problem(403,"RECORDS_LIST_FORBIDDEN");
        query=Objects.requireNonNullElse(query,"").strip();if(query.length()>160)throw new Problem(422,"INVALID_RECORD_QUERY");
        String sql=admin?"SELECT c.*,FALSE can_read FROM conversations c WHERE c.tenant_id=? ORDER BY (SELECT e.occurred_at FROM platform_events e WHERE e.tenant_id=c.tenant_id AND e.conversation_id=c.id AND e.sequence=1) DESC,c.id LIMIT 100":"SELECT c.*,g.content_read can_read FROM conversations c JOIN grants g ON g.tenant_id=c.tenant_id AND g.environment_id=c.environment_id WHERE c.tenant_id=? AND g.subject_id=? AND g.active=TRUE ORDER BY (SELECT e.occurred_at FROM platform_events e WHERE e.tenant_id=c.tenant_id AND e.conversation_id=c.id AND e.sequence=1) DESC,c.id LIMIT 100";
        sql=sql.replace(" ORDER BY "," AND LOCATE(?,c.title)>0 ORDER BY ");
        var rows=admin?db.rows(sql,id(TENANT),query):db.rows(sql,id(TENANT),id(a.id()),query);
        return rows.stream().map(r->Map.<String,Object>of("conversationId",uuid(r.get("id")),"title",r.get("title"),"state",r.get("state"),"subjectId",uuid(r.get("subject_id")),"environmentId",uuid(r.get("environment_id")),"inputRevision",number(r.get("input_revision")),"canReadContent",Boolean.TRUE.equals(r.get("can_read")))).toList();
    }
}
