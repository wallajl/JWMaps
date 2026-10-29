package com.jw.maps.core;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import static org.junit.Assert.*;
public class ArtifactTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void verifiesExactHashAndLengthAndRemovesPartials() throws Exception {
        byte[] b="real artifact bytes".getBytes(); String h=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        File f=new File(tmp.getRoot(),"artifact");
        Artifacts.download(new ByteArrayInputStream(b),f,new UpdateManifest.Artifact("x",h,b.length),100);
        assertArrayEquals(b,Files.readAllBytes(f.toPath()));
        assertThrows(IOException.class,()->Artifacts.download(new ByteArrayInputStream(b),f,new UpdateManifest.Artifact("x",h,b.length-1),100));
        assertFalse(f.exists()); assertFalse(new File(f+".part").exists());
        assertThrows(IOException.class,()->Artifacts.download(new ByteArrayInputStream(b),f,new UpdateManifest.Artifact("x","0".repeat(64),b.length),100));
    }
}
