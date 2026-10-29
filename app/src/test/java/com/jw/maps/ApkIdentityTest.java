package com.jw.maps;
import android.content.pm.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class ApkIdentityTest {
 private SigningInfo sign(String... values){SigningInfo s=mock(SigningInfo.class);android.content.pm.Signature[] signatures=new android.content.pm.Signature[values.length];for(int i=0;i<values.length;i++)signatures[i]=new android.content.pm.Signature(values[i].getBytes());when(s.getApkContentsSigners()).thenReturn(signatures);return s;}
 @Test public void archivePackageVersionAndHashMustMatchSignedMetadata()throws Exception{
    android.content.Context base=androidx.test.core.app.ApplicationProvider.getApplicationContext();android.content.Context context=mock(android.content.Context.class);PackageManager pm=mock(PackageManager.class);when(context.getPackageManager()).thenReturn(pm);when(context.getPackageName()).thenReturn("com.jw.maps");
    byte[] bytes="APK identity fixture".getBytes();java.io.File file=new java.io.File(base.getCacheDir(),"identity.apk");java.nio.file.Files.write(file.toPath(),bytes);
    String json="{\"schemaVersion\":1,\"channel\":\"stable\",\"packageName\":\"com.jw.maps\",\"app\":{\"versionCode\":2,\"versionName\":\"0.2.0\",\"asset\":\"JWMaps-0.2.0.apk\",\"sha256\":\""+com.jw.maps.core.Artifacts.hash(bytes)+"\",\"sizeBytes\":"+bytes.length+",\"minSdk\":28},\"data\":{\"id\":\"adelaide-cbd\",\"version\":1,\"asset\":\"adelaide-cbd-v1.geojson\",\"sha256\":\""+"a".repeat(64)+"\",\"sizeBytes\":1,\"formatVersion\":1}}";
    java.security.KeyPairGenerator gen=java.security.KeyPairGenerator.getInstance("EC");gen.initialize(256);java.security.KeyPair key=gen.generateKeyPair();java.security.Signature signer=java.security.Signature.getInstance("SHA256withECDSA");signer.initSign(key.getPrivate());signer.update(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    com.jw.maps.core.UpdateManifest m=com.jw.maps.core.UpdateManifest.verify(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),java.util.Base64.getEncoder().encode(signer.sign()),key.getPublic());
    PackageInfo installed=new PackageInfo();installed.packageName="com.jw.maps";installed.setLongVersionCode(1);installed.signingInfo=sign("channel");
    PackageInfo archive=new PackageInfo();archive.packageName="com.jw.maps";archive.setLongVersionCode(2);archive.signingInfo=sign("channel");
    when(pm.getPackageInfo("com.jw.maps",PackageManager.GET_SIGNING_CERTIFICATES)).thenReturn(installed);when(pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES)).thenReturn(archive);
    ApkIdentity.validate(context,file,m);
    archive.packageName="wrong.package";assertThrows(java.io.IOException.class,()->ApkIdentity.validate(context,file,m));archive.packageName="com.jw.maps";
    archive.setLongVersionCode(3);assertThrows(java.io.IOException.class,()->ApkIdentity.validate(context,file,m));archive.setLongVersionCode(2);
    archive.signingInfo=sign("other channel");assertThrows(java.io.IOException.class,()->ApkIdentity.validate(context,file,m));archive.signingInfo=sign("channel");
    bytes[0]^=1;java.nio.file.Files.write(file.toPath(),bytes);assertThrows(java.io.IOException.class,()->ApkIdentity.validate(context,file,m));
 }
 @Test public void requiresWholeCurrentIdentityNotHistoricalOverlap(){
    assertTrue(ApkIdentity.sameCurrentSigners(sign("a","b"),sign("b","a")));
    assertFalse(ApkIdentity.sameCurrentSigners(sign("a","b"),sign("a","c")));
    SigningInfo installed=sign("new"),wrong=sign("evil");android.content.pm.Signature[] history={new android.content.pm.Signature("old".getBytes())};when(installed.getSigningCertificateHistory()).thenReturn(history);when(wrong.getSigningCertificateHistory()).thenReturn(history);
    assertFalse(ApkIdentity.sameCurrentSigners(installed,wrong));assertFalse(ApkIdentity.sameCurrentSigners(null,sign("a")));assertFalse(ApkIdentity.sameCurrentSigners(sign(),sign()));
 }
}
