package com.jw.maps;

import android.app.Application;
import com.jw.maps.core.*;
import java.io.*;
public final class MapsApplication extends Application implements androidx.work.Configuration.Provider {
    @androidx.annotation.NonNull @Override public androidx.work.Configuration getWorkManagerConfiguration(){return new androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build();}
    private volatile MapPack pack;
    private PackStore store;
    final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public void onCreate() { super.onCreate(); store=new PackStore(new File(getFilesDir(),"active.geojson")); Updates.schedule(this); }
    MapPack currentPack(){return pack;}
    synchronized MapPack loadPack() {
        if(pack!=null) return pack;
        try { if(store.file.exists()) pack=store.load(); }
        catch(IOException ignored) { /* Bundled known-good preview remains available after disk damage. */ }
        if(pack==null) try(InputStream in=getAssets().open("adelaide-cbd-v1.geojson")) {pack=MapPack.parse(Artifacts.bounded(in,(int)UpdateManifest.DATA_LIMIT));}
        catch(IOException ignored) { return null; }
        return pack;
    }
    synchronized void activate(byte[] bytes,long version) throws IOException {store.activate(bytes,version);pack=store.load();}
}
