package io.aitest.workbench.execution;

import io.aitest.contract.ExecutionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class PreparationContextPortTest {
 @TempDir Path blobs;
 @Test void preparedPlanFreezesARecomputableContextManifest()throws Exception {
  var h=new DispatchAtomicityTest();h.blobs=blobs;var f=h.fixture();var p=h.prepared(f,false);
  try{var plan=(Map<?,?>)p.get("plan");assertNotNull(plan.get("contextManifestRef"),"a plan must bind the immutable input/config/authorization context");assertNotNull(plan.get("contextManifestHash"));}
  finally{h.close(f,p);}
 }
 @Test void runtimeWorkerConsumesTheUnifiedProviderPort()throws Exception {
  assertEquals(ExecutionProvider.class,ExecutionWorker.class.getDeclaredField("provider").getType(),"runtime must consume the compiled port rather than fixture Maps");
  assertTrue(ExecutionProvider.class.isAssignableFrom(FixtureProvider.class),"the persistent synthetic implementation must implement the actual port");
 }
}
