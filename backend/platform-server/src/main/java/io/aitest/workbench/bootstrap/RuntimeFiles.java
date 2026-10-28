package io.aitest.workbench.bootstrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Owner-private launch metadata only. Never repairs permissions on an existing path. */
public final class RuntimeFiles {
    private RuntimeFiles(){}
    private static final LinkOption[] NO_LINKS={LinkOption.NOFOLLOW_LINKS};

    public static void createPrivateDirectory(Path path)throws IOException{
        location(path);
        if(Files.exists(path,NO_LINKS)){requirePrivateDirectory(path);return;}
        Path parent=path.getParent();
        if(parent==null)throw invalid();
        if(!Files.exists(parent,NO_LINKS))createPrivateDirectory(parent);
        if(!Files.isDirectory(parent,NO_LINKS))throw invalid();
        Files.createDirectory(path,attributes(parent,true));
        requirePrivateDirectory(path);
    }

    public static void requirePrivateDirectory(Path path)throws IOException{
        location(path);
        if(!Files.isDirectory(path,NO_LINKS))throw invalid();
        ownerOnly(path,true);
    }

    public static void requirePrivateFile(Path path)throws IOException{
        location(path);
        if(!Files.isRegularFile(path,NO_LINKS))throw invalid();
        ownerOnly(path,false);
    }

    /** Legacy POSIX readiness may live in an owner-owned 755 directory; never writable by peers. */
    public static void requireOwnerDirectory(Path path)throws IOException{
        location(path);
        if(!Files.isDirectory(path,NO_LINKS)||!Files.getOwner(path,NO_LINKS).equals(currentUser(path)))throw invalid();
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,NO_LINKS);
        if(posix!=null){
            var permissions=posix.readAttributes().permissions();
            if(!permissions.containsAll(PosixFilePermissions.fromString("rwx------"))
                ||permissions.contains(PosixFilePermission.GROUP_WRITE)||permissions.contains(PosixFilePermission.OTHERS_WRITE))throw invalid();
        }else ownerOnly(path,true);
    }

    public static void writeNewPrivateFile(Path path,byte[] bytes)throws IOException{
        location(path);requireOwnerDirectory(path.getParent());
        Set<OpenOption> options=Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try(var channel=FileChannel.open(path,options,attributes(path.getParent(),false))){
            requirePrivateFile(path);
            ByteBuffer buffer=ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining())channel.write(buffer);
            channel.force(true);
        }
    }

    static void location(Path path)throws IOException{
        if(path==null||!path.isAbsolute()||!path.equals(path.normalize())||path.toString().startsWith("\\\\"))throw invalid();
        for(Path current=path;current!=null;current=current.getParent())if(Files.isSymbolicLink(current))throw invalid();
    }

    private static FileAttribute<?>[] attributes(Path parent,boolean directory)throws IOException{
        var store=Files.getFileStore(parent);
        if(store.supportsFileAttributeView(PosixFileAttributeView.class))
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"))};
        if(store.supportsFileAttributeView(AclFileAttributeView.class)){
            UserPrincipal owner=currentUser(parent);
            var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if(directory)entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT);
            List<AclEntry> entries=List.of(entry.build());
            return new FileAttribute<?>[]{new FileAttribute<List<AclEntry>>(){
                public String name(){return "acl:acl";}
                public List<AclEntry> value(){return entries;}
            }};
        }
        throw invalid();
    }

    private static void ownerOnly(Path path,boolean directory)throws IOException{
        UserPrincipal owner=Files.getOwner(path,NO_LINKS);
        if(!owner.equals(currentUser(path)))throw invalid();
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,NO_LINKS);
        if(posix!=null){
            var permissions=posix.readAttributes().permissions();
            var expected=PosixFilePermissions.fromString(directory?"rwx------":"rw-------");
            if(!permissions.equals(expected))throw invalid();
            return;
        }
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,NO_LINKS);
        if(acl==null||!privateAcl(owner,acl.getAcl()))throw invalid();
    }

    /** Exposed to package tests without claiming Linux can execute Windows ACL semantics. */
    static boolean privateAcl(UserPrincipal owner,List<AclEntry> acl){
        boolean ownerAllow=false;
        for(var entry:acl){
            if(entry.type()==AclEntryType.ALLOW){
                if(!owner.equals(entry.principal()))return false;
                if(entry.permissions().contains(AclEntryPermission.READ_DATA)&&entry.permissions().contains(AclEntryPermission.WRITE_DATA))ownerAllow=true;
            }
        }
        return ownerAllow;
    }

    private static UserPrincipal currentUser(Path path)throws IOException{
        String name=System.getProperty("user.name");
        if(name==null||name.isBlank())throw invalid();
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
    }
    private static IOException invalid(){return new IOException("Private runtime path or current-owner permissions could not be verified.");}
}
