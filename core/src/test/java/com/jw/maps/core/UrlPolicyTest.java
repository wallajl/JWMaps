package com.jw.maps.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class UrlPolicyTest {
 @Test public void githubDownloadsAreBoundToExactRepositoryAndRedirectHosts() throws Exception {
   String good="https://github.com/wallajl/JWMaps/releases/download/v0.1.0/JWMaps-0.1.0.apk";
   UrlPolicy.requireAsset(good,"v0.1.0","JWMaps-0.1.0.apk");
   for(String bad:new String[]{good.replace("https:","http:"),good.replace("github.com/","github.com.evil/"),good.replace("wallajl/","other/"),good.replace("github.com/","a@github.com/"),good.replace("github.com/","github.com:444/"),good.replace("JWMaps-0.1.0.apk","../evil.apk")}) assertThrows(Exception.class,()->UrlPolicy.requireAsset(bad,"v0.1.0","JWMaps-0.1.0.apk"));
   assertThrows(Exception.class,()->UrlPolicy.requireRedirect("https://example.com/x","v0.1.0","JWMaps-0.1.0.apk"));
 }
}
