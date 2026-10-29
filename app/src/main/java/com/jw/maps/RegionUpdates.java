package com.jw.maps;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import com.jw.maps.core.UpdateManifest;
import java.io.*;
import java.nio.file.*;

/** Independent, streamed, signed statewide map installation. Never reads the complete DB into RAM. */
final class RegionUpdates {
    private RegionUpdates() {}
    private static boolean matches(File file,UpdateManifest.Artifact artifact) throws IOException {
        if(file.length()!=artifact.sizeBytes())return false;
        try{
            java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)hash.update(buffer,0,n);}
            return String.format(java.util.Locale.ROOT,"%064x",new java.math.BigInteger(1,hash.digest())).equals(artifact.sha256());
        }catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
    static boolean apply(Context context,ReleaseClient client,ReleaseClient.Release release,UpdateManifest manifest,boolean allowMetered) throws IOException {
        if(manifest.region==null)return false;
        SharedPreferences prefs=context.getSharedPreferences(Updates.PREFS,0);
        File active=new File(context.getFilesDir(),"sa-active.sqlite");long installed=0;
        if(active.exists())try{installed=SaDatabase.version(active);}catch(IOException ignored){/* A signed replacement can repair a damaged database. */}
        long highest=Math.max(installed,prefs.getLong("highestRegion",0));
        if(manifest.regionVersion<highest)throw new IOException("South Australia rollback rejected");
        if(installed==manifest.regionVersion&&matches(active,manifest.region))return false;
        ConnectivityManager network=context.getSystemService(ConnectivityManager.class);
        if(!allowMetered&&network!=null&&network.isActiveNetworkMetered())throw new IOException("South Australia download waiting for Wi-Fi. Download manually on the phone to use mobile data.");
        if(context.getFilesDir().getUsableSpace()<manifest.region.sizeBytes()*2+10L*1024*1024)throw new IOException("Not enough free storage for the South Australia map");
        File candidate=new File(Updates.dir(context),"region.download");
        try{
            client.download(release,manifest.region,candidate,UpdateManifest.REGION_LIMIT);
            SaDatabase.validate(candidate,manifest.regionVersion);
            try(FileOutputStream sync=new FileOutputStream(candidate,true)){sync.getFD().sync();}
            Files.move(candidate.toPath(),active.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            if(!prefs.edit().putLong("highestRegion",manifest.regionVersion).commit())throw new IOException("Could not persist South Australia revision");
            return true;
        }finally{candidate.delete();new File(candidate+".part").delete();}
    }
}
