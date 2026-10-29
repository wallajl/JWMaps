package com.jw.maps;

import android.content.Context;
import android.content.pm.*;
import com.jw.maps.core.*;
import java.io.*;
import java.security.*;
import java.util.*;

final class ApkIdentity {
    private ApkIdentity() {}
    static void validate(Context c,File file,UpdateManifest manifest) throws IOException,PackageManager.NameNotFoundException {
        if(file.length()!=manifest.app.sizeBytes()) throw new IOException("APK length mismatch");
        try(InputStream in=new FileInputStream(file)) {
            MessageDigest d=MessageDigest.getInstance("SHA-256"); byte[] buf=new byte[16384]; int n;
            while((n=in.read(buf))!=-1) d.update(buf,0,n);
            StringBuilder h=new StringBuilder(); for(byte b:d.digest()) h.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!h.toString().equals(manifest.app.sha256())) throw new IOException("APK hash mismatch");
        } catch(GeneralSecurityException e) { throw new IOException(e); }
        PackageManager pm=c.getPackageManager();
        PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null||!"com.jw.maps".equals(archive.packageName)||archive.getLongVersionCode()!=manifest.appVersion||archive.getLongVersionCode()<=installed.getLongVersionCode()) throw new IOException("APK package/version mismatch");
        if(!sameCurrentSigners(installed.signingInfo,archive.signingInfo)) throw new IOException("Update signing certificate differs. Use the same distribution channel as this installation.");
    }
    static boolean sameCurrentSigners(SigningInfo a,SigningInfo b) {
        // Do not accept historical overlap. Exact full current-signer set equality is deliberately conservative about key rotation.
        if(a==null||b==null) return false;
        android.content.pm.Signature[] x=a.getApkContentsSigners(),y=b.getApkContentsSigners();
        if(x==null||y==null||x.length==0||x.length!=y.length) return false;
        Set<String> one=new HashSet<>(),two=new HashSet<>();
        for(android.content.pm.Signature s:x) one.add(Base64.getEncoder().encodeToString(s.toByteArray()));
        for(android.content.pm.Signature s:y) two.add(Base64.getEncoder().encodeToString(s.toByteArray()));
        return one.size()==x.length&&two.size()==y.length&&one.equals(two);
    }
}
