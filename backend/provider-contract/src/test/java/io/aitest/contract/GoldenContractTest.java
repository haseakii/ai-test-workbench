package io.aitest.contract;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;

class GoldenContractTest {
    @Test void matchesSharedGoldenVectors()throws Exception {
        var root=JsonMapper.builder().build().readTree(Files.readString(Path.of("../../contracts/golden.json")));
        int count=0;
        for(var row:root.get("cases")){
            assertEquals(row.get("canonical").asString(),CanonicalJson.canonicalize(row.get("input").asString()));
            assertEquals(row.get("sha256").asString(),CanonicalJson.hash(row.get("input").asString()));count++;
        }
        assertEquals(3,count);
    }
}
