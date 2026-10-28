package io.aitest.workbench.execution;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.Store.Problem;
import org.springframework.stereotype.Component;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static io.aitest.workbench.infrastructure.Store.*;

/** Called by T_DISPATCH/receipt/terminal transactions. Never changes an unselected account. */
@Component
public class QuotaLedger {
    private final Store db;
    public QuotaLedger(Store db){this.db=db;}
    public static String modelAccount(String model){
        if(model==null||model.isBlank())throw new Problem(422,"MODEL_REQUIRED");
        String key="model:"+model;Store.key(key);return key;
    }
    public boolean reserve(UUID binding,UUID subject,String model,long environmentLimit){
        return db.transaction(()->{
            number(environmentLimit);String modelKey=modelAccount(model),period=db.quotaPeriod();
            var existing=db.rows("SELECT * FROM quota_reservations WHERE tenant_id=? AND binding_id=? FOR UPDATE",id(TENANT),id(binding));
            if(!existing.isEmpty()){
                var r=existing.getFirst();requireFrozen(r);
                if(!uuid(r.get("subject_id")).equals(subject.toString())||!Arrays.equals((byte[])r.get("model_account_key"),key(modelKey)))throw new Problem(409,"QUOTA_BINDING_CONFLICT");
                if(!"reserved".equals(r.get("state")))throw new Problem(409,"QUOTA_RESERVATION_CLOSED");
                return true; // Keep its original day and account pair, even after midnight.
            }
            var owner=db.one("SELECT c.subject_id FROM execution_bindings b JOIN conversations c ON c.tenant_id=b.tenant_id AND c.id=b.conversation_id WHERE b.tenant_id=? AND b.id=?",id(TENANT),id(binding));
            if(!uuid(owner.get("subject_id")).equals(subject.toString()))throw new Problem(409,"QUOTA_BINDING_CONFLICT");
            long limit=number(db.one("SELECT turn_limit FROM quota_policies WHERE tenant_id=? AND subject_id=?",id(TENANT),id(subject)).get("turn_limit"));
            var keys=List.of(modelKey,"total"); // Stable binary order: model:* before total.
            for(String account:keys)if(db.rows("SELECT account_key FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND account_key=? AND period_key=?",id(TENANT),id(subject),key(account),period).isEmpty())
                db.update("INSERT INTO quota_accounts(tenant_id,subject_id,account_key,period_key,turn_limit) VALUES(?,?,?,?,?)",id(TENANT),id(subject),key(account),period,limit);
            var slots=db.one("SELECT occupied,slot_limit FROM active_slots WHERE tenant_id=? AND subject_id=? FOR UPDATE",id(TENANT),id(subject));
            var accounts=db.rows("SELECT consumed,reserved,turn_limit FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND account_key IN (?,?) AND period_key=? ORDER BY account_key FOR UPDATE",id(TENANT),id(subject),key(modelKey),key("total"),period);
            if(accounts.size()!=2)throw new Problem(409,"QUOTA_SCOPE_UNKNOWN");
            boolean available=number(slots.get("occupied"))<number(slots.get("slot_limit"))&&accounts.stream().allMatch(a->number(a.get("consumed"))+number(a.get("reserved"))<Math.min(environmentLimit,number(a.get("turn_limit"))));
            if(!available)return false;
            for(String account:keys)changed(db.update("UPDATE quota_accounts SET reserved=reserved+1 WHERE tenant_id=? AND subject_id=? AND account_key=? AND period_key=?",id(TENANT),id(subject),key(account),period));
            changed(db.update("UPDATE active_slots SET occupied=occupied+1 WHERE tenant_id=? AND subject_id=?",id(TENANT),id(subject)));
            db.update("INSERT INTO quota_reservations(tenant_id,binding_id,subject_id,period_key,state,total_account_key,model_account_key) VALUES(?,?,?,?,'reserved',?,?)",id(TENANT),id(binding),id(subject),period,key("total"),key(modelKey));return true;
        });
    }
    public void consume(UUID binding){
        db.transaction(()->{
            var r=reservation(binding);
            if(Set.of("consumed","settled").contains(r.get("state")))return null;
            if(!r.get("state").equals("reserved"))throw new Problem(409,"QUOTA_RESERVATION_CLOSED");
            requireFrozen(r);
            for(byte[] account:accounts(r))changed(db.update("UPDATE quota_accounts SET reserved=reserved-1,consumed=consumed+1 WHERE tenant_id=? AND subject_id=? AND account_key=? AND period_key=? AND reserved>0 AND consumed<9007199254740991",id(TENANT),r.get("subject_id"),account,r.get("period_key")));
            changed(db.update("UPDATE quota_reservations SET state='consumed' WHERE tenant_id=? AND binding_id=? AND state='reserved'",id(TENANT),id(binding)));return null;
        });
    }
    /** Only call after an authoritative terminal/closed fact; not on timeout or not_found. */
    public void close(UUID binding){
        db.transaction(()->{
            var rows=db.rows("SELECT * FROM quota_reservations WHERE tenant_id=? AND binding_id=? FOR UPDATE",id(TENANT),id(binding));
            if(rows.isEmpty())return null;var r=rows.getFirst();String state=r.get("state").toString();
            if(Set.of("released","settled").contains(state))return null;
            if(state.equals("reserved")){
                requireFrozen(r);
                for(byte[] account:accounts(r))changed(db.update("UPDATE quota_accounts SET reserved=reserved-1 WHERE tenant_id=? AND subject_id=? AND account_key=? AND period_key=? AND reserved>0",id(TENANT),r.get("subject_id"),account,r.get("period_key")));
            }else if(!state.equals("consumed"))throw new Problem(409,"QUOTA_SCOPE_UNKNOWN");
            changed(db.update("UPDATE quota_reservations SET state=? WHERE tenant_id=? AND binding_id=?",state.equals("reserved")?"released":"settled",id(TENANT),id(binding)));
            changed(db.update("UPDATE active_slots SET occupied=occupied-1 WHERE tenant_id=? AND subject_id=? AND occupied>0",id(TENANT),r.get("subject_id")));return null;
        });
    }
    private Map<String,Object> reservation(UUID binding){return db.one("SELECT * FROM quota_reservations WHERE tenant_id=? AND binding_id=? FOR UPDATE",id(TENANT),id(binding));}
    private static void requireFrozen(Map<String,Object> r){
        if(r.get("total_account_key")==null||r.get("model_account_key")==null||!Arrays.equals((byte[])r.get("total_account_key"),key("total"))||!new String((byte[])r.get("model_account_key"),StandardCharsets.UTF_8).startsWith("model:"))throw new Problem(409,"QUOTA_SCOPE_UNKNOWN");
    }
    private static List<byte[]> accounts(Map<String,Object> r){return List.of((byte[])r.get("model_account_key"),(byte[])r.get("total_account_key"));}
    private static void changed(int count){if(count!=1)throw new Problem(409,"QUOTA_BALANCE_CONFLICT");}
}
