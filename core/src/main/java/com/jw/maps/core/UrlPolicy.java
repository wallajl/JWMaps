package com.jw.maps.core;
import java.io.IOException;
import java.net.*;
public final class UrlPolicy {
    public static final String LATEST="https://api.github.com/repos/wallajl/JWMaps/releases/latest";
    private UrlPolicy() {}
    private static URI uri(String s) throws IOException {
        try { URI u=new URI(s); if(!"https".equals(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getPort()!=-1||u.getRawFragment()!=null) throw new IOException("Unsafe release URL"); return u; }
        catch(URISyntaxException e) { throw new IOException("Invalid release URL",e); }
    }
    public static void requireAsset(String url,String tag,String asset) throws IOException {
        if(!tag.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")||tag.contains("..")||!asset.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")||asset.contains("..")) throw new IOException("Unsafe asset/tag");
        URI u=uri(url);
        if(!u.getHost().equals("github.com")||u.getRawQuery()!=null||!u.getRawPath().equals("/wallajl/JWMaps/releases/download/"+tag+"/"+asset)) throw new IOException("Asset is not from this repository/release");
    }
    public static void requireRedirect(String url,String tag,String asset) throws IOException {
        URI u=uri(url); if(u.getHost().equals("github.com")) { requireAsset(url,tag,asset); return; }
        if(!(u.getHost().equals("release-assets.githubusercontent.com")||u.getHost().equals("objects.githubusercontent.com"))||!u.getRawPath().matches("/github-production-release-asset(?:-2e65be)?/[0-9]+/[A-Za-z0-9-]+")) throw new IOException("Untrusted release redirect");
    }
}
