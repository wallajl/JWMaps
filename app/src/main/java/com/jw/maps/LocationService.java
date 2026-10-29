package com.jw.maps;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.os.*;
import java.util.*;

/** Bound only while phone/car screens are in use. No saved location history. */
public final class LocationService extends Service implements LocationListener {
    final class LocalBinder extends Binder { LocationService service(){return LocationService.this;} }
    private final LocalBinder binder=new LocalBinder();
    private LocationManager manager;
    private final Set<Runnable> listeners=new HashSet<>();
    Location fix;
    String status="Waiting for GPS";
    static boolean permitted(Context c){return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    @Override public void onCreate(){super.onCreate();manager=(LocationManager)getSystemService(LOCATION_SERVICE);startTracking();}
    private void startTracking(){
        if(!permitted(this)){status="Location denied. Enable it on the phone.";return;}
        try {
            NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("location","Map location",NotificationManager.IMPORTANCE_LOW));
            PendingIntent phone=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(this,"location").setSmallIcon(R.drawable.ic_map).setContentTitle("JWMaps location active").setContentText("Only while the map is in use. No trip history saved.").setContentIntent(phone).setOngoing(true).build();
            if(Build.VERSION.SDK_INT>=29)startForeground(10,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);else startForeground(10,n);
            boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
            boolean requested=false;
            for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}) {
                if(provider.equals(LocationManager.GPS_PROVIDER)&&!fine)continue;
                if(manager.getAllProviders().contains(provider)){manager.requestLocationUpdates(provider,2000,3,this,Looper.getMainLooper());if(manager.isProviderEnabled(provider))requested=true;}
            }
            if(!requested)status="Location disabled. Enable device location on the phone.";
        }catch(SecurityException|IllegalStateException e){status="Location unavailable. Open JWMaps on the phone while parked.";}
    }
    void add(Runnable r){listeners.add(r);r.run();}
    void remove(Runnable r){listeners.remove(r);}
    private void changed(){for(Runnable r:new ArrayList<>(listeners))r.run();}
    @Override public IBinder onBind(Intent i){return binder;}
    @Override public void onLocationChanged(Location l){if(l.getElapsedRealtimeNanos()>0&&SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos()>120000000000L)return;fix=new Location(l);status=l.hasAccuracy()?String.format(Locale.ROOT,"GPS · ±%.0f m",l.getAccuracy()):"Location active";changed();}
    @Override public void onProviderDisabled(String provider){fix=null;status="Location disabled or signal lost";changed();}
    @Override public void onProviderEnabled(String provider){status="Waiting for GPS";changed();}
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    @Override public void onDestroy(){if(manager!=null)manager.removeUpdates(this);stopForeground(STOP_FOREGROUND_REMOVE);listeners.clear();fix=null;super.onDestroy();}
}
