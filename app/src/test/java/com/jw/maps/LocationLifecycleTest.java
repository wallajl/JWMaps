package com.jw.maps;
import android.Manifest;
import android.app.Application;
import android.content.*;
import android.location.*;
import android.os.*;
import androidx.test.core.app.ApplicationProvider;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk={28,34})
public class LocationLifecycleTest {
 @Test public void acquiredFixStaysInMemoryAndTrackingStopsWithService(){
   Application app=ApplicationProvider.getApplicationContext();Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION);
   LocationManager manager=app.getSystemService(LocationManager.class);Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER,true);
   var controller=Robolectric.buildService(LocationService.class).create();LocationService service=controller.get();
   try{
      assertFalse(Shadows.shadowOf(manager).getLocationUpdateListeners().isEmpty());
      Location fix=new Location(LocationManager.GPS_PROVIDER);fix.setLatitude(-34.922);fix.setLongitude(138.602);fix.setAccuracy(6);fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
      service.onLocationChanged(fix);assertNotNull(service.fix);assertTrue(service.status.contains("GPS"));
      service.onProviderDisabled(LocationManager.GPS_PROVIDER);assertNull(service.fix);
   }finally{controller.destroy();}
   assertTrue(Shadows.shadowOf(manager).getLocationUpdateListeners().isEmpty());
 }
}
