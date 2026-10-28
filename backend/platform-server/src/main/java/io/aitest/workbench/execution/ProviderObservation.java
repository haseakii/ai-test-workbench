package io.aitest.workbench.execution;

import io.aitest.contract.ContractChecks;
import io.aitest.contract.ProviderJson;
import io.aitest.contract.ProviderTypes.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Validated local import evidence, obtained through the Provider port rather than its journal. */
record ProviderObservation(String status,String receiptId,String specHash,Instant acceptedAt,
                           ArtifactDescriptor artifact,CsvSummary summary,String failureCode) {
 ProviderObservation {Objects.requireNonNull(status);Objects.requireNonNull(receiptId);Objects.requireNonNull(specHash);}
 static ProviderObservation receipt(StartReceipt receipt){return new ProviderObservation("accepted",receipt.receiptId(),receipt.specHash(),Instant.parse(receipt.acceptedAt()),null,null,null);}
 Map<String,Object> result(){
  if(artifact!=null&&summary!=null)return Map.of("artifactId",artifact.artifactId(),"sha256",artifact.sha256(),"bytes",artifact.bytes(),"summary",summary);
  return failureCode==null?Map.of():Map.of("code",failureCode);
 }
 record CsvSummary(long differenceCount,String detailTotal,String reportTotal,String totalDifference,String currency,
                   JsonNode plan,String algorithm,String scope,Usage usage){
  CsvSummary {
   ContractChecks.safeInteger(differenceCount);for(String value:List.of(detailTotal,reportTotal,totalDifference))if(!value.matches("-?(0|[1-9][0-9]{0,17})\\.[0-9]{2}"))throw new IllegalArgumentException("Invalid decimal evidence");
   if(!currency.matches("[A-Z]{3}")||!"report-minus-detail-v1".equals(algorithm)||!"synthetic_fixture_service".equals(scope)||plan==null||!plan.isObject())throw new IllegalArgumentException("Invalid fixed CSV evidence");
   Objects.requireNonNull(usage);
  }
 }
 record Usage(long platformTurns,long modelCalls,String costQuality){Usage{if(platformTurns!=1||modelCalls!=0||!"not_applicable".equals(costQuality))throw new IllegalArgumentException("Invalid synthetic usage");}}
}
