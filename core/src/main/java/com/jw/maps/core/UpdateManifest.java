package com.jw.maps.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class UpdateManifest {
    public static final long APK_LIMIT=100L*1024*1024, DATA_LIMIT=25L*1024*1024;
    public static final String PIN="MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsVXX7MNmAMqWkhOlgksZmEagxpcQu6+2ZCns5Ak8uMqKOCZ69kEU3jg7geqn5SSrcljtt3FIqY/+nhyUdHDiaQ==";
    public record Artifact(String asset,String sha256,long sizeBytes) {}
    public record Decision(boolean app, boolean data) {}
    public final long appVersion,dataVersion,minSdk;
    public final String versionName,dataId;
    public final Artifact app,data;
    private UpdateManifest(JsonObject o) throws IOException {
        if(Json.integer(o,"schemaVersion")!=1||!Json.str(o,"channel",20).equals("stable")||!Json.str(o,"packageName",100).equals("com.jw.maps")) throw new IOException("Unsupported release");
        JsonObject a=Json.obj(o,"app"), d=Json.obj(o,"data");
        appVersion=Json.integer(a,"versionCode"); dataVersion=Json.integer(d,"version"); minSdk=Json.integer(a,"minSdk");
        if(appVersion>Integer.MAX_VALUE || minSdk>1000||Json.integer(d,"formatVersion")!=1) throw new IOException("Unsupported format/version");
        versionName=Json.str(a,"versionName",64); dataId=Json.str(d,"id",64);
        if(!versionName.matches("[0-9]+\\.[0-9]+\\.[0-9]+")||!dataId.equals("adelaide-cbd")) throw new IOException("Unsupported version or pack");
        app=artifact(a,APK_LIMIT); data=artifact(d,DATA_LIMIT);
        if(!app.asset.equals("JWMaps-"+versionName+".apk")||!data.asset.equals(dataId+"-v"+dataVersion+".geojson")) throw new IOException("Unexpected asset name");
    }
    private static Artifact artifact(JsonObject o,long max) throws IOException {
        String n=Json.str(o,"asset",128), h=Json.str(o,"sha256",64); long s=Json.integer(o,"sizeBytes");
        if(!n.matches("[A-Za-z0-9][A-Za-z0-9._-]*")||n.contains("..")||!h.matches("[0-9a-f]{64}")||s>max) throw new IOException("Invalid artifact");
        return new Artifact(n,h,s);
    }
    public static PublicKey pinnedKey() throws GeneralSecurityException { return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PIN))); }
    public static UpdateManifest verify(byte[] b,byte[] s,PublicKey k) throws IOException, GeneralSecurityException {
        if(b.length>65536||s.length>256) throw new IOException("Manifest size limit");
        byte[] der; try { der=Base64.getDecoder().decode(new String(s,java.nio.charset.StandardCharsets.US_ASCII).trim()); } catch(IllegalArgumentException e) { throw new IOException("Invalid signature encoding",e); }
        Signature v=Signature.getInstance("SHA256withECDSA"); v.initVerify(k); v.update(b); if(!v.verify(der)) throw new GeneralSecurityException("Release signature rejected");
        return new UpdateManifest(Json.object(b,65536));
    }
    public Decision decide(long installedApp,long installedData,long highestApp,long highestData,int sdk) throws IOException {
        if(appVersion<Math.max(installedApp,highestApp)||dataVersion<Math.max(installedData,highestData)) throw new IOException("Release rollback rejected");
        if(minSdk>sdk) throw new IOException("Update needs a newer Android version");
        return new Decision(appVersion>installedApp,dataVersion>installedData);
    }
}
