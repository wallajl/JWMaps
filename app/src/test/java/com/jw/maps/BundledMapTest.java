package com.jw.maps;
import android.graphics.*;
import android.os.*;
import android.view.View;
import androidx.test.core.app.ApplicationProvider;
import com.jw.maps.core.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BundledMapTest {
 @Test public void suppliedOsmPackParsesAndDrawsOnPhoneAndCar() throws Exception {
    MapsApplication app=ApplicationProvider.getApplicationContext();
    MapPack p;try(InputStream in=app.getAssets().open("adelaide-cbd-v1.geojson")){p=MapPack.parse(Artifacts.bounded(in,(int)UpdateManifest.DATA_LIMIT));}
    assertEquals(1,p.version);assertTrue(p.features.size()>100);assertFalse(p.nearby(138.602,-34.922,5).isEmpty());
    app.getSharedPreferences(Updates.PREFS,0).edit().putLong("lastCheck",System.currentTimeMillis()).commit();
    try(var controller=Robolectric.buildActivity(MainActivity.class).setup()){
      app.io.submit(()->{}).get(30,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
      MainActivity a=controller.get();MapView map=a.findViewById(MainActivity.MAP_ID);assertNotNull(map.renderer.pack());
      View view=a.getWindow().getDecorView();view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));view.layout(0,0,1080,2400);
      Bitmap phone=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888);view.draw(new Canvas(phone));save(phone,"phone-preview.png");
    }
    MapRenderer r=new MapRenderer();r.setPack(p);
    for(boolean dark:new boolean[]{true,false}){Bitmap car=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);r.draw(new Canvas(car),new Rect(450,30,1250,690),dark,1.5f);save(car,dark?"car-map-night.png":"car-map-day.png");}
 }
 private void save(Bitmap b,String name)throws IOException{File dir=new File("build/qa");assertTrue(dir.isDirectory()||dir.mkdirs());try(OutputStream out=new FileOutputStream(new File(dir,name))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}}
}
