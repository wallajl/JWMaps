package com.jw.maps;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Synthetic SQLite fixtures, not evidence of real OSM coverage. Uses native SQLite. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public class SaDatabaseTest {
    static File fixture() throws Exception {
        File file=File.createTempFile("synthetic-sa-", ".sqlite",ApplicationProvider.getApplicationContext().getCacheDir());
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null)) {
            db.execSQL("DROP TABLE IF EXISTS android_metadata");
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            db.execSQL("CREATE TABLE tiles(x INTEGER NOT NULL,y INTEGER NOT NULL,payload BLOB NOT NULL,feature_count INTEGER NOT NULL,PRIMARY KEY(x,y))");
            metadata(db,"formatVersion",new JsonPrimitive(1));metadata(db,"id",new JsonPrimitive("south-australia"));metadata(db,"version",new JsonPrimitive(3));
            metadata(db,"gridStep",new JsonPrimitive(.025));metadata(db,"bbox",JsonParser.parseString("[129,-39,142,-26]"));
            metadata(db,"sourceUrl",new JsonPrimitive("https://download.geofabrik.de/australia-oceania/australia/south-australia-261029.osm.pbf"));
            metadata(db,"attribution",new JsonPrimitive("OpenStreetMap contributors · ODbL"));
            metadata(db,"featureCount",new JsonPrimitive(2));metadata(db,"tileCount",new JsonPrimitive(3));
            metadata(db,"kinds",JsonParser.parseString("{\"poi\":2}"));
            metadata(db,"sourceSha256",new JsonPrimitive("a".repeat(64)));metadata(db,"sourceTimestamp",new JsonPrimitive("2026-10-29T00:00:00Z"));
            tile(db,5544,-1397,"city",138.60,-34.92);tile(db,5545,-1397,"city",138.60,-34.92);
            tile(db,5500,-1320,"distant",137.51,-32.99);
        }
        return file;
    }
    static void metadata(SQLiteDatabase db,String key,JsonElement value) {
        ContentValues row=new ContentValues();row.put("key",key);row.put("value",value.toString());db.insertWithOnConflict("metadata",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }
    static void tile(SQLiteDatabase db,int x,int y,String id,double lon,double lat) throws IOException {
        String json="{\"type\":\"FeatureCollection\",\"pack\":{\"id\":\"south-australia\",\"version\":3,\"formatVersion\":1,\"name\":\"Synthetic tile\",\"attribution\":\"OpenStreetMap contributors\",\"sourceUrl\":\"https://download.geofabrik.de/australia-oceania/australia/south-australia-261029.osm.pbf\"},\"bbox\":["+(x*.025)+","+(y*.025)+","+((x+1)*.025)+","+((y+1)*.025)+"],\"features\":[{\"type\":\"Feature\",\"id\":\""+id+"\",\"properties\":{\"kind\":\"poi\",\"name\":\"Synthetic place\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":["+lon+","+lat+"]}}]}";
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(GZIPOutputStream zip=new GZIPOutputStream(bytes)){zip.write(json.getBytes(StandardCharsets.UTF_8));}
        ContentValues row=new ContentValues();row.put("x",x);row.put("y",y);row.put("payload",bytes.toByteArray());row.put("feature_count",1);db.insertOrThrow("tiles",null,row);
    }
    @Test public void loadsOnlyNineTilesAndDeduplicatesGzipGeometryAcrossTileBorders() throws Exception {
        File file=fixture();
        var local=SaDatabase.window(file,138.61,-34.92);
        assertEquals("south-australia",local.id);assertEquals(3,local.version);
        assertEquals(1,local.features.size());assertEquals("city",local.features.get(0).id);
        assertTrue(local.covers(138.61,-34.92));assertFalse(local.covers(137.51,-32.99));
        var distant=SaDatabase.window(file,137.51,-32.99);assertEquals(1,distant.features.size());assertEquals("distant",distant.features.get(0).id);
        var rural=SaDatabase.window(file,135,-30);assertTrue(rural.features.isEmpty());assertTrue(rural.covers(135,-30));
    }
    @Test public void corruptionOutsideRequestedWindowDoesNotReadOrParseTheWholeState() throws Exception {
        File file=fixture();try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            db.execSQL("UPDATE tiles SET payload=x'00010203' WHERE x=5500");
        }
        SaDatabase.validate(file,3);
        assertEquals(1,SaDatabase.window(file,138.61,-34.92).features.size());
        assertThrows(IOException.class,()->SaDatabase.window(file,137.51,-32.99));
    }
    @Test public void rejectsUnsupportedSchemaAndWrongCountsAndOversizeBlob() throws Exception {
        File schema=fixture();try(SQLiteDatabase db=SQLiteDatabase.openDatabase(schema.getAbsolutePath(),null,SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {db.execSQL("CREATE VIEW injected AS SELECT * FROM tiles");}
        assertThrows(IOException.class,()->SaDatabase.validate(schema,3));
        File counts=fixture();try(SQLiteDatabase db=SQLiteDatabase.openDatabase(counts.getAbsolutePath(),null,SQLiteDatabase.NO_LOCALIZED_COLLATORS)){metadata(db,"tileCount",new JsonPrimitive(99));}
        assertThrows(IOException.class,()->SaDatabase.validate(counts,3));
        File huge=fixture();try(SQLiteDatabase db=SQLiteDatabase.openDatabase(huge.getAbsolutePath(),null,SQLiteDatabase.NO_LOCALIZED_COLLATORS)){db.execSQL("UPDATE tiles SET payload=zeroblob(4194305) WHERE x=5500");}
        assertThrows(IOException.class,()->SaDatabase.validate(huge,3));assertThrows(IOException.class,()->SaDatabase.window(huge,137.51,-32.99));
    }
    @Test public void rejectsDecompressionBombBeforeParsingAndLeavesDamagedFileIntact() throws Exception {
        File file=fixture();ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(out)) {byte[] block=new byte[1024*1024];for(int i=0;i<26;i++)gzip.write(block);}
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.NO_LOCALIZED_COLLATORS)){ContentValues row=new ContentValues();row.put("payload",out.toByteArray());db.update("tiles",row,"x=?",new String[]{"5500"});}
        IOException error=assertThrows(IOException.class,()->SaDatabase.window(file,137.51,-32.99));assertTrue(error.getMessage(),error.getMessage().contains("25 MiB"));
        File damaged=File.createTempFile("synthetic-damaged-", ".sqlite",file.getParentFile());byte[] invalid="this is not sqlite".getBytes(StandardCharsets.UTF_8);java.nio.file.Files.write(damaged.toPath(),invalid);
        assertThrows(IOException.class,()->SaDatabase.validate(damaged,3));assertArrayEquals(invalid,java.nio.file.Files.readAllBytes(damaged.toPath()));
    }
    @Test public void acceptsTilesBeyondHeaderBoundsButDoesNotExpandAdvertisedCoverage() throws Exception {
        File file=fixture();try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            tile(db,5720,-1397,"boundary",143.01,-34.92);metadata(db,"tileCount",new JsonPrimitive(4));metadata(db,"featureCount",new JsonPrimitive(3));metadata(db,"kinds",JsonParser.parseString("{\"poi\":3}"));
        }
        SaDatabase.validate(file,3);assertThrows(IOException.class,()->SaDatabase.window(file,143.01,-34.92));
    }
    @Test public void acceptsBoundedProducerDiagnosticsInMetadata() throws Exception {
        File file=fixture();try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {for(int i=0;i<25;i++)metadata(db,"diagnostic"+i,new JsonPrimitive(i));}
        SaDatabase.validate(file,3);
    }
    @Test public void rejectsMetadataThatSmugglesAdditionalJsonFields() throws Exception {
        File file=fixture();try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            ContentValues values=new ContentValues();values.put("value","3,\"extra\":true");db.update("metadata",values,"key=?",new String[]{"version"});
        }
        assertThrows(IOException.class,()->SaDatabase.validate(file,3));
    }
    @Test public void validatesNativeSqliteMetadataWithoutLoadingTheStateIntoMemory() throws Exception {
        File file=fixture();SaDatabase.validate(file,3);assertEquals(3,SaDatabase.version(file));
        assertThrows(IOException.class,()->SaDatabase.validate(file,4));
    }
}
