package com.jw.maps;

import android.location.Location;
import com.jw.maps.core.MapPack;
import java.io.InputStream;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class FollowModeTest {
    private MapPack pack() throws Exception {
        try(InputStream in=androidx.test.core.app.ApplicationProvider.getApplicationContext().getAssets().open("adelaide-cbd-v1.geojson")) { return MapPack.parse(in.readAllBytes()); }
    }
    private Location fix(double lon,double lat) { Location l=new Location("gps");l.setLongitude(lon);l.setLatitude(lat);return l; }
    private void assertCenter(MapRenderer r,double lon,double lat) {assertArrayEquals(new double[]{lon,lat},r.center(),0.0000001);}
    @Test public void firstLocationAutomaticallyFollowsWithoutPressingRecenter() throws Exception {
        MapRenderer r=new MapRenderer();r.setPack(pack());r.setLocation(fix(138.601,-34.923));
        assertCenter(r,138.601,-34.923);
        r.setLocation(fix(138.602,-34.924));assertCenter(r,138.602,-34.924);
    }
    @Test public void replacingPackPreservesFollowAndCamera() throws Exception {
        MapRenderer r=new MapRenderer();r.setPack(pack());r.setLocation(fix(138.601,-34.923));r.recenter();
        r.setPack(pack());r.setLocation(fix(138.602,-34.924));assertCenter(r,138.602,-34.924);
    }
    @Test public void panningPausesFollowUntilRecenterEvenAcrossPackRefresh() throws Exception {
        MapRenderer r=new MapRenderer();r.setPack(pack());r.setLocation(fix(138.601,-34.923));r.recenter();r.pan(100,20);
        double[] browsed=r.center();r.setPack(pack());r.setLocation(fix(138.602,-34.924));assertArrayEquals(browsed,r.center(),0.0000001);
        r.recenter();assertCenter(r,138.602,-34.924);r.setLocation(fix(138.603,-34.924));assertCenter(r,138.603,-34.924);
    }
}
