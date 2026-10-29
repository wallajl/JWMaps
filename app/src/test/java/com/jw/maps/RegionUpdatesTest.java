package com.jw.maps;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.google.gson.*;
import com.jw.maps.core.*;
import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Synthetic signed-update fixtures. Real statewide checks are separate evidence. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public class RegionUpdatesTest {
    private UpdateManifest manifest(byte[] data,long version) throws Exception {
        JsonObject root=JsonParser.parseString("{\"schemaVersion\":1,\"channel\":\"stable\",\"packageName\":\"com.jw.maps\",\"app\":{\"versionCode\":3,\"versionName\":\"0.1.2\",\"minSdk\":28,\"asset\":\"JWMaps-0.1.2.apk\",\"sizeBytes\":1,\"sha256\":\""+"a".repeat(64)+"\"},\"data\":{\"id\":\"adelaide-cbd\",\"version\":2,\"formatVersion\":1,\"asset\":\"adelaide-cbd-v2.geojson\",\"sizeBytes\":1,\"sha256\":\""+"a".repeat(64)+"\"}}").getAsJsonObject();
        JsonObject r=new JsonObject();r.addProperty("id","south-australia");r.addProperty("version",version);r.addProperty("formatVersion",1);r.addProperty("asset","south-australia-v"+version+".sqlite");r.addProperty("sha256",Artifacts.hash(data));r.addProperty("sizeBytes",data.length);root.add("region",r);
        byte[] raw=root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(256);KeyPair k=g.generateKeyPair();Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(k.getPrivate());s.update(raw);return UpdateManifest.verify(raw,Base64.getEncoder().encode(s.sign()),k.getPublic());
    }
    private ReleaseClient client(byte[] bytes) {return new ReleaseClient(){@Override void download(Release r,UpdateManifest.Artifact a,File out,long max)throws IOException{Artifacts.download(new ByteArrayInputStream(bytes),out,a,max);}};}
    @Test public void verifiedDatabaseInstallsAtomicallyAndBadUpdatePreservesKnownGood() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();c.getSharedPreferences(Updates.PREFS,0).edit().clear().commit();File active=new File(c.getFilesDir(),"sa-active.sqlite");active.delete();
        byte[] bytes=Files.readAllBytes(SaDatabaseTest.fixture().toPath());UpdateManifest m=manifest(bytes,3);
        ReleaseClient.Release release=new ReleaseClient.Release("v0.1.2",Map.of());
        assertTrue(RegionUpdates.apply(c,client(bytes),release,m,true));assertEquals(3,SaDatabase.version(active));assertEquals(3,c.getSharedPreferences(Updates.PREFS,0).getLong("highestRegion",0));
        assertFalse(RegionUpdates.apply(c,client(bytes),release,m,true));
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(active.getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE|android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {db.execSQL("UPDATE tiles SET payload=x'00' WHERE x=5544");}
        assertEquals("Metadata still advertises the original revision",3,SaDatabase.version(active));
        assertTrue("Same-version corruption must be repairable from the signed bytes",RegionUpdates.apply(c,client(bytes),release,m,true));
        assertArrayEquals(bytes,Files.readAllBytes(active.toPath()));
        byte[] good=Files.readAllBytes(active.toPath());UpdateManifest newer=manifest(bytes,4);
        assertThrows(IOException.class,()->RegionUpdates.apply(c,client("bad bytes".getBytes()),release,newer,true));
        assertArrayEquals(good,Files.readAllBytes(active.toPath()));
        assertThrows(IOException.class,()->RegionUpdates.apply(c,client(bytes),release,newer,true));
        assertArrayEquals(good,Files.readAllBytes(active.toPath()));
        assertThrows(IOException.class,()->RegionUpdates.apply(c,client(bytes),release,manifest(bytes,2),true));
        assertArrayEquals(good,Files.readAllBytes(active.toPath()));assertFalse(new File(Updates.dir(c),"region.download").exists());
    }
}
