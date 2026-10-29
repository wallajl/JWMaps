package com.jw.maps.core;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;
public class MapPackTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    public static byte[] pack(int version) { return ("{\"type\":\"FeatureCollection\",\"pack\":{\"id\":\"adelaide-cbd\",\"version\":"+version+",\"formatVersion\":1,\"name\":\"Adelaide city centre\",\"attribution\":\"© OpenStreetMap contributors\",\"sourceUrl\":\"https://www.openstreetmap.org\"},\"bbox\":[138.5,-35,138.7,-34.8],\"features\":[{\"type\":\"Feature\",\"properties\":{\"kind\":\"poi\",\"name\":\"Test place\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[138.6,-34.9]}}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    @Test public void projectionCoverageAndNearbyAreTruthful() throws Exception {
        MapPack p=MapPack.parse(pack(1)); assertTrue(p.covers(138.6,-34.9)); assertFalse(p.covers(139,-34.9));
        double[] xy=MapPack.project(138.6,-34.9), ll=MapPack.unproject(xy[0],xy[1]); assertEquals(138.6,ll[0],1e-8); assertEquals(-34.9,ll[1],1e-8);
        assertEquals("Test place",p.nearby(138.6,-34.9,5).get(0).name);
    }
    @Test public void osmWaysMayExtendBeyondExtractCoverageWithoutClaimingCoverage() throws Exception {
        String json=new String(pack(1),java.nio.charset.StandardCharsets.UTF_8).replace("138.6,-34.9","138.8,-34.9");
        MapPack parsed=MapPack.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(parsed.covers(138.8,-34.9));
        assertEquals(1,parsed.features.size());
    }
    @Test public void rejectsMalformedGeometryAndNonFiniteCoordinates() throws Exception {
        String good=new String(pack(1),java.nio.charset.StandardCharsets.UTF_8);
        for(String bad:new String[]{good.replace("138.6,-34.9","181,-34.9"),good.replace("138.6,-34.9","1e999,-34.9"),good.replace("138.6,-34.9","\"138.6\",-34.9"),good.replace("\"version\":1","\"version\":1.0"),good.replace("\"Point\"","\"Polygon\"")})assertThrows(IOException.class,()->MapPack.parse(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test public void malformedDataKeepsGoodPackAndVersion() throws Exception {
        PackStore s=new PackStore(new File(tmp.getRoot(),"active.geojson")); s.activate(pack(1),1);
        byte[] before=Files.readAllBytes(s.file.toPath());
        assertThrows(IOException.class,()->s.activate("{}".getBytes(),2));
        assertArrayEquals(before,Files.readAllBytes(s.file.toPath())); assertEquals(1,s.load().version);
        s.activate(pack(2),2); assertThrows(IOException.class,()->s.activate(pack(1),1)); assertEquals(2,s.load().version);
    }
}
