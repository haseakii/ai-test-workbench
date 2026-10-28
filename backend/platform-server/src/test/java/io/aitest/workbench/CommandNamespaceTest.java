package io.aitest.workbench;

import io.aitest.contract.CanonicalJson;
import io.aitest.workbench.infrastructure.*;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.conversation.ConversationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static io.aitest.workbench.infrastructure.Store.*;

/** Foreign rows are deliberate canaries inside the existing approved platform test DB.
 * Every test rolls back its fixtures. This verifies isolation, not a multi-tenant HTTP feature. */
@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class CommandNamespaceTest {
    record Fixture(Store db,Scope scope,ConversationService conversations,Scope.Actor alice,Scope.Actor bob,UUID environment,UUID foreignTenant){}
    void fixture(Consumer<Fixture> check){
        var source=new DriverManagerDataSource(System.getenv("WB_DB_URL"),System.getenv("WB_DB_USER"),System.getenv("WB_DB_PASSWORD"));
        var manager=new DataSourceTransactionManager(source);var db=new Store(new JdbcTemplate(source),manager);var scope=new Scope(db);
        new TransactionTemplate(manager).execute(status->{
            status.setRollbackOnly(); // Includes every nested command and every assertion failure.
            db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE",id(TENANT));
            UUID foreignTenant=UUID.randomUUID(),environment=UUID.randomUUID();
            db.update("INSERT INTO tenants(id) VALUES(?)",id(foreignTenant));
            String alice="namespace-a-"+UUID.randomUUID(),bob="namespace-b-"+UUID.randomUUID();
            for(String alias:List.of(alice,bob)){
                UUID subject=UUID.randomUUID();db.update("INSERT INTO subjects VALUES(?,?)",id(subject),key(alias));
                for(UUID tenant:List.of(TENANT,foreignTenant))db.update("INSERT INTO memberships(tenant_id,subject_id,role_name,state) VALUES(?,?,'member','active')",id(tenant),id(subject));
            }
            db.update("INSERT INTO config_objects(tenant_id,id,kind,name,revision,published_json,draft_json) VALUES(?,?,'environment','namespace canary fixture',1,'{}','{}')",id(TENANT),id(environment));
            for(String alias:List.of(alice,bob))db.update("INSERT INTO grants(tenant_id,subject_id,environment_id) VALUES(?,?,?)",id(TENANT),id(scope.actor(alias).id()),id(environment));
            check.accept(new Fixture(db,scope,new ConversationService(db,scope),scope.actor(alice),scope.actor(bob),environment,foreignTenant));return null;
        });
    }
    void canary(Fixture f,UUID tenant,UUID subject,String kind,String key,String requestHash){
        f.db().update("INSERT INTO commands(tenant_id,id,subject_id,kind,idempotency_key,request_hash,response_json,access_kind) VALUES(?,?,?,?,?,?,?,'SUBJECT')",id(tenant),id(UUID.randomUUID()),id(subject),Store.key(kind),Store.key(key),hex(requestHash),"{\"privateCanary\":\"foreign-namespace-only\"}");
    }
    void problem(String expected,int status,Runnable action){var error=assertThrows(Store.Problem.class,action::run);assertEquals(expected,error.code);assertEquals(status,error.status);}
    @Test void crossTenantSameSubjectKindAndKeyCannotExposeConflictOrCachedReceipt(){
        fixture(f->{
            String localHash=CanonicalJson.hash(f.db().json(Map.of("method","conversation.create","body",Map.of("environmentId",f.environment().toString()))));
            for(String foreignHash:List.of(localHash,"f".repeat(64))){
                String key=UUID.randomUUID().toString();canary(f,f.foreignTenant(),f.alice().id(),"conversation.create",key,foreignHash);
                var response=f.conversations().create(f.alice(),f.environment(),key);
                assertTrue(response.containsKey("commandId"));assertTrue(response.containsKey("id"));assertFalse(response.containsKey("privateCanary"));
                assertEquals(response,f.conversations().create(f.alice(),f.environment(),key));
                assertEquals(1,f.db().rows("SELECT id FROM commands WHERE tenant_id=? AND subject_id=? AND kind=? AND idempotency_key=?",id(TENANT),id(f.alice().id()),Store.key("conversation.create"),Store.key(key)).size());
                assertEquals(1,f.db().rows("SELECT id FROM commands WHERE tenant_id=? AND subject_id=? AND kind=? AND idempotency_key=?",id(f.foreignTenant()),id(f.alice().id()),Store.key("conversation.create"),Store.key(key)).size());
            }
        });
    }
    @Test void crossSubjectSameTenantKindAndKeyCreatesSeparateOwnedReceipts(){
        fixture(f->{
            String key=UUID.randomUUID().toString();var a=f.conversations().create(f.alice(),f.environment(),key);var b=f.conversations().create(f.bob(),f.environment(),key);
            assertNotEquals(a.get("commandId"),b.get("commandId"));assertNotEquals(a.get("id"),b.get("id"));
            problem("NOT_FOUND",404,()->f.scope().ownConversation(f.bob(),UUID.fromString(a.get("id").toString())));
            problem("NOT_FOUND",404,()->f.scope().ownConversation(f.alice(),UUID.fromString(b.get("id").toString())));
            assertEquals(a,f.conversations().create(f.alice(),f.environment(),key));assertEquals(b,f.conversations().create(f.bob(),f.environment(),key));
        });
    }
    @Test void crossCommandKindSameTenantSubjectAndKeyCannotCauseHashConflict(){
        fixture(f->{
            String key=UUID.randomUUID().toString();canary(f,TENANT,f.alice().id(),"message.save",key,"e".repeat(64));
            var response=f.conversations().create(f.alice(),f.environment(),key);assertTrue(response.containsKey("id"));assertFalse(response.containsKey("privateCanary"));
            problem("IDEMPOTENCY_CONFLICT",409,()->f.conversations().create(f.alice(),UUID.randomUUID(),key));
        });
    }
    @Test void inactiveOrUnknownMembershipIsRejectedBeforeEqualOrConflictingHash(){
        fixture(f->{
            String key=UUID.randomUUID().toString();f.conversations().create(f.alice(),f.environment(),key);
            f.db().update("UPDATE memberships SET state='suspended' WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.alice().id()));
            problem("MEMBER_NOT_ACTIVE",403,()->f.conversations().create(f.alice(),f.environment(),key));
            problem("MEMBER_NOT_ACTIVE",403,()->f.conversations().create(f.alice(),UUID.randomUUID(),key));
            problem("UNAUTHENTICATED",401,()->f.db().command(UUID.randomUUID(),"conversation.create",key,Map.of(),CommandScope.subject(),()->{},Map::of));
        });
    }
    @Test void revokedObjectEqualContentCannotReplayAFormerlyAuthorizedReceipt(){
        fixture(f->{
            String key=UUID.randomUUID().toString();var response=f.conversations().create(f.alice(),f.environment(),key);
            f.db().update("UPDATE grants SET active=FALSE,revision=revision+1 WHERE tenant_id=? AND subject_id=? AND environment_id=?",id(TENANT),id(f.alice().id()),id(f.environment()));
            problem("NOT_FOUND",404,()->f.conversations().create(f.alice(),f.environment(),key));
            var row=f.db().one("SELECT access_kind,access_id FROM commands WHERE tenant_id=? AND subject_id=? AND id=?",id(TENANT),id(f.alice().id()),id(response.get("commandId").toString()));
            problem("NOT_FOUND",404,()->f.scope().receipt(f.alice(),row));
        });
    }
    @Test void pendingMembershipCanRequestAccessButCannotProbeOtherCommandHashes(){
        fixture(f->{
            f.db().update("UPDATE memberships SET state='pending' WHERE tenant_id=? AND subject_id=?",id(TENANT),id(f.alice().id()));
            String key=UUID.randomUUID().toString();var body=Map.of("purpose","request synthetic access");
            var first=f.db().command(f.alice().id(),"membership.request",key,body,CommandScope.subject(),()->f.scope().authorize(f.alice(),CommandScope.subject()),()->Map.of("state","pending"));
            assertEquals(first,f.db().command(f.alice().id(),"membership.request",key,body,CommandScope.subject(),()->f.scope().authorize(f.alice(),CommandScope.subject()),()->{throw new AssertionError("replay must not repeat work");}));
            problem("MEMBER_NOT_ACTIVE",403,()->f.conversations().create(f.alice(),f.environment(),key));
        });
    }
}
