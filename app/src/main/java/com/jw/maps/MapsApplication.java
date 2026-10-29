package com.jw.maps;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import com.jw.maps.core.*;
import java.io.*;
import java.util.*;

public final class MapsApplication extends Application implements androidx.work.Configuration.Provider {
    @androidx.annotation.NonNull @Override public androidx.work.Configuration getWorkManagerConfiguration(){return new androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build();}
    private volatile MapPack pack;
    private PackStore store;
    final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<MapRenderer,RegionRequest> regions=new WeakHashMap<>();
    private long regionGeneration;
    private String checkedStamp="",databaseError="";
    private volatile String installedStatus="South Australia not installed · CBD preview only";

    @Override public void onCreate() {
        super.onCreate();store=new PackStore(new File(getFilesDir(),"active.geojson"));Updates.schedule(this);
    }
    MapPack currentPack(){return pack;}
    synchronized MapPack loadPack() {
        if(pack!=null)return pack;
        try{if(store.file.exists())pack=store.load();}
        catch(IOException ignored){/* Bundled known-good preview remains available after disk damage. */}
        if(pack==null)try(InputStream in=getAssets().open("adelaide-cbd-v1.geojson")){pack=MapPack.parse(Artifacts.bounded(in,(int)UpdateManifest.DATA_LIMIT));}
        catch(IOException ignored){return null;}
        return pack;
    }
    synchronized void activate(byte[] bytes,long version) throws IOException {store.activate(bytes,version);pack=store.load();}

    private static final class RegionRequest {
        String desired="",applied="",status="";
        boolean running;
        Runnable changed;
    }
    File regionFile(){return new File(getFilesDir(),"sa-active.sqlite");}
    boolean hasRegion(){return regionFile().isFile();}
    String regionStatus(){return installedStatus;}
    synchronized String regionStatus(MapRenderer renderer){
        RegionRequest r=regions.get(renderer);return r==null||r.status.isEmpty()?installedStatus:r.status;
    }
    private String stamp(){File f=regionFile();return regionGeneration+":"+f.lastModified()+":"+f.length();}
    private String key(MapRenderer renderer){
        double[] center=renderer.requestedCenter();return stamp()+":"+SaDatabase.tile(center[0])+":"+SaDatabase.tile(center[1]);
    }
    synchronized void forgetRegion(MapRenderer renderer){regions.remove(renderer);}
    void regionActivated(){
        synchronized(this){
            regionGeneration++;checkedStamp="";databaseError="";
            installedStatus=hasRegion()?"Checking installed South Australia database…":"South Australia not installed · CBD preview only";
        }
        main.post(()->{
            synchronized(MapsApplication.this){
                for(var entry:new ArrayList<>(regions.entrySet()))requestRegion(entry.getKey(),entry.getValue().changed);
            }
        });
    }
    /** Called on redraw or a GPS fix. Only cheap file stats and tile keys run on the UI thread. */
    synchronized void requestRegion(MapRenderer renderer,Runnable changed){
        RegionRequest state=regions.computeIfAbsent(renderer,r->new RegionRequest());
        state.changed=changed;state.desired=key(renderer);
        if(!hasRegion()){
            state.status="South Australia not installed · CBD preview only";installedStatus=state.status;return;
        }
        if(state.running||state.desired.equals(state.applied))return;
        state.running=true;state.status="Loading South Australia local window…";
        String requested=state.desired,fileStamp=stamp();double[] center=renderer.requestedCenter();
        io.execute(()->{
            MapPack result=null;String status;
            try{
                validateRegion(fileStamp);
                result=SaDatabase.window(regionFile(),center[0],center[1]);
                status=result.features.isEmpty()?"South Australia local window · No mapped features here":"South Australia local window · North up";
            }catch(IOException e){status="South Australia window unavailable: "+e.getMessage();}
            MapPack loaded=result;String finalStatus=status;
            main.post(()->{
                synchronized(MapsApplication.this){
                    if(regions.get(renderer)!=state)return;
                    state.running=false;
                    // Compare the live camera too, even if no redraw happened while I/O was running.
                    if(!requested.equals(key(renderer))){requestRegion(renderer,state.changed);return;}
                    state.applied=requested;state.status=finalStatus;
                    if(loaded!=null)renderer.setPack(loaded);
                    if(state.changed!=null)state.changed.run();
                }
            });
        });
    }
    private void validateRegion(String fileStamp) throws IOException {
        synchronized(this){if(fileStamp.equals(checkedStamp)){if(!databaseError.isEmpty())throw new IOException(databaseError);return;}}
        String error="",description;
        // Do not hold the application lock during SQLite checks. A statewide count scan must not block redraw.
        try{
            long version=SaDatabase.version(regionFile());
            long highest=getSharedPreferences(Updates.PREFS,0).getLong("highestRegion",0);
            if(version<highest)throw new IOException("South Australia rollback rejected");
            SaDatabase.validate(regionFile(),version);
            description=String.format(Locale.ROOT,"South Australia v%d installed · %.1f MiB",version,regionFile().length()/(1024.0*1024));
        }catch(IOException e){error=e.getMessage();description="South Australia database unavailable: "+error;}
        synchronized(this){
            if(fileStamp.equals(stamp())){checkedStamp=fileStamp;databaseError=error;installedStatus=description;}
        }
        if(!error.isEmpty())throw new IOException(error);
    }
}
