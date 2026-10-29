package com.jw.maps;

import android.location.Location;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual runtime with synthetic native SQLite data. No emulator or real GPS claim. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public class SaRuntimeTest {
    private MapsApplication app;
    @Before public void setup() throws Exception {
        app=ApplicationProvider.getApplicationContext();
        Files.copy(SaDatabaseTest.fixture().toPath(),new File(app.getFilesDir(),"sa-active.sqlite").toPath(),StandardCopyOption.REPLACE_EXISTING);
        app.regionActivated();
    }
    private Location fix(double lon,double lat){Location l=new Location("synthetic");l.setLongitude(lon);l.setLatitude(lat);return l;}
    private void drain() throws Exception {
        for(int i=0;i<4;i++){app.io.submit(()->{}).get(20,TimeUnit.SECONDS);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    }
    @Test public void reportsRollbackAndWindowErrorsWithoutReplacingTheLastGoodMap() throws Exception {
        MapRenderer renderer=new MapRenderer();var preview=app.loadPack();renderer.setPack(preview);renderer.center(138.61,-34.92);
        app.getSharedPreferences(Updates.PREFS,0).edit().putLong("highestRegion",4).commit();
        app.requestRegion(renderer,()->{});drain();assertSame(preview,renderer.pack());
        assertTrue(app.regionStatus(renderer),app.regionStatus(renderer).contains("rollback rejected"));
        app.getSharedPreferences(Updates.PREFS,0).edit().putLong("highestRegion",3).commit();app.regionActivated();drain();
        renderer.center(135,-30);app.requestRegion(renderer,()->{});drain();assertTrue(renderer.pack().features.isEmpty());
        assertTrue(app.regionStatus(renderer),app.regionStatus(renderer).contains("No mapped features here"));
        var rural=renderer.pack();renderer.center(150,-30);app.requestRegion(renderer,()->{});drain();assertSame(rural,renderer.pack());
        assertTrue(app.regionStatus(renderer),app.regionStatus(renderer).contains("Outside the South Australia source extent"));
    }
    @Test public void onlyNewestCameraRequestPublishesAndActivationRefreshesWithoutCameraReset() throws Exception {
        MapRenderer renderer=new MapRenderer();renderer.setPack(app.loadPack());renderer.center(138.61,-34.92);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        app.io.execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(10,TimeUnit.SECONDS));int[] changes={0};
        try{app.requestRegion(renderer,()->changes[0]++);renderer.center(137.51,-32.99);app.requestRegion(renderer,()->changes[0]++);}finally{release.countDown();}
        drain();assertEquals(1,changes[0]);assertEquals("distant",renderer.pack().features.get(0).id);assertFalse(renderer.isFollowing());
        var before=renderer.pack();app.regionActivated();drain();
        assertNotSame(before,renderer.pack());assertEquals(2,changes[0]);assertFalse(renderer.isFollowing());
        assertArrayEquals(new double[]{137.51,-32.99},renderer.center(),1e-7);
    }
    @Test public void statewideDownloadExplainsMobileDataBeforeStarting() throws Exception {
        assertTrue(app.regionFile().delete());app.regionActivated();drain();
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            drain();var activity=controller.get();findButton(activity.getWindow().getDecorView(),"Updates").performClick();
            findButton(activity.getWindow().getDecorView(),"Download South Australia").performClick();
            var dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            String text=allText(dialog.getWindow().getDecorView());assertTrue(text,text.contains("mobile data"));assertTrue(text,text.contains("Wi-Fi"));
        }
    }
    @Test public void updatesOfferDownloadWhenAbsentAndNameTheLocalWindowWhenInstalled() throws Exception {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            drain();MainActivity activity=controller.get();
            findButton(activity.getWindow().getDecorView(),"Updates").performClick();
            assertTrue(allText(activity.getWindow().getDecorView()).contains("South Australia v3 installed"));
            assertFalse(allText(activity.getWindow().getDecorView()).contains("Download South Australia"));
            findButton(activity.getWindow().getDecorView(),"Info").performClick();
            assertTrue(allText(activity.getWindow().getDecorView()).contains("local window boundary"));
            assertTrue(new File(app.getFilesDir(),"sa-active.sqlite").delete());app.regionActivated();drain();
            findButton(activity.getWindow().getDecorView(),"Updates").performClick();
            assertNotNull(findButton(activity.getWindow().getDecorView(),"Download South Australia"));
        }
    }
    private android.widget.Button findButton(android.view.View view,String text){
        if(view instanceof android.widget.Button&&((android.widget.Button)view).getText().toString().equals(text))return (android.widget.Button)view;
        if(view instanceof android.view.ViewGroup){var group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){var found=findButton(group.getChildAt(i),text);if(found!=null)return found;}}return null;
    }
    private String allText(android.view.View view){
        StringBuilder text=new StringBuilder();if(view instanceof android.widget.TextView)text.append(((android.widget.TextView)view).getText()).append('\n');
        if(view instanceof android.view.ViewGroup){var group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)text.append(allText(group.getChildAt(i)));}return text.toString();
    }
    @Test public void phoneDrawAndCarRedrawRequestTheirOwnPannedWindows() throws Exception {
        MapView view=new MapView(app);view.renderer.setPack(app.loadPack());view.renderer.center(137.51,-32.99);
        view.layout(0,0,400,300);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(400,300,android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));drain();
        assertEquals("distant",view.renderer.pack().features.get(0).id);assertFalse(view.renderer.isFollowing());
        assertArrayEquals(new double[]{137.51,-32.99},view.renderer.center(),1e-7);
        var context=androidx.car.app.testing.TestCarContext.createCarContext(app);MapsCarScreen car=new MapsCarScreen(context);
        car.renderer.setPack(app.loadPack());car.renderer.center(138.61,-34.92);car.redraw();drain();
        assertEquals("city",car.renderer.pack().features.get(0).id);assertEquals("distant",view.renderer.pack().features.get(0).id);
        assertFalse(car.renderer.isFollowing());bitmap.recycle();
    }
    @Test public void followsGpsAcrossDistantWindowsWithoutResettingFollowOrRebuildingEachDraw() throws Exception {
        MapRenderer renderer=new MapRenderer();renderer.setPack(app.loadPack());renderer.setLocation(fix(138.61,-34.92));
        int[] changes={0};app.requestRegion(renderer,()->changes[0]++);drain();
        assertEquals("city",renderer.pack().features.get(0).id);assertTrue(renderer.isFollowing());
        assertArrayEquals(new double[]{138.61,-34.92},renderer.center(),1e-7);
        var same=renderer.pack();app.requestRegion(renderer,()->changes[0]++);drain();assertSame(same,renderer.pack());assertEquals(1,changes[0]);
        renderer.setLocation(fix(137.51,-32.99));app.requestRegion(renderer,()->changes[0]++);drain();
        assertEquals("distant",renderer.pack().features.get(0).id);assertTrue(renderer.isFollowing());
        assertArrayEquals(new double[]{137.51,-32.99},renderer.center(),1e-7);assertEquals(2,changes[0]);
    }
}
