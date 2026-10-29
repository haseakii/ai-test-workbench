package io.aitest.workbench.identity;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;

/** Read-only check of a fixed review snapshot, not database eligibility or bootstrap authorization.
 * No runtime service calls this class. SQL files remain unapproved and are not packaged. */
final class ProductionMigrationSnapshot {
    private ProductionMigrationSnapshot() {}
    private record Entry(String name, String sha256, int flywayCrc) {}
    private static final List<Entry> EXPECTED = List.of(
        new Entry("V1__platform.sql","a5a5ae686f943be76b908a0c17381fea662695e78b544c5f74934bc28731e7c3",-1942270693),
        new Entry("V2__title_source_and_quota_policy.sql","3d6e1348322220eba5859c738fb93daa943f17308426725c8e5c6f8da90af2ed",-1156082688),
        new Entry("V3__scoped_content_review.sql","f7a19e1384e71a9782229a8c2ad0b6a70987e7b00e3ff85ee068d49d7ce8ce2c",1858403170),
        new Entry("V4__receipt_scope_and_production_marker.sql","727741ee30230f61359af08bb2a9c481490ab3c13403fdf99017d65f7350c6a9",-296733036),
        new Entry("V5__frozen_quota_accounts.sql","a479f9d0aa86fb1628e36db66fadcbb28ab4107ed1a87b9d75624b367549ef7b",1775228837),
        new Entry("V6__context_manifests.sql","1aadb47f036d6120183dd928c3c48b0f100ecc04173c400650a413d9fc101294",731149075),
        new Entry("V7__typed_provider_journal.sql","ff4dad56ea304313654a87cc4aba893944a1ab2430041ea108d18bc8135b9ecb",2143442536),
        new Entry("V8__resource_operation_consumption.sql","bf30db9f26d6d312ab65da697c672e996d10f7891656b4a6f02c877c12fc05e7",633549247),
        new Entry("V9__local_auth_sessions.sql","c0558a28b177612146b96a521d6bbb55dd1f8d9f3649acb7890a833f8b5bbc1c",-9023482),
        new Entry("V10__local_accounts_and_login_limits.sql","52aa4704e530e2adf54d5087572a6d5b3a1817ebdf2689f95c55949e3de8e72d",-2049745790),
        new Entry("V11__production_bootstrap_guard.sql","1d1ef8c21bc905f587f54f622c7fd3e10b5937f46af1fa151b73c656e52237e6",311402000));

    static boolean verify(Path directory) {
        if (directory == null || Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            try (var files=Files.list(directory)) {
                var sql=files.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".sql"))
                        .map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
                if (!sql.equals(EXPECTED.stream().map(Entry::name).collect(java.util.stream.Collectors.toSet()))) return false;
            }
            for (Entry entry : EXPECTED) {
                Path path = directory.resolve(entry.name());
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1_048_576) return false;
                byte[] data;
                try (var in=Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    var buffer=ByteBuffer.allocate(1_048_577); while (buffer.hasRemaining() && in.read(buffer) != -1) { }
                    if (buffer.position() > 1_048_576) return false;
                    data=java.util.Arrays.copyOf(buffer.array(), buffer.position());
                }
                if (!MessageDigest.isEqual(HexFormat.of().parseHex(entry.sha256()), MessageDigest.getInstance("SHA-256").digest(data))
                        || flywayChecksum(data) != entry.flywayCrc()) return false;
            }
            return true;
        } catch (Exception failure) { return false; }
    }

    /** Flyway 12.4.0 checksum: UTF-8 text, first-line BOM removed, CRC over lines without terminators. */
    static int flywayChecksum(byte[] bytes) throws Exception {
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        CRC32 crc=new CRC32();
        try (var reader=new BufferedReader(new StringReader(text))) {
            String line; boolean first=true;
            while ((line=reader.readLine()) != null) {
                if (first && line.startsWith("\ufeff")) line=line.substring(1);
                first=false; crc.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        return (int)crc.getValue();
    }
}
