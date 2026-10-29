package com.jw.maps;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import com.jw.maps.core.MapPack;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapStyleTest {
    @Test public void mapContentHasBlockInspiredGreenTerrainInBothHostModes() throws Exception {
        String json="{\"type\":\"FeatureCollection\",\"pack\":{\"id\":\"adelaide-cbd\",\"version\":1,\"formatVersion\":1,\"name\":\"Test map\",\"attribution\":\"OpenStreetMap contributors\",\"sourceUrl\":\"https://www.openstreetmap.org\"},\"bbox\":[138.5,-35,138.7,-34.8],\"features\":[{\"type\":\"Feature\",\"properties\":{\"kind\":\"poi\",\"name\":\"Fixture place\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[138.6,-34.9]}}]}";
        for(boolean dark:new boolean[]{false,true}) {
            MapRenderer renderer=new MapRenderer();renderer.setPack(MapPack.parse(json.getBytes(StandardCharsets.UTF_8)));
            Bitmap map=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888);
            renderer.draw(new Canvas(map),new Rect(0,0,600,600),dark,1);
            int ground=map.getPixel(20,20);
            assertTrue("Terrain must read as green in day and night, without changing the phone UI palette",Color.green(ground)>Color.red(ground)&&Color.green(ground)>Color.blue(ground));
        }
    }
}
