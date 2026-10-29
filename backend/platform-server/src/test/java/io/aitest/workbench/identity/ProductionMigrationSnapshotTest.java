package io.aitest.workbench.identity;

import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ProductionMigrationSnapshotTest {
    private static final Path CANDIDATE = Path.of("../../docs/development/production-schema-draft/candidate");

    @Test void fixedSnapshotMatchesFlyway124ForEveryFile() throws Exception {
        assertTrue(ProductionMigrationSnapshot.verify(CANDIDATE));
        try (var stream=Files.list(CANDIDATE)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                byte[] data=Files.readAllBytes(path);
                assertEquals(ChecksumCalculator.calculate(resource(new String(data, StandardCharsets.UTF_8))),
                        ProductionMigrationSnapshot.flywayChecksum(data), path.toString());
            }
        }
    }

    @Test void missingTamperedExtraAndUntrustedFilesFailClosed(@TempDir Path temp) throws Exception {
        try (var stream=Files.list(CANDIDATE)) {
            for (Path source : stream.filter(p -> p.getFileName().toString().endsWith(".sql")).toList())
                Files.copy(source, temp.resolve(source.getFileName()));
        }
        assertTrue(ProductionMigrationSnapshot.verify(temp));
        Path extra=temp.resolve("V12__unexpected.sql"); Files.writeString(extra,"SELECT 1;");
        assertFalse(ProductionMigrationSnapshot.verify(temp)); Files.delete(extra);
        Path target=temp.resolve("V11__production_bootstrap_guard.sql");
        byte[] data=Files.readAllBytes(target); Files.writeString(target,new String(data, StandardCharsets.UTF_8)+"-- modified\n");
        assertFalse(ProductionMigrationSnapshot.verify(temp)); Files.write(target,data);
        Files.delete(target); assertFalse(ProductionMigrationSnapshot.verify(temp));
    }

    @Test void flywayChecksumHandlesLineEndingsBomAndInvalidUnicode() throws Exception {
        for (String text : new String[]{"\ufeff行一\r\nline2\r\n", "行一\nline2\n", "", "\ufeff"}) {
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
            assertEquals(ChecksumCalculator.calculate(resource(text)), ProductionMigrationSnapshot.flywayChecksum(bytes));
        }
        assertThrows(Exception.class, () -> ProductionMigrationSnapshot.flywayChecksum(new byte[]{(byte)0xc3, 0x28}));
    }

    /** Flyway internal API is used in this test only, to verify the fixed 12.4.0 algorithm. */
    private static LoadableResource resource(String text) {
        return new LoadableResource() {
            @Override public Reader read() { return new StringReader(text); }
            @Override public String getFilename() { return "test.sql"; }
            @Override public String getRelativePath() { return "test.sql"; }
            @Override public String getAbsolutePath() { return "test.sql"; }
            @Override public String getAbsolutePathOnDisk() { return "test.sql"; }
        };
    }
}
