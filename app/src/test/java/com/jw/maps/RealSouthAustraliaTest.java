package com.jw.maps;

import android.graphics.*;
import android.location.Location;
import com.jw.maps.core.MapPack;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Opt-in native SQLite/render exercise of the genuine statewide artifact. GPS fixes are simulated. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @SQLiteMode(SQLiteMode.Mode.NATIVE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RealSouthAustraliaTest {
    @Test public void realStatewideDatabaseLoadsSixDistantTownsAndFollowsAcrossWindows() throws Exception {
        String path=System.getenv("JW_SA_TEST_DB");Assume.assumeTrue("Set JW_SA_TEST_DB to exercise real statewide data",path!=null);
        File file=new File(path);SaDatabase.validate(file,3);assertEquals(3,SaDatabase.version(file));
        // Public town-node coordinates read directly from the dated Geofabrik source, not user locations.
        String[] names={"Adelaide","Mount Gambier","Port Augusta","Coober Pedy","Renmark","Kingscote","Dense metro window"};
        double[][] centers={{138.5999312,-34.9281805},{140.7842627,-37.8301386},{137.7639874,-32.4908819},{134.7536164,-29.0133682},{140.7468863,-34.1743516},{137.6367523,-35.6524847},{(5544+.5)*.025,(-1398+.5)*.025}};
        MapRenderer renderer=new MapRenderer();List<Map<String,Object>> evidence=new ArrayList<>();
        for(int i=0;i<names.length;i++){
            double lon=centers[i][0],lat=centers[i][1];long start=System.nanoTime();MapPack window=SaDatabase.window(file,lon,lat);
            long loadMillis=(System.nanoTime()-start)/1000000;
            assertEquals("south-australia",window.id);assertTrue(window.covers(lon,lat));assertFalse(names[i]+" must contain genuine mapped geometry",window.features.isEmpty());
            assertTrue(names[i]+" must have roads",window.features.stream().anyMatch(f->f.kind.equals("road")));
            Location fix=new Location("simulated-town-fix");fix.setLongitude(lon);fix.setLatitude(lat);renderer.setLocation(fix);renderer.setPack(window);renderer.recenter();
            assertArrayEquals(centers[i],renderer.center(),1e-7);assertTrue(renderer.isFollowing());
            Bitmap image=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);long drawStart=System.nanoTime();renderer.draw(new Canvas(image),new Rect(0,0,1280,720),true,1);
            long drawMillis=(System.nanoTime()-drawStart)/1000000;
            Map<String,Object> row=new LinkedHashMap<>();row.put("name",names[i]);row.put("features",window.features.size());row.put("loadMillis",loadMillis);row.put("drawMillis",drawMillis);evidence.add(row);
            String dir=System.getenv("JW_SA_EVIDENCE");if(dir!=null){File out=new File(dir,"native-"+names[i].toLowerCase(Locale.ROOT).replace(' ','-')+".png");out.getParentFile().mkdirs();try(OutputStream stream=new FileOutputStream(out)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}}
            image.recycle();
        }
        String json=new com.google.gson.Gson().toJson(evidence);System.out.println("REAL_SA_EVIDENCE "+json);
        String dir=System.getenv("JW_SA_EVIDENCE");if(dir!=null)java.nio.file.Files.write(new File(dir,"native-runtime-receipt.json").toPath(),json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
