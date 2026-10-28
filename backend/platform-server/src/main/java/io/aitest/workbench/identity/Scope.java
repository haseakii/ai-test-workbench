package io.aitest.workbench.identity;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.infrastructure.CommandScope;
import org.springframework.stereotype.Component;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;

@Component
public class Scope {
    private final Store db;
    public Scope(Store db){this.db=db;}
    public record Actor(UUID id,String alias,String role,String state,long revision){}
    public Actor actor(String alias) {
        if(alias==null || alias.length()>80)throw new Problem(401,"TEST_IDENTITY_REQUIRED");
        var r=db.one("SELECT s.id,s.alias,m.role_name,m.state,m.revision FROM subjects s JOIN memberships m ON m.subject_id=s.id WHERE m.tenant_id=? AND s.alias=?",id(TENANT),key(alias));
        return new Actor(UUID.fromString(uuid(r.get("id"))),alias,r.get("role_name").toString(),r.get("state").toString(),number(r.get("revision")));
    }
    public void active(Actor a){var r=db.one("SELECT state FROM memberships WHERE tenant_id=? AND subject_id=?",id(TENANT),id(a.id()));if(!"active".equals(r.get("state")))throw new Problem(403,"MEMBER_NOT_ACTIVE");}
    public void admin(Actor a){active(a);if(!actor(a.alias()).role().equals("admin"))throw new Problem(403,"ADMIN_REQUIRED");}
    public void reviewer(Actor a){active(a);if(!actor(a.alias()).role().equals("reviewer"))throw new Problem(403,"APPROVER_REQUIRED");}
    public Map<String,Object> environment(Actor a,UUID env) {
        active(a);var r=db.one("SELECT * FROM config_objects WHERE tenant_id=? AND id=? AND kind='environment' AND revision>0 AND disabled=FALSE",id(TENANT),id(env));
        if(!actor(a.alias()).role().equals("admin") && db.rows("SELECT subject_id FROM grants WHERE tenant_id=? AND subject_id=? AND environment_id=? AND active=TRUE",id(TENANT),id(a.id()),id(env)).isEmpty())throw new Problem(404,"NOT_FOUND");
        return r;
    }
    public Map<String,Object> ownConversation(Actor a,UUID conversation) {
        active(a);var c=db.one("SELECT * FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(conversation));
        if(!uuid(c.get("subject_id")).equals(a.id().toString()))throw new Problem(404,"NOT_FOUND");
        environment(a,UUID.fromString(uuid(c.get("environment_id"))));return c;
    }
    public void authorize(Actor a,CommandScope access){
        switch(access.kind()){
            case SUBJECT -> {
                var current=actor(a.alias());
                if(!Set.of("active","pending").contains(current.state()))throw new Problem(403,"MEMBER_NOT_ACTIVE");
            }
            case ADMIN -> admin(a);
            case ENVIRONMENT -> environment(a,access.objectId());
            case CONVERSATION -> ownConversation(a,access.objectId());
            case APPROVAL -> {
                reviewer(a);var p=db.one("SELECT plan_json FROM approvals WHERE tenant_id=? AND id=?",id(TENANT),id(access.objectId()));
                environment(a,UUID.fromString(db.object(p.get("plan_json")).get("environmentId").toString()));
            }
            case ATTACHMENT -> {
                var r=db.one("SELECT conversation_id FROM attachments WHERE tenant_id=? AND id=?",id(TENANT),id(access.objectId()));
                ownConversation(a,UUID.fromString(uuid(r.get("conversation_id"))));
            }
            case ARTIFACT -> artifact(a,access.objectId());
            case CONTENT -> contentGrant(a,access.objectId());
        }
    }
    public void receipt(Actor a,Map<String,Object> row){
        if(row.get("access_kind")==null)throw new Problem(410,"RECEIPT_SCOPE_UNAVAILABLE");
        authorize(a,new CommandScope(CommandScope.Kind.valueOf(row.get("access_kind").toString()),row.get("access_id")==null?null:UUID.fromString(uuid(row.get("access_id")))));
    }
    public Map<String,Object> artifact(Actor a,UUID artifact){
        var r=db.one("SELECT * FROM artifacts WHERE tenant_id=? AND id=?",id(TENANT),id(artifact));
        UUID c=UUID.fromString(uuid(r.get("conversation_id")));var conv=ownConversation(a,c);
        if(!"ready".equals(r.get("state"))||number(r.get("input_revision"))!=number(conv.get("input_revision")))throw new Problem(409,"RESULT_STALE");
        var plan=db.object(db.object(r.get("summary_json")).get("plan"));
        for(String field:List.of("detailAttachmentId","reportAttachmentId")){
            var source=db.one("SELECT state,source_active,sha256 FROM attachments WHERE tenant_id=? AND conversation_id=? AND id=?",id(TENANT),id(c),id(plan.get(field).toString()));
            if(!Boolean.TRUE.equals(source.get("source_active"))||!"ready".equals(source.get("state"))||!hex(source.get("sha256")).equals(plan.get(field+"Hash")))throw new Problem(409,"SOURCE_REVOKED_OR_CHANGED");
        }
        return r;
    }
    public Map<String,Object> contentGrant(Actor a,UUID conversation){
        reviewer(a);
        var c=db.one("SELECT * FROM conversations WHERE tenant_id=? AND id=?",id(TENANT),id(conversation));
        environment(a,UUID.fromString(uuid(c.get("environment_id"))));
        var grant=db.one("SELECT * FROM grants WHERE tenant_id=? AND subject_id=? AND environment_id=? AND active=TRUE",id(TENANT),id(a.id()),c.get("environment_id"));
        if(!Boolean.TRUE.equals(grant.get("content_read")))throw new Problem(403,"CONTENT_REVIEW_NOT_GRANTED");
        if(db.rows("SELECT g.subject_id FROM grants g JOIN memberships m ON m.tenant_id=g.tenant_id AND m.subject_id=g.subject_id WHERE g.tenant_id=? AND g.subject_id=? AND g.environment_id=? AND g.active=TRUE AND m.state='active'",id(TENANT),c.get("subject_id"),c.get("environment_id")).isEmpty())throw new Problem(403,"SOURCE_AUTHORIZATION_REVOKED");
        return grant;
    }
}
