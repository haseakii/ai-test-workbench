package io.aitest.workbench.bootstrap;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeFilesTest {
    @TempDir Path root;
    @Test void createsOnlyNewOwnerPrivateDirectoriesAndFiles()throws Exception{
        var directory=root.resolve("中文 空格/runs/one");RuntimeFiles.createPrivateDirectory(directory);RuntimeFiles.requirePrivateDirectory(directory);
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(directory));
        var file=directory.resolve("metadata.json");RuntimeFiles.writeNewPrivateFile(file,"{}".getBytes());RuntimeFiles.requirePrivateFile(file);
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));assertEquals("{}",Files.readString(file));
    }
    @Test void neverChangesPermissionsOfAnExistingUnprivateDirectory()throws Exception{
        var directory=root.resolve("existing");Files.createDirectory(directory);Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(java.io.IOException.class,()->RuntimeFiles.createPrivateDirectory(directory));
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"),Files.getPosixFilePermissions(directory));
    }
    @Test void privateFileMayPreserveLegacyOwnerReadinessInNonWritable755Directory()throws Exception{
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-xr-x"));
        var file=root.resolve("ready.json");RuntimeFiles.writeNewPrivateFile(file,"{}".getBytes());
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"),Files.getPosixFilePermissions(root));
    }
    @Test void unsafeParentFailsBeforeFileCreation()throws Exception{
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxrwxrwx"));var file=root.resolve("private.json");
        try{assertThrows(java.io.IOException.class,()->RuntimeFiles.writeNewPrivateFile(file,"credential-not-for-output".getBytes()));assertFalse(Files.exists(file));}
        finally{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));}
    }
    @Test void existingFileIsNotOverwritten()throws Exception{
        var file=root.resolve("existing");Files.writeString(file,"original");Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        assertThrows(java.io.IOException.class,()->RuntimeFiles.writeNewPrivateFile(file,"replacement".getBytes()));assertEquals("original",Files.readString(file));
    }
    @Test void rejectsSymlinkFileAndAncestor()throws Exception{
        var actual=root.resolve("actual");RuntimeFiles.createPrivateDirectory(actual);var link=root.resolve("link");Files.createSymbolicLink(link,actual);
        assertThrows(java.io.IOException.class,()->RuntimeFiles.createPrivateDirectory(link.resolve("child")));assertFalse(Files.exists(actual.resolve("child")));
        var source=actual.resolve("source");RuntimeFiles.writeNewPrivateFile(source,"original".getBytes());var linked=root.resolve("linked");Files.createSymbolicLink(linked,source);
        assertThrows(java.io.IOException.class,()->RuntimeFiles.requirePrivateFile(linked));assertThrows(java.io.IOException.class,()->RuntimeFiles.writeNewPrivateFile(linked,"replacement".getBytes()));assertEquals("original",Files.readString(source));
    }
    @Test void aclPolicyModelAllowsOnlyCurrentOwnerAndRejectsBroadOrMissingAllow(){
        UserPrincipal owner=()->"OWNER",other=()->"OTHER";
        var own=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(AclEntryPermission.READ_DATA,AclEntryPermission.WRITE_DATA).build();
        var foreign=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other).setPermissions(AclEntryPermission.READ_DATA).build();
        var denied=AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(other).setPermissions(AclEntryPermission.READ_DATA).build();
        assertTrue(RuntimeFiles.privateAcl(owner,List.of(own)));assertTrue(RuntimeFiles.privateAcl(owner,List.of(own,denied)));
        assertFalse(RuntimeFiles.privateAcl(owner,List.of(own,foreign)));assertFalse(RuntimeFiles.privateAcl(owner,List.of(denied)));assertFalse(RuntimeFiles.privateAcl(owner,List.of()));
    }
    @Test void rejectsRelativeAndUnnormalizedPathsWithFixedError()throws Exception{
        assertThrows(java.io.IOException.class,()->RuntimeFiles.createPrivateDirectory(Path.of("relative-runtime")));
        var failure=assertThrows(java.io.IOException.class,()->RuntimeFiles.writeNewPrivateFile(root.resolve("do-not-output-secret/../file"),new byte[0]));
        assertFalse(failure.getMessage().contains("do-not-output-secret"));
    }
}
