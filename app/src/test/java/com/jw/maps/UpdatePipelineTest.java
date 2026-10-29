package com.jw.maps;
import android.content.*;
import androidx.test.core.app.ApplicationProvider;
import com.jw.maps.core.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class UpdatePipelineTest {
 private byte[] pack(int v){return ("{\"type\":\"FeatureCollection\",\"pack\":{\"id\":\"adelaide-cbd\",\"version\":"+v+",\"formatVersion\":1,\"name\":\"Test fixture\",\"attribution\":\"OpenStreetMap contributors\",\"sourceUrl\":\"https://www.openstreetmap.org\"},\"bbox\":[138.59,-34.93,138.61,-34.91],\"features\":[{\"type\":\"Feature\",\"properties\":{\"kind\":\"poi\",\"name\":\"Fixture place\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[138.60,-34.92]}}]}").getBytes(StandardCharsets.UTF_8);}
 private class FixtureClient extends ReleaseClient {
   byte[] data,manifest,signature;final Map<String,String> assets=new HashMap<>();
   FixtureClient(byte[] b,int version,KeyPair key)throws Exception{
     data=b;JsonObject root=new JsonObject();root.addProperty("schemaVersion",1);root.addProperty("channel","stable");root.addProperty("packageName","com.jw.maps");
     JsonObject app=new JsonObject();app.addProperty("versionCode",BuildConfig.VERSION_CODE);app.addProperty("versionName",BuildConfig.VERSION_NAME);app.addProperty("asset","JWMaps-"+BuildConfig.VERSION_NAME+".apk");app.addProperty("sha256",Artifacts.hash(new byte[]{1}));app.addProperty("sizeBytes",1);app.addProperty("minSdk",28);root.add("app",app);
     JsonObject d=new JsonObject();d.addProperty("id","adelaide-cbd");d.addProperty("version",version);d.addProperty("asset","adelaide-cbd-v"+version+".geojson");d.addProperty("sha256",Artifacts.hash(data));d.addProperty("sizeBytes",data.length);d.addProperty("formatVersion",1);root.add("data",d);manifest=root.toString().getBytes(StandardCharsets.UTF_8);
     Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(key.getPrivate());signer.update(manifest);signature=Base64.getEncoder().encode(signer.sign());
     for(String name:new String[]{"update-manifest.json","update-manifest.sig","JWMaps-"+BuildConfig.VERSION_NAME+".apk","adelaide-cbd-v"+version+".geojson"})assets.put(name,"https://github.com/wallajl/JWMaps/releases/download/v0.1.0/"+name);
   }
   @Override Release latest(){return new Release("v0.1.0",assets);}
   @Override byte[] bytes(Release r,String asset,int max){return asset.endsWith(".sig")?signature:manifest;}
   @Override void download(Release r,UpdateManifest.Artifact a,File out,long max)throws IOException{Artifacts.download(new ByteArrayInputStream(data),out,a,max);}
 }
 @Test public void failedApkStageKeepsIndependentDataCommitAndRetriesOnlyTheApp()throws Exception{
   org.junit.Assume.assumeTrue(BuildConfig.EXTERNAL_APK_UPDATES);
   MapsApplication app=ApplicationProvider.getApplicationContext();app.getSharedPreferences(Updates.PREFS,0).edit().clear().commit();app.activate(pack(1),1);
   KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(256);KeyPair key=generator.generateKeyPair();
   int[] downloads={0,0};
   FixtureClient client=new FixtureClient(pack(2),2,key){
     @Override void download(Release r,UpdateManifest.Artifact a,File out,long max)throws IOException{
       downloads[a.asset().endsWith(".apk")?1:0]++;super.download(r,a,out,max);
     }
   };
   JsonObject root=JsonParser.parseString(new String(client.manifest,StandardCharsets.UTF_8)).getAsJsonObject();
   root.getAsJsonObject("app").addProperty("versionCode",BuildConfig.VERSION_CODE+1);root.getAsJsonObject("app").addProperty("versionName","0.2.0");root.getAsJsonObject("app").addProperty("asset","JWMaps-0.2.0.apk");
   client.assets.put("JWMaps-0.2.0.apk","https://github.com/wallajl/JWMaps/releases/download/v0.1.0/JWMaps-0.2.0.apk");
   client.manifest=root.toString().getBytes(StandardCharsets.UTF_8);Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(key.getPrivate());signer.update(client.manifest);client.signature=Base64.getEncoder().encode(signer.sign());
   String result=Updates.check(app,client,key.getPublic());
   assertTrue(result,result.contains("Map pack updated"));assertTrue(result,result.contains("App update not staged"));
   assertEquals(2,app.loadPack().version);assertEquals(2,app.getSharedPreferences(Updates.PREFS,0).getLong("highestData",0));
   Updates.check(app,client,key.getPublic());
   assertEquals("Already active map must not download again",1,downloads[0]);assertEquals("A failed app stage must remain retryable",2,downloads[1]);assertEquals(2,app.loadPack().version);
 }
 @Test public void signedDataOnlyUpdateCommitsThenMalformedAndReplayedDataPreserveGoodState()throws Exception{
   MapsApplication app=ApplicationProvider.getApplicationContext();app.getSharedPreferences(Updates.PREFS,0).edit().clear().commit();app.activate(pack(1),1);
   KeyPairGenerator gen=KeyPairGenerator.getInstance("EC");gen.initialize(256);KeyPair key=gen.generateKeyPair();
   String result=Updates.check(app,new FixtureClient(pack(2),2,key),key.getPublic());assertTrue(result,result.contains("Map pack updated"));assertEquals(2,app.loadPack().version);assertEquals(2,app.getSharedPreferences(Updates.PREFS,0).getLong("highestData",0));
   byte[] before=Files.readAllBytes(new File(app.getFilesDir(),"active.geojson").toPath());
   assertTrue(Updates.check(app,new FixtureClient("{}".getBytes(),3,key),key.getPublic()).contains("not applied"));assertArrayEquals(before,Files.readAllBytes(new File(app.getFilesDir(),"active.geojson").toPath()));assertEquals(2,app.getSharedPreferences(Updates.PREFS,0).getLong("highestData",0));
   assertTrue(Updates.check(app,new FixtureClient(pack(1),1,key),key.getPublic()).contains("rollback"));assertEquals(2,app.loadPack().version);
   FixtureClient corrupt=new FixtureClient(pack(3),3,key);corrupt.data[3]^=1;assertTrue(Updates.check(app,corrupt,key.getPublic()).contains("hash/length"));assertEquals(2,app.loadPack().version);
   assertFalse(new File(Updates.dir(app),"data.download").exists());assertFalse(new File(Updates.dir(app),"data.download.part").exists());assertNull(Shadows.shadowOf(app).getNextStartedActivity());
 }
}
