package io.aitest.workbench.material;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.infrastructure.CommandScope;
import io.aitest.workbench.infrastructure.CommandScope.Kind;
import io.aitest.workbench.infrastructure.Store.Problem;
import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.identity.Scope.Actor;
import io.aitest.contract.CanonicalJson;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.apache.commons.csv.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;

@Service
public class Materials {
    private static final int MAX_BYTES=20*1024*1024,MAX_ROWS=10000;
    private final Store db;private final Scope scope;private final Path root;
    public record Entry(String order,BigDecimal amount,String currency,String status,Instant occurredAt,long row){}
    public record Calculation(byte[] csv,Map<String,Object> summary){}
    public Materials(Store db,Scope scope,@Value("${workbench.blob-root}")String directory)throws IOException {
        this.db=db;this.scope=scope;Path p=Path.of(directory).toAbsolutePath().normalize();Files.createDirectories(p);
        if(Files.isSymbolicLink(p))throw new IOException("Blob root must not be a symlink");root=p.toRealPath();
    }
    public byte[] read(UUID id){try{Path p=path(id);if(Files.isSymbolicLink(p))throw new Problem(409,"INTEGRITY_MISMATCH");return Files.readAllBytes(p);}catch(IOException e){throw new Problem(409,"ARTIFACT_UNAVAILABLE");}}
    public void write(UUID id,byte[] bytes) {
        try {Path temp=Files.createTempFile(root,"quarantine-",".tmp");Files.write(temp,bytes);Files.move(temp,path(id),StandardCopyOption.ATOMIC_MOVE);}
        catch(IOException e){throw new Problem(503,"BLOB_UNAVAILABLE");}
    }
    private Path path(UUID id){return root.resolve(id+".blob");}
    /** Opaque task-local executor scope; never place the private blob path on the port. */
    public String scope(){return CanonicalJson.hashBytes(root.toString().getBytes(StandardCharsets.UTF_8));}
    public Map<String,Object> upload(Actor a,UUID conversation,MultipartFile file,String key) {
        scope.ownConversation(a,conversation);
        byte[] bytes;
        try{bytes=file.getInputStream().readNBytes(MAX_BYTES+1);}catch(IOException e){throw new Problem(422,"UPLOAD_FAILED");}
        if(bytes.length>MAX_BYTES)throw new Problem(413,"FILE_TOO_LARGE");
        parse(bytes); // A bounded, non-executing UTF-8 CSV format scan and parser; not an antivirus claim.
        String hash=CanonicalJson.hashBytes(bytes);
        String name=Objects.toString(file.getOriginalFilename(),"input.csv").replaceAll("[\\\\/\\p{Cntrl}]","_");
        if(name.length()>180)name=name.substring(name.length()-180);
        UUID attachment=UUID.randomUUID();write(attachment,bytes);final String safeName=name;
        try {
            return db.command(a.id(),"attachment.import",key,Map.of("conversationId",conversation.toString(),"sha256",hash,"name",safeName),CommandScope.of(Kind.CONVERSATION,conversation),()->scope.authorize(a,CommandScope.of(Kind.CONVERSATION,conversation)),()->{
                var current=scope.ownConversation(a,conversation);long input=number(current.get("input_revision"))+1;
                db.update("INSERT INTO attachments(tenant_id,id,conversation_id,file_name,state,sha256,bytes) VALUES(?,?,?,?,?,?,?)",id(TENANT),id(attachment),id(conversation),safeName,"ready",hex(hash),bytes.length);
                db.update("UPDATE conversations SET input_revision=?,revision=revision+1,state='awaiting_user' WHERE tenant_id=? AND id=?",input,id(TENANT),id(conversation));
                db.update("UPDATE artifacts SET state='superseded' WHERE tenant_id=? AND conversation_id=? AND state='ready'",id(TENANT),id(conversation));
                io.aitest.workbench.execution.ContextManifests.invalidate(db,conversation,"INPUT_CHANGED");
                db.update("UPDATE approvals p JOIN execution_bindings b ON b.tenant_id=p.tenant_id AND b.id=p.binding_id SET p.state='superseded',p.revision=p.revision+1 WHERE p.tenant_id=? AND p.conversation_id=? AND p.state IN ('pending','approved') AND b.closed_at IS NULL",id(TENANT),id(conversation));
                db.event(conversation,"attachment.ready",Map.of("attachmentId",attachment.toString(),"sha256",hash,"inputRevision",input));
                return Map.of("id",attachment.toString(),"state","ready","sha256",hash,"bytes",bytes.length,"name",safeName,"inputRevision",input);
            });
        }finally{
            if(db.rows("SELECT id FROM attachments WHERE tenant_id=? AND id=?",id(TENANT),id(attachment)).isEmpty())try{Files.deleteIfExists(path(attachment));}catch(IOException ignored){}
        }
    }
    public List<Entry> parse(byte[] bytes) {
        String input;
        try{input=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw new Problem(422,"INVALID_UTF8");}
        if(input.startsWith("\uFEFF"))input=input.substring(1);
        if(input.indexOf('\0')>=0)throw new Problem(422,"INVALID_CSV");
        var entries=new ArrayList<Entry>();var seen=new HashSet<String>();
        try(var parser=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get().parse(new StringReader(input))){
            if(!parser.getHeaderNames().equals(List.of("order_id","amount","currency","status","occurred_at")))throw new Problem(422,"CSV_HEADER_REQUIRED");
            for(var row:parser) {
                if(entries.size()>=MAX_ROWS||!row.isConsistent())throw new Problem(422,"CSV_LIMIT_OR_COLUMNS");
                String order=row.get("order_id"),amount=row.get("amount"),currency=row.get("currency"),status=row.get("status");
                if(!order.matches("[A-Za-z0-9_-]{1,64}")||!amount.matches("-?[0-9]{1,12}\\.[0-9]{2}")||!currency.matches("[A-Z]{3}")||!Set.of("completed","pending","cancelled").contains(status))throw new Problem(422,"CSV_VALUE_INVALID");
                if(!seen.add(order))throw new Problem(422,"DUPLICATE_ORDER");
                Instant at=Instant.parse(row.get("occurred_at"));
                entries.add(new Entry(order,new BigDecimal(amount),currency,status,at,row.getRecordNumber()+1));
            }
        } catch(Problem p){throw p;}catch(Exception e){throw new Problem(422,"INVALID_CSV");}
        if(entries.isEmpty())throw new Problem(422,"CSV_EMPTY");return entries;
    }
    public byte[] source(UUID conversation,UUID attachment) {
        var r=db.one("SELECT * FROM attachments WHERE tenant_id=? AND conversation_id=? AND id=? AND state='ready' AND source_active=TRUE",id(TENANT),id(conversation),id(attachment));
        byte[] bytes=read(attachment);if(!CanonicalJson.hashBytes(bytes).equals(hex(r.get("sha256"))))throw new Problem(409,"INTEGRITY_MISMATCH");
        return bytes;
    }
    public void assertSources(UUID conversation,Map<String,Object> plan) {
        for(String key:List.of("detailAttachmentId","reportAttachmentId")){
            var r=db.one("SELECT sha256,source_active,state FROM attachments WHERE tenant_id=? AND conversation_id=? AND id=?",id(TENANT),id(conversation),id(plan.get(key).toString()));
            if(!Boolean.TRUE.equals(r.get("source_active"))||!r.get("state").equals("ready")||!hex(r.get("sha256")).equals(plan.get(key+"Hash")))throw new Problem(409,"SOURCE_REVOKED_OR_CHANGED");
        }
    }
    public Calculation calculate(UUID conversation,Map<String,Object> plan) {
        assertSources(conversation,plan);
        Instant from=Instant.parse(plan.get("from").toString()),to=Instant.parse(plan.get("to").toString());
        String currency=plan.get("currency").toString(),status=plan.get("status").toString();
        var details=select(parse(source(conversation,UUID.fromString(plan.get("detailAttachmentId").toString()))),from,to,currency,status);
        var report=select(parse(source(conversation,UUID.fromString(plan.get("reportAttachmentId").toString()))),from,to,currency,status);
        var keys=new TreeSet<String>(details.keySet());keys.addAll(report.keySet());int differences=0;
        BigDecimal detailTotal=BigDecimal.ZERO.setScale(2),reportTotal=BigDecimal.ZERO.setScale(2);
        var output=new StringWriter();
        try(var printer=new CSVPrinter(output,CSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).get())){
            printer.printRecord("order_id","detail_amount","report_amount","difference","currency","detail_row","report_row");
            for(String order:keys) {
                var d=details.get(order);var r=report.get(order);
                BigDecimal dv=d==null?BigDecimal.ZERO.setScale(2):d.amount(),rv=r==null?BigDecimal.ZERO.setScale(2):r.amount();
                detailTotal=detailTotal.add(dv);reportTotal=reportTotal.add(rv);
                if(d==null||r==null||dv.compareTo(rv)!=0) {differences++;printer.printRecord(order,d==null?"missing":dv.toPlainString(),r==null?"missing":rv.toPlainString(),rv.subtract(dv).toPlainString(),currency,d==null?"":d.row(),r==null?"":r.row());}
            }
        }catch(IOException e){throw new Problem(503,"CALCULATION_FAILED");}
        var summary=new LinkedHashMap<String,Object>();summary.put("differenceCount",differences);summary.put("detailTotal",detailTotal.toPlainString());summary.put("reportTotal",reportTotal.toPlainString());summary.put("totalDifference",reportTotal.subtract(detailTotal).toPlainString());summary.put("currency",currency);summary.put("plan",plan);summary.put("algorithm","report-minus-detail-v1");summary.put("scope","synthetic_fixture_service");summary.put("usage",Map.of("platformTurns",1,"modelCalls",0,"costQuality","not_applicable"));
        return new Calculation(output.toString().getBytes(StandardCharsets.UTF_8),summary);
    }
    private Map<String,Entry> select(List<Entry> entries,Instant from,Instant to,String currency,String status){
        var map=new LinkedHashMap<String,Entry>();
        for(var e:entries)if(!e.occurredAt().isBefore(from)&&e.occurredAt().isBefore(to)&&e.status().equals(status)){
            if(!e.currency().equals(currency))throw new Problem(422,"CURRENCY_SCOPE_MISMATCH");map.put(e.order(),e);
        }
        return map;
    }
    public Map<String,Object> revoke(Actor a,UUID attachment,String key) {
        return db.command(a.id(),"source.revoke",key,Map.of("id",attachment.toString()),CommandScope.of(Kind.ATTACHMENT,attachment),()->scope.authorize(a,CommandScope.of(Kind.ATTACHMENT,attachment)),()->{
            var r=db.one("SELECT conversation_id FROM attachments WHERE tenant_id=? AND id=?",id(TENANT),id(attachment));UUID c=UUID.fromString(uuid(r.get("conversation_id")));scope.ownConversation(a,c);
            db.update("UPDATE attachments SET source_active=FALSE WHERE tenant_id=? AND id=?",id(TENANT),id(attachment));
            io.aitest.workbench.execution.ContextManifests.invalidate(db,c,"SOURCE_REVOKED");
            db.update("UPDATE approvals p JOIN execution_bindings b ON b.tenant_id=p.tenant_id AND b.id=p.binding_id SET p.state='superseded',p.revision=p.revision+1 WHERE p.tenant_id=? AND p.conversation_id=? AND p.state IN ('pending','approved') AND b.closed_at IS NULL",id(TENANT),id(c));
            db.update("UPDATE execution_bindings SET stopped=TRUE WHERE tenant_id=? AND conversation_id=? AND closed_at IS NULL",id(TENANT),id(c));
            db.event(c,"source.revoked",Map.of("attachmentId",attachment.toString(),"derivedAccess","blocked","providerSession","unusable"));db.audit(a.id(),"source.revoke",attachment,Map.of());
            return Map.of("state","revoked");
        });
    }
    public byte[] download(Actor a,UUID artifact) {
        var r=db.transaction(()->{
            var row=scope.artifact(a,artifact);UUID c=UUID.fromString(uuid(row.get("conversation_id")));
            assertSources(c,db.object(db.object(row.get("summary_json")).get("plan")));db.audit(a.id(),"artifact.download",artifact,Map.of());return row;
        });
        byte[] bytes=read(artifact);if(!CanonicalJson.hashBytes(bytes).equals(hex(r.get("sha256"))))throw new Problem(409,"INTEGRITY_MISMATCH");return bytes;
    }
    public Map<String,Object> verify(Actor a,UUID artifact,long resultRevision,boolean resolved,String opinion,String key){
        if(opinion==null||opinion.length()>1000)throw new Problem(422,"INVALID_OPINION");
        return db.command(a.id(),"result.verify",key,Map.of("artifactId",artifact.toString(),"resultRevision",resultRevision,"resolved",resolved,"opinion",opinion),CommandScope.of(Kind.ARTIFACT,artifact),()->scope.authorize(a,CommandScope.of(Kind.ARTIFACT,artifact)),()->{
            var r=scope.artifact(a,artifact);UUID c=UUID.fromString(uuid(r.get("conversation_id")));var conv=scope.ownConversation(a,c);
            if(number(r.get("result_revision"))!=resultRevision||number(r.get("input_revision"))!=number(conv.get("input_revision")))throw new Problem(409,"RESULT_STALE");
            assertSources(c,db.object(db.object(r.get("summary_json")).get("plan")));
            var old=db.rows("SELECT resolved,opinion FROM verifications WHERE tenant_id=? AND artifact_id=? AND result_revision=?",id(TENANT),id(artifact),resultRevision);
            if(!old.isEmpty()){
                if(!old.getFirst().get("resolved").equals(resolved)||!old.getFirst().get("opinion").equals(opinion))throw new Problem(409,"VERIFICATION_CONFLICT");
                return Map.of("state",resolved?"resolved":"open");
            }
            db.update("INSERT INTO verifications VALUES(?,?,?,?,?,?)",id(TENANT),id(artifact),id(a.id()),resultRevision,resolved,opinion);
            db.update("UPDATE conversations SET state=?,revision=revision+1 WHERE tenant_id=? AND id=?",resolved?"resolved":"open",id(TENANT),id(c));
            db.event(c,"result.verified",Map.of("resultRevision",resultRevision,"resolved",resolved));db.audit(a.id(),"result.verify",artifact,Map.of("resolved",resolved));
            return Map.of("state",resolved?"resolved":"open");
        });
    }
}
