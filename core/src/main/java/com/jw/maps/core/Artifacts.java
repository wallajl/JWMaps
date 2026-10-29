package com.jw.maps.core;
import java.io.*;
import java.nio.file.*;
import java.security.*;
public final class Artifacts {
    private Artifacts() {}
    public static String hash(byte[] b) throws IOException { try { return hex(MessageDigest.getInstance("SHA-256").digest(b)); } catch(GeneralSecurityException e) { throw new IOException(e); } }
    private static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(); for(byte b:bytes) s.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return s.toString(); }
    public static byte[] bounded(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n;
        while((n=in.read(buf))!=-1) { if(out.size()+n>limit) throw new IOException("Response exceeds limit"); out.write(buf,0,n); } return out.toByteArray();
    }
    public static void download(InputStream in,File out,UpdateManifest.Artifact a,long max) throws IOException {
        File part=new File(out+".part"); out.delete(); part.delete(); boolean done=false;
        try {
            if(a.sizeBytes()<=0||a.sizeBytes()>max) throw new IOException("Artifact exceeds limit");
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); long total=0;
            try(InputStream source=in; FileOutputStream sink=new FileOutputStream(part)) {
                byte[] buf=new byte[16384]; int n;
                while((n=source.read(buf))!=-1) { total+=n; if(total>max||total>a.sizeBytes()) throw new IOException("Artifact too large"); sink.write(buf,0,n); digest.update(buf,0,n); }
                sink.getFD().sync();
            }
            if(total!=a.sizeBytes()||!hex(digest.digest()).equals(a.sha256())) throw new IOException("Artifact hash/length mismatch");
            Files.move(part.toPath(),out.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); done=true;
        } catch(GeneralSecurityException e) { throw new IOException(e); }
        finally { part.delete(); if(!done) out.delete(); }
    }
}
