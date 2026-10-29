package com.jw.maps;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.google.gson.*;
import com.jw.maps.core.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Read-only South Australia tile store. Never materializes statewide geometry. */
final class SaDatabase {
    static final double GRID=.025;
    static final int BLOB_LIMIT=4*1024*1024,WINDOW_BYTES=25*1024*1024;
    private static final Set<String> KINDS=new HashSet<>(Arrays.asList("road","building","water","park","poi"));
    private SaDatabase() {}
    private static SQLiteDatabase open(File file) throws IOException {
        if(!file.isFile())throw new IOException("South Australia database is not installed");
        try {return SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                db->{throw new android.database.sqlite.SQLiteException("South Australia database is corrupt; file preserved");});}
        catch(RuntimeException e){throw new IOException("Cannot open South Australia database",e);}
    }
    static long version(File file) throws IOException {
        try(SQLiteDatabase db=open(file)){schema(db);return metadata(db).version;}
        catch(RuntimeException e){throw new IOException("Invalid South Australia metadata",e);}
    }
    static void validate(File file,long expectedVersion) throws IOException {
        try(SQLiteDatabase db=open(file)) {
            try(Cursor c=db.rawQuery("PRAGMA quick_check",null)) {
                if(!c.moveToFirst()||!"ok".equals(c.getString(0))||c.moveToNext())throw new IOException("South Australia SQLite integrity check failed");
            }
            schema(db);
            Metadata m=metadata(db);
            if(m.version!=expectedVersion)throw new IOException("South Australia database version does not match signed version");
            try(Cursor c=db.rawQuery("SELECT count(*),coalesce(sum(feature_count),0),coalesce(max(feature_count),0),"+
                    "coalesce(sum(CASE WHEN typeof(x)!='integer' OR typeof(y)!='integer' OR typeof(payload)!='blob' OR typeof(feature_count)!='integer' "+
                    "OR feature_count<0 OR feature_count>60000 OR length(payload)<1 OR length(payload)>4194304 "+
                    "OR x<? OR x>? OR y<? OR y>? THEN 1 ELSE 0 END),0) FROM tiles",
                    // Geofabrik retains complete ways beyond the extract header. Extra boundary cells do not extend advertised coverage.
                    new String[]{Integer.toString(tile(-180)),Integer.toString(tile(180)),Integer.toString(tile(-85)),Integer.toString(tile(85))})) {
                if(!c.moveToFirst()||c.getLong(0)!=m.tileCount||c.getLong(3)!=0||m.featureCount>c.getLong(1)||m.featureCount<c.getLong(2))
                    throw new IOException("South Australia tile counts, bounds or blob limits are invalid");
            }
        }catch(RuntimeException e){throw new IOException("Invalid South Australia database",e);}
    }
    static com.jw.maps.core.MapPack window(File file,double lon,double lat) throws IOException {
        if(!Double.isFinite(lon)||!Double.isFinite(lat)||lon < -180||lon > 180||lat < -85||lat > 85)throw new IOException("Invalid map camera position");
        try(SQLiteDatabase db=open(file)) {
            schema(db);Metadata m=metadata(db);
            if(lon<m.bbox[0]||lon>m.bbox[2]||lat<m.bbox[1]||lat>m.bbox[3])throw new IOException("Outside the South Australia source extent");
            int x=tile(lon),y=tile(lat);long bytes=0,features=0,points=0;
            List<com.jw.maps.core.MapPack> tiles=new ArrayList<>();
            String[] args={Integer.toString(x-1),Integer.toString(x+1),Integer.toString(y-1),Integer.toString(y+1)};
            // Read lengths first. Oversize blobs must never enter Android's CursorWindow.
            try(Cursor c=db.rawQuery("SELECT x,y,length(payload),feature_count FROM tiles WHERE x BETWEEN ? AND ? AND y BETWEEN ? AND ? LIMIT 10",args)) {
                while(c.moveToNext()) {
                    if(tiles.size()>=9||c.getLong(2)<1||c.getLong(2)>BLOB_LIMIT||c.getLong(3)<0||c.getLong(3)>60000)throw new IOException("Regional tile exceeds safe blob or feature limit");
                    byte[] compressed;
                    try(Cursor blob=db.rawQuery("SELECT payload FROM tiles WHERE x=? AND y=?",new String[]{c.getString(0),c.getString(1)})) {
                        if(!blob.moveToFirst())throw new IOException("Regional tile disappeared during read");compressed=blob.getBlob(0);
                    }
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    try(InputStream zip=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(compressed))) {
                        byte[] buffer=new byte[8192];int n;while((n=zip.read(buffer))!=-1){bytes+=n;if(bytes>WINDOW_BYTES)throw new IOException("Regional density exceeds 25 MiB decompression limit");out.write(buffer,0,n);}
                    }
                    com.jw.maps.core.MapPack p=com.jw.maps.core.MapPack.parse(out.toByteArray());
                    double[] expected={c.getInt(0)*GRID,c.getInt(1)*GRID,(c.getInt(0)+1)*GRID,(c.getInt(1)+1)*GRID};
                    for(int i=0;i<4;i++)if(Math.abs(p.bbox[i]-expected[i])>1e-8)throw new IOException("Regional tile bounds do not match grid");
                    if(p.features.size()!=c.getLong(3))throw new IOException("Regional tile feature count mismatch");
                    features+=p.features.size();
                    for(com.jw.maps.core.MapPack.Feature f:p.features)for(double[][] path:f.paths)points+=path.length;
                    if(features>60000||points>400000)throw new IOException("Regional density exceeds safe feature or coordinate limit");
                    tiles.add(p);
                }
            }
            double[] bounds={Math.max(m.bbox[0],(x-1)*GRID),Math.max(m.bbox[1],(y-1)*GRID),Math.min(m.bbox[2],(x+2)*GRID),Math.min(m.bbox[3],(y+2)*GRID)};
            return com.jw.maps.core.MapPack.window(tiles,bounds,m.version,m.sourceUrl);
        }catch(RuntimeException e){throw new IOException("Cannot read South Australia local window",e);}
    }
    private static void schema(SQLiteDatabase db) throws IOException {
        Set<String> tables=new HashSet<>();
        try(Cursor c=db.rawQuery("SELECT type,name,sql FROM sqlite_master",null)) {
            while(c.moveToNext()) {
                String type=c.getString(0),name=c.getString(1),sql=c.getString(2);
                if("table".equals(type)&&Arrays.asList("metadata","tiles").contains(name)&&sql!=null&&!sql.toLowerCase(Locale.ROOT).contains("virtual"))tables.add(name);
                else if(!"index".equals(type)||!name.startsWith("sqlite_autoindex_")||sql!=null)throw new IOException("Unsupported SQLite table, view, trigger or extension");
            }
        }
        if(tables.size()!=2)throw new IOException("Missing South Australia tables");
        columns(db,"metadata",new String[]{"key","value"},new String[]{"TEXT","TEXT"},new int[]{1,0},new int[]{0,1});
        columns(db,"tiles",new String[]{"x","y","payload","feature_count"},new String[]{"INTEGER","INTEGER","BLOB","INTEGER"},new int[]{1,2,0,0},new int[]{1,1,1,1});
    }
    private static void columns(SQLiteDatabase db,String table,String[] names,String[] types,int[] keys,int[] notNull) throws IOException {
        try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)) {
            int i=0;while(c.moveToNext()) {
                if(i>=names.length||!names[i].equals(c.getString(1))||!types[i].equalsIgnoreCase(c.getString(2))||c.getInt(5)!=keys[i]||c.getInt(3)!=notNull[i])throw new IOException("Unsupported South Australia schema");i++;
            }
            if(i!=names.length)throw new IOException("Incomplete South Australia schema");
        }
    }
    static int tile(double coordinate){return (int)Math.floor(coordinate/GRID);}
    private static final class Metadata {
        long version,tileCount,featureCount;double[] bbox=new double[4];String sourceUrl,attribution;
    }
    private static Metadata metadata(SQLiteDatabase db) throws IOException {
        JsonObject values=new JsonObject();int total=0;
        try(Cursor c=db.rawQuery("SELECT key,value,length(value) FROM metadata",null)) {
            while(c.moveToNext()) {
                if(c.isNull(0)||c.isNull(1)||c.getString(0).length()>80||c.getLong(2)>8192||++total>64)throw new IOException("South Australia metadata size limit");
                String key=c.getString(0),value=c.getString(1);
                // Parse scalar and array values with the same strict duplicate/trailing-token rules as map packs.
                JsonObject wrapped=Json.object(("{\"value\":"+value+"}").getBytes(StandardCharsets.UTF_8),16384);
                if(wrapped.size()!=1)throw new IOException("Invalid South Australia metadata JSON value");
                if(values.has(key))throw new IOException("Duplicate South Australia metadata");values.add(key,wrapped.get("value"));
            }
        }
        Metadata m=new Metadata();
        if(!Json.str(values,"id",64).equals("south-australia")||Json.integer(values,"formatVersion")!=1)throw new IOException("Unsupported South Australia format");
        m.version=Json.integer(values,"version");if(m.version<3)throw new IOException("South Australia database predates supported format");
        JsonElement step=values.get("gridStep");if(step==null||!step.isJsonPrimitive()||!step.getAsJsonPrimitive().isNumber()||step.getAsDouble()!=GRID)throw new IOException("Unsupported South Australia tile grid");
        JsonElement bounds=values.get("bbox");if(bounds==null||!bounds.isJsonArray()||bounds.getAsJsonArray().size()!=4)throw new IOException("Invalid statewide bounds");
        for(int i=0;i<4;i++){JsonElement e=bounds.getAsJsonArray().get(i);if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()||!Double.isFinite(e.getAsDouble()))throw new IOException("Invalid statewide bounds");m.bbox[i]=e.getAsDouble();}
        if(m.bbox[0]<-180||m.bbox[2]>180||m.bbox[1]<-85||m.bbox[3]>85||m.bbox[0]>=m.bbox[2]||m.bbox[1]>=m.bbox[3])throw new IOException("Invalid statewide bounds");
        m.sourceUrl=Json.str(values,"sourceUrl",500);m.attribution=Json.str(values,"attribution",300);
        if(!m.sourceUrl.startsWith("https://download.geofabrik.de/")||!m.sourceUrl.endsWith(".osm.pbf")||!m.attribution.contains("OpenStreetMap"))throw new IOException("Missing South Australia source attribution");
        if(!Json.str(values,"sourceSha256",64).matches("[a-fA-F0-9]{64}"))throw new IOException("Invalid South Australia source hash");
        try{Instant.parse(Json.str(values,"sourceTimestamp",64));}catch(java.time.format.DateTimeParseException e){throw new IOException("Invalid South Australia source timestamp",e);}
        m.tileCount=nonnegative(values,"tileCount");m.featureCount=nonnegative(values,"featureCount");
        JsonElement kinds=values.get("kinds");if(kinds==null||!kinds.isJsonObject()||kinds.getAsJsonObject().size()>5)throw new IOException("Invalid South Australia kinds");
        long count=0;for(var entry:kinds.getAsJsonObject().entrySet()){
            if(!KINDS.contains(entry.getKey()))throw new IOException("Invalid South Australia kind");
            long n=nonnegative(kinds.getAsJsonObject(),entry.getKey());if(n>m.featureCount)throw new IOException("Invalid South Australia kind count");count+=n;
        }
        if(count!=m.featureCount)throw new IOException("South Australia kind counts do not match feature count");
        return m;
    }
    private static long nonnegative(JsonObject values,String key) throws IOException {
        JsonElement e=values.get(key);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()||!e.toString().matches("0|[1-9][0-9]{0,17}"))throw new IOException("Invalid South Australia count: "+key);return e.getAsLong();
    }
}
