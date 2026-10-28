package io.aitest.contract;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import static io.aitest.contract.ProviderTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderWireTest {
    record Method(String name,String parameter,Class<?> input,Class<?> output){}
    static final List<Method> METHODS=List.of(
        new Method("inspectCapabilities","connection",ConnectionRef.class,CapabilitySnapshot.class),
        new Method("validateBinding","spec",ExecutionSpec.class,ValidationReport.class),
        new Method("start","request",StartRequest.class,StartReceipt.class),
        new Method("lookupSubmission","request",SubmissionRef.class,SubmissionLookup.class),
        new Method("closeSubmission","request",CloseSubmissionRequest.class,SubmissionClosure.class),
        new Method("getSnapshot","binding",BindingRef.class,ExecutionSnapshot.class),
        new Method("readEvents","request",EventReadRequest.class,EventPage.class),
        new Method("submitInput","request",InputRequest.class,InputReceipt.class),
        new Method("cancel","request",CancelRequest.class,CancelReceipt.class),
        new Method("preparePermissionResume","request",ResumePreparation.class,ResumeReceipt.class),
        new Method("respondPermission","request",PermissionResponse.class,PermissionReceipt.class),
        new Method("getArtifact","request",ArtifactRequest.class,ArtifactDescriptor.class));
    String vectors()throws Exception{return Files.readString(Path.of("../../contracts/provider-vectors.json"));}
    @Test void everyPortMethodCompilesAndAllSharedWireVectorsRoundTrip()throws Exception {
        var root=JsonMapper.builder().build().readTree(vectors());assertEquals(12,ExecutionProvider.class.getDeclaredMethods().length);
        for(var method:METHODS){
            assertEquals(CompletionStage.class,ExecutionProvider.class.getDeclaredMethod(method.name(),CallContext.class,method.input()).getReturnType());
            var vector=root.get("methods").get(method.name());
            for(var part:List.of(Map.entry("context",CallContext.class),Map.entry(method.parameter(),method.input()))){
                var input=vector.get("request").get(part.getKey());var value=ProviderJson.read(input.toString(),part.getValue());
                assertEquals(CanonicalJson.canonicalize(input.toString()),CanonicalJson.canonicalize(ProviderJson.write(value)),method.name()+" "+part.getKey());
            }
            var response=vector.get("response");var value=ProviderJson.readResult(response.toString(),method.output());
            assertEquals(CanonicalJson.canonicalize(response.toString()),CanonicalJson.canonicalize(ProviderJson.write(value)),method.name()+" response");
        }
    }
    @Test void fullStartContentSpecAndCommandHashesMatchSharedVectors()throws Exception {
        var json=JsonMapper.builder().build();var root=json.readTree(vectors());var request=root.get("methods").get("start").get("request").get("request");
        var spec=json.readValue(request.get("spec").toString(),Map.class);
        assertEquals(root.get("specHash").asString(),CanonicalJson.hash(json.writeValueAsString(spec)));
        var content=new LinkedHashMap<>(spec);content.remove("admission");
        assertEquals(root.get("executionContentHash").asString(),CanonicalJson.hash(json.writeValueAsString(content)));
        assertEquals(root.get("commandRequestHash").asString(),CanonicalJson.hash(json.writeValueAsString(Map.of("method","start","body",Map.of("spec",spec,"submission",json.readValue(request.get("submission").toString(),Map.class))))));
    }
    @Test void rejectsUnsafeWireNumbersMissingFieldsUnknownFieldsAndPrecisionLoss()throws Exception {
        var root=JsonMapper.builder().build().readTree(vectors());String connection=root.get("methods").get("inspectCapabilities").get("request").get("connection").toString();
        assertThrows(RuntimeException.class,()->ProviderJson.read(connection.replace("\"connectionRevision\":1","\"connectionRevision\":1.5"),ConnectionRef.class));
        assertThrows(RuntimeException.class,()->new ConnectionRef("p","c",9_007_199_254_740_992L));
        assertThrows(RuntimeException.class,()->ProviderJson.read("{\"providerId\":\"p\",\"connectionId\":\"c\"}",ConnectionRef.class));
        assertThrows(RuntimeException.class,()->ProviderJson.read(connection.replaceFirst("\\{","{\"unexpected\":true,"),ConnectionRef.class));
        assertThrows(RuntimeException.class,()->ProviderJson.read(connection.replaceFirst("\\{","{\"providerId\":\"duplicate\","),ConnectionRef.class));
        assertThrows(RuntimeException.class,()->new CallContext("initial-draft","c","00000000-0000-4000-8000-000000000001",new CallContextSubjectContext("00000000-0000-4000-8000-000000000012","a"),"2026-10-26T00:00:00Z"));
        assertThrows(RuntimeException.class,()->new BindingRef("bad-internal-id","00000000-0000-4000-8000-000000000001","p"));
    }
    @Test void policyHumanAndUnknownOutcomeCannotBeCoercedIntoAnotherVariant()throws Exception {
        String human="{\"kind\":\"human\",\"subjectId\":\"reviewer\",\"qualificationRef\":\"q\"}";
        assertInstanceOf(DecisionAuthorityHuman.class,ProviderJson.read(human,ProviderTypes.DecisionAuthority.class));
        assertThrows(RuntimeException.class,()->ProviderJson.read(human.replace("human","policy"),ProviderTypes.DecisionAuthority.class));
        assertEquals("false",ProviderJson.write(KnownBoolean.FALSE));assertEquals("\"unknown\"",ProviderJson.write(KnownBoolean.UNKNOWN));
        assertEquals(KnownBoolean.UNKNOWN,ProviderJson.read("\"unknown\"",KnownBoolean.class));assertEquals(KnownBoolean.FALSE,ProviderJson.read("false",KnownBoolean.class));
        assertThrows(RuntimeException.class,()->ProviderJson.read("\"false\"",KnownBoolean.class));
        var error=JsonMapper.builder().build().readTree(vectors()).get("unknownError");
        assertEquals(CanonicalJson.canonicalize(error.toString()),CanonicalJson.canonicalize(ProviderJson.write(ProviderJson.readResult(error.toString(),StartReceipt.class))));
        assertThrows(RuntimeException.class,()->ProviderJson.read("{\"status\":\"not_found\",\"observedAt\":\"2026-10-26T00:00:00.000001Z\",\"authoritativeNonAcceptance\":true}",SubmissionLookup.class));
    }
}
