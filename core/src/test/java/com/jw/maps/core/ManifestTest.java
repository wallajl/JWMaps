package com.jw.maps.core;
import org.junit.Test;
import static org.junit.Assert.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
public class ManifestTest {
    public static String json(int app, int data) { return "{\"schemaVersion\":1,\"channel\":\"stable\",\"packageName\":\"com.jw.maps\",\"app\":{\"versionCode\":"+app+",\"versionName\":\"0.1.0\",\"asset\":\"JWMaps-0.1.0.apk\",\"sha256\":\""+"a".repeat(64)+"\",\"sizeBytes\":123,\"minSdk\":28},\"data\":{\"id\":\"adelaide-cbd\",\"version\":"+data+",\"asset\":\"adelaide-cbd-v"+data+".geojson\",\"sha256\":\""+"b".repeat(64)+"\",\"sizeBytes\":123,\"formatVersion\":1}}"; }
    private UpdateManifest verify(String text) throws Exception {
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(256);KeyPair k=g.generateKeyPair();byte[] bytes=text.getBytes(StandardCharsets.UTF_8);Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(k.getPrivate());s.update(bytes);return UpdateManifest.verify(bytes,Base64.getEncoder().encode(s.sign()),k.getPublic());
    }
    @Test public void signedRegionDatabaseIsOptionalBoundedAndVersionNamed() throws Exception {
        com.google.gson.JsonObject root=com.google.gson.JsonParser.parseString(json(2,2)).getAsJsonObject();
        com.google.gson.JsonObject region=new com.google.gson.JsonObject();region.addProperty("id","south-australia");region.addProperty("version",3);region.addProperty("formatVersion",1);region.addProperty("asset","south-australia-v3.sqlite");region.addProperty("sha256","c".repeat(64));region.addProperty("sizeBytes",70000000);root.add("region",region);
        UpdateManifest m=verify(root.toString());assertNotNull(m.region);assertEquals(3,m.regionVersion);assertEquals(70000000,m.region.sizeBytes());
        assertNull(verify(json(2,2)).region);
        region.addProperty("asset","south-australia-v2.sqlite");assertThrows(Exception.class,()->verify(root.toString()));
        region.addProperty("asset","south-australia-v3.sqlite");region.addProperty("sizeBytes",600000000);assertThrows(Exception.class,()->verify(root.toString()));
    }
    @Test public void rejectsDecimalAndReplay() throws Exception {
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC"); g.initialize(256); KeyPair k=g.generateKeyPair();
        for(String text:new String[]{json(1,1).replace("\"schemaVersion\":1","\"schemaVersion\":1.0"),json(1,1).replace("\"sizeBytes\":123","\"sizeBytes\":\"123\""),json(1,1).replace("\"channel\":\"stable\"","\"channel\":\"stable\",\"channel\":\"stable\"")}) {
            byte[] b=text.getBytes(StandardCharsets.UTF_8); Signature s=Signature.getInstance("SHA256withECDSA"); s.initSign(k.getPrivate()); s.update(b); byte[] sig=Base64.getEncoder().encode(s.sign());
            assertThrows(Exception.class,()->UpdateManifest.verify(b,sig,k.getPublic()));
        }
        byte[] b=json(1,1).getBytes(StandardCharsets.UTF_8); Signature s=Signature.getInstance("SHA256withECDSA"); s.initSign(k.getPrivate()); s.update(b);
        UpdateManifest m=UpdateManifest.verify(b,Base64.getEncoder().encode(s.sign()),k.getPublic());
        assertThrows(Exception.class,()->m.decide(1,2,1,2,35));
        assertThrows(Exception.class,()->m.decide(1,1,2,1,35));
    }
    @Test public void signedDataOnlyReleaseIsAccepted() throws Exception {
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC"); g.initialize(256); KeyPair k=g.generateKeyPair();
        byte[] bytes=json(1,2).getBytes(StandardCharsets.UTF_8);
        Signature s=Signature.getInstance("SHA256withECDSA"); s.initSign(k.getPrivate()); s.update(bytes);
        UpdateManifest m=UpdateManifest.verify(bytes, Base64.getEncoder().encode(s.sign()), k.getPublic());
        UpdateManifest.Decision d=m.decide(1,1,1,1,35);
        assertFalse(d.app()); assertTrue(d.data());
        bytes[10]^=1; assertThrows(Exception.class,()->UpdateManifest.verify(bytes,new byte[]{1},k.getPublic()));
    }
}
