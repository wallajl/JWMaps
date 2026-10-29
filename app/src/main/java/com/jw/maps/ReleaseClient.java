package com.jw.maps;

import com.google.gson.*;
import com.jw.maps.core.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Anonymous fixed-repository release client. Every redirect is checked before opening. */
class ReleaseClient {
    record Release(String tag,Map<String,String> assets) {
        String asset(String name) throws IOException { String url=assets.get(name); if(url==null) throw new IOException("Release asset missing: "+name); UrlPolicy.requireAsset(url,tag,name); return url; }
    }
    static final class RateLimited extends IOException { RateLimited() { super("GitHub rate limit. Try again later."); } }
    private HttpsURLConnection connect(String url) throws IOException {
        HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent","JWMaps/"+BuildConfig.VERSION_NAME);
        c.setRequestProperty("Accept-Encoding","identity"); return c;
    }
    Release latest() throws IOException {
        HttpsURLConnection c=connect(UrlPolicy.LATEST);
        try {
            c.setRequestProperty("Accept","application/vnd.github+json"); c.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
            check(c); JsonObject root;
            try(InputStream in=c.getInputStream()) { root=Json.object(Artifacts.bounded(in,1024*1024),1024*1024); }
            if(!falseBoolean(root,"draft")||!falseBoolean(root,"prerelease")) throw new IOException("Release is not published stable");
            Json.str(root,"published_at",100); String tag=Json.str(root,"tag_name",81);
            if(!tag.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")||tag.contains("..")) throw new IOException("Unsafe release tag");
            JsonElement a=root.get("assets"); if(a==null||!a.isJsonArray()||a.getAsJsonArray().size()>100) throw new IOException("Invalid release assets");
            Map<String,String> assets=new HashMap<>();
            for(JsonElement e:a.getAsJsonArray()) { if(!e.isJsonObject()) throw new IOException("Invalid release asset"); JsonObject o=e.getAsJsonObject(); String name=Json.str(o,"name",128), url=Json.str(o,"browser_download_url",2048); if(assets.put(name,url)!=null) throw new IOException("Duplicate asset name"); }
            return new Release(tag,Collections.unmodifiableMap(assets));
        } finally { c.disconnect(); }
    }
    private boolean falseBoolean(JsonObject o,String key) { JsonElement e=o.get(key); return e!=null&&e.isJsonPrimitive()&&e.getAsJsonPrimitive().isBoolean()&&!e.getAsBoolean(); }
    private void check(HttpsURLConnection c) throws IOException {
        int code=c.getResponseCode(); if(code==403||code==429) throw new RateLimited(); if(code!=200) throw new IOException(code==404?"No stable release published yet":"GitHub response "+code);
    }
    InputStream open(Release release,String asset) throws IOException {
        String url=release.asset(asset);
        for(int redirects=0;redirects<=4;redirects++) {
            UrlPolicy.requireRedirect(url,release.tag,asset); HttpsURLConnection c=connect(url);
            try {
                int code=c.getResponseCode();
                if(code==301||code==302||code==303||code==307||code==308) {
                    String location=c.getHeaderField("Location"); if(location==null) throw new IOException("Missing redirect");
                    url=new URL(new URL(url),location).toString(); UrlPolicy.requireRedirect(url,release.tag,asset); c.disconnect(); continue;
                }
                check(c); final long deadline=android.os.SystemClock.elapsedRealtime()+10*60*1000;
                return new FilterInputStream(c.getInputStream()) {
                    private void deadline() throws IOException { if(android.os.SystemClock.elapsedRealtime()>deadline) throw new IOException("Download timed out"); }
                    @Override public int read() throws IOException { deadline(); return super.read(); }
                    @Override public int read(byte[] b,int o,int l) throws IOException { deadline(); return in.read(b,o,l); }
                    @Override public void close() throws IOException { try {super.close();} finally {c.disconnect();} }
                };
            } catch(IOException|RuntimeException e) { c.disconnect(); throw e; }
        }
        throw new IOException("Too many release redirects");
    }
    byte[] bytes(Release release,String asset,int max) throws IOException { try(InputStream in=open(release,asset)) { return Artifacts.bounded(in,max); } }
    void download(Release r,UpdateManifest.Artifact a,File target,long max) throws IOException { try(InputStream in=open(r,a.asset())) { Artifacts.download(in,target,a,max); } }
}
