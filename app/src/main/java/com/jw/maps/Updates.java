package com.jw.maps;

import android.content.*;
import androidx.work.*;
import com.jw.maps.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

final class Updates {
    private static final Object LOCK=new Object();
    static final String PREFS="updates";
    static void schedule(Context c) {
        Constraints net=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("repository-poll",ExistingPeriodicWorkPolicy.KEEP,new PeriodicWorkRequest.Builder(UpdateWorker.class,12,TimeUnit.HOURS).setConstraints(net).build());
    }
    static void startup(Context c) {
        long last=c.getSharedPreferences(PREFS,0).getLong("lastCheck",0);
        if(System.currentTimeMillis()-last>TimeUnit.HOURS.toMillis(6)) WorkManager.getInstance(c).enqueueUniqueWork("startup-check",ExistingWorkPolicy.KEEP,new OneTimeWorkRequest.Builder(UpdateWorker.class).setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build());
    }
    static File dir(Context c) { File d=new File(c.getFilesDir(),"updates"); if(!d.exists()&&!d.mkdirs()) throw new IllegalStateException("Cannot create update directory"); return d; }
    static File apk(Context c) { return new File(dir(c),"JWMaps.apk"); }
    @android.annotation.SuppressLint("ApplySharedPref") // Checks run off the UI thread; revision state must reach disk before success.
    static String check(Context c) { return check(c,new ReleaseClient(),null); }
    @android.annotation.SuppressLint("ApplySharedPref") // Off-main-thread atomic rollback state.
    static String check(Context c,ReleaseClient client,java.security.PublicKey testKey) {
        synchronized(LOCK) {
            SharedPreferences prefs=c.getSharedPreferences(PREFS,0);
            long now=System.currentTimeMillis();
            if(now<prefs.getLong("notBefore",0)) return "Update checks paused by GitHub rate limit. Try later.";
            prefs.edit().putLong("lastCheck",now).commit();
            File dataDownload=new File(dir(c),"data.download");
            boolean dataApplied=false;
            try {
                ReleaseClient.Release r=client.latest();
                byte[] json=client.bytes(r,"update-manifest.json",65536),sig=client.bytes(r,"update-manifest.sig",256);
                UpdateManifest m=UpdateManifest.verify(json,sig,testKey==null?UpdateManifest.pinnedKey():testKey);
                MapPack current=((MapsApplication)c.getApplicationContext()).loadPack(); long currentData=current==null?1:current.version;
                long installed=c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode();
                UpdateManifest.Decision decision=m.decide(installed,currentData,prefs.getLong("highestApp",installed),prefs.getLong("highestData",currentData),android.os.Build.VERSION.SDK_INT);
                // Require exact assets even when a component does not need updating.
                r.asset(m.app.asset()); r.asset(m.data.asset());
                if(decision.data()) {
                    client.download(r,m.data,dataDownload,UpdateManifest.DATA_LIMIT);
                    byte[] bytes; try(InputStream in=new FileInputStream(dataDownload)) { bytes=Artifacts.bounded(in,(int)UpdateManifest.DATA_LIMIT); }
                    ((MapsApplication)c.getApplicationContext()).activate(bytes,m.dataVersion);
                    dataApplied=true;
                    if(!prefs.edit().putLong("highestData",m.dataVersion).commit())throw new IOException("Could not persist map update state");
                }
                if(decision.app() && BuildConfig.EXTERNAL_APK_UPDATES) {
                    // Always recheck staged bytes against the current signed release.
                    client.download(r,m.app,apk(c),UpdateManifest.APK_LIMIT);
                    try { ApkIdentity.validate(c,apk(c),m); atomic(new File(dir(c),"manifest.json"),json); atomic(new File(dir(c),"manifest.sig"),sig); }
                    catch(Exception e) { apk(c).delete(); throw e; }
                } else { apk(c).delete(); }
                if(!prefs.edit().putLong("highestApp",m.appVersion).putLong("highestData",m.dataVersion).putString("status",decision.app()?(BuildConfig.EXTERNAL_APK_UPDATES?"Verified app update ready. Install from this phone.":"A new app version is available. Update through Google Play."):decision.data()?"Map pack updated. App is current.":"App and map pack are current.").commit()) throw new IOException("Could not persist update state");
                return prefs.getString("status","");
            } catch(ReleaseClient.RateLimited e) {
                String status=(dataApplied?"Map pack updated. App update delayed: ":"")+e.getMessage();
                prefs.edit().putLong("notBefore",now+TimeUnit.HOURS.toMillis(6)).putString("status",status).commit();return status;
            } catch(Exception e) {
                String status=(dataApplied?"Map pack updated. App update not staged: ":"Update not applied: ")+(e.getMessage()==null?"connection or validation failed":e.getMessage()); prefs.edit().putString("status",status).commit(); return status;
            } finally { dataDownload.delete(); new File(dataDownload+".part").delete(); new File(apk(c)+".part").delete(); }
        }
    }
    static UpdateManifest staged(Context c) throws Exception {
        synchronized(LOCK) {
            byte[] json,sig;
            try(InputStream in=new FileInputStream(new File(dir(c),"manifest.json"))) { json=Artifacts.bounded(in,65536); }
            try(InputStream in=new FileInputStream(new File(dir(c),"manifest.sig"))) { sig=Artifacts.bounded(in,256); }
            UpdateManifest m=UpdateManifest.verify(json,sig,UpdateManifest.pinnedKey()); ApkIdentity.validate(c,apk(c),m); return m;
        }
    }
    private static void atomic(File file,byte[] bytes) throws IOException {
        File part=new File(file+".part");
        try { try(FileOutputStream out=new FileOutputStream(part)) {out.write(bytes);out.getFD().sync();} Files.move(part.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { part.delete(); }
    }
}
