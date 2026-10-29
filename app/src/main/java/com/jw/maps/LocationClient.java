package com.jw.maps;
import android.content.*;
import android.location.Location;
import android.os.IBinder;
final class LocationClient implements ServiceConnection {
    private final Context context;
    private final Runnable changed;
    private boolean bound;
    private LocationService service;
    LocationClient(Context c,Runnable r){context=c;changed=r;}
    void start(){if(!bound&&LocationService.permitted(context))bound=context.bindService(new Intent(context,LocationService.class),this,Context.BIND_AUTO_CREATE);else changed.run();}
    void stop(){if(service!=null)service.remove(changed);service=null;if(bound){context.unbindService(this);bound=false;}}
    Location fix(){if(service==null||service.fix==null)return null;Location l=service.fix;return android.os.SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos()<120000000000L?new Location(l):null;}
    String status(){return !LocationService.permitted(context)?"Location denied. Adelaide city-centre preview.":service==null?"Adelaide city-centre preview. Waiting for location.":fix()==null?service.status+" · Adelaide city-centre preview":service.status;}
    @Override public void onServiceConnected(ComponentName name,IBinder binder){service=((LocationService.LocalBinder)binder).service();service.add(changed);}
    @Override public void onServiceDisconnected(ComponentName name){service=null;changed.run();}
}
