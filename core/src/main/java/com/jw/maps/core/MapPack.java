package com.jw.maps.core;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

public final class MapPack {
    public final long version;
    public final String id,name,attribution,sourceUrl;
    public final double[] bbox;
    public final List<Feature> features;
    public static final class Feature {
        public final String id,kind,name,type;
        private final String fingerprint;
        public final List<double[][]> paths;
        public final double lon,lat,height;
        Feature(String id,String fingerprint,String k,String n,String t,List<double[][]> p,double h) { this.id=id;this.fingerprint=fingerprint;kind=k; name=n; type=t; paths=Collections.unmodifiableList(p); lon=p.get(0)[0][0]; lat=p.get(0)[0][1]; height=h; }
    }
    private MapPack(JsonObject root) throws IOException {
        if(!Json.str(root,"type",32).equals("FeatureCollection")) throw new IOException("Not a FeatureCollection");
        JsonObject p=Json.obj(root,"pack"); id=Json.str(p,"id",64); version=Json.integer(p,"version");
        if((!id.equals("adelaide-cbd")&&!id.equals("south-australia"))||Json.integer(p,"formatVersion")!=1) throw new IOException("Unsupported map pack");
        name=Json.str(p,"name",160); attribution=Json.str(p,"attribution",300); sourceUrl=Json.str(p,"sourceUrl",500);
        if(!attribution.contains("OpenStreetMap")||!sourceUrl.startsWith("https://")) throw new IOException("Missing map attribution");
        JsonArray bounds=array(root.get("bbox")); if(bounds.size()!=4) throw new IOException("Invalid bbox"); bbox=new double[4];
        for(int i=0;i<4;i++) bbox[i]=number(bounds.get(i));
        if(bbox[0]<-180||bbox[2]>180||bbox[1]<-85||bbox[3]>85||bbox[0]>=bbox[2]||bbox[1]>=bbox[3]||bbox[2]-bbox[0]>5||bbox[3]-bbox[1]>5) throw new IOException("Invalid coverage bounds");
        JsonArray fs=array(root.get("features")); if((fs.size()==0&&!id.equals("south-australia"))||fs.size()>60000) throw new IOException("Feature count limit");
        List<Feature> out=new ArrayList<>(); int[] count={0};
        for(JsonElement el:fs) {
            if(!el.isJsonObject()) throw new IOException("Invalid feature"); JsonObject f=el.getAsJsonObject();
            if(!Json.str(f,"type",20).equals("Feature")) throw new IOException("Invalid feature type");
            JsonObject props=Json.obj(f,"properties"), geom=Json.obj(f,"geometry");
            String kind=Json.str(props,"kind",20), type=Json.str(geom,"type",20), n=props.has("name")&&!props.get("name").isJsonNull()?Json.str(props,"name",300):"";
            if(!Arrays.asList("road","building","water","park","poi").contains(kind)) throw new IOException("Invalid feature kind");
            if((kind.equals("road")&&!type.equals("LineString"))||(kind.equals("poi")&&!type.equals("Point"))||(!kind.equals("road")&&!kind.equals("poi")&&!type.equals("Polygon"))) throw new IOException("Kind/geometry mismatch");
            List<double[][]> paths=new ArrayList<>(); JsonArray coords=array(geom.get("coordinates"));
            if(type.equals("Point")) paths.add(new double[][]{coordinate(coords,count)});
            else if(type.equals("LineString")) paths.add(path(coords,false,count));
            else if(type.equals("Polygon")) { if(coords.size()<1||coords.size()>64) throw new IOException("Polygon ring limit"); for(JsonElement ring:coords) paths.add(path(array(ring),true,count)); }
            else throw new IOException("Unsupported geometry");
            double height=8; // Rendering default in metres, not a measured building height.
            if(props.has("height")&&props.get("height").isJsonPrimitive()&&props.get("height").getAsJsonPrimitive().isNumber()) height=Math.max(1,Math.min(150,number(props.get("height"))));
            String featureId=f.has("id")&&f.get("id").isJsonPrimitive()?f.get("id").getAsString():"";
            if(featureId.length()>160)throw new IOException("Feature identifier limit");
            out.add(new Feature(featureId,Artifacts.hash(f.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),kind,n,type,paths,height));
        }
        features=Collections.unmodifiableList(out);
    }
    private MapPack(MapPack metadata,List<Feature> fs) {
        id=metadata.id;version=metadata.version;name=metadata.name;attribution=metadata.attribution;sourceUrl=metadata.sourceUrl;bbox=metadata.bbox.clone();features=Collections.unmodifiableList(fs);
    }
    /** A bounded nine-tile view. Full-state geometry is never loaded into this model. */
    public static MapPack window(List<MapPack> tiles,double[] bounds,long version,String source) throws IOException {
        if(tiles.size()>9||version<1||bounds==null||bounds.length!=4)throw new IOException("Invalid regional window");
        JsonObject root=new JsonObject(),meta=new JsonObject();root.addProperty("type","FeatureCollection");
        meta.addProperty("id","south-australia");meta.addProperty("version",version);meta.addProperty("formatVersion",1);meta.addProperty("name","South Australia");meta.addProperty("attribution","© OpenStreetMap contributors, ODbL 1.0");meta.addProperty("sourceUrl",source);root.add("pack",meta);
        JsonArray bbox=new JsonArray();for(double v:bounds)bbox.add(v);root.add("bbox",bbox);root.add("features",new JsonArray());
        MapPack metadata=new MapPack(root);List<Feature> out=new ArrayList<>();Set<String> seen=new HashSet<>();long points=0;
        for(MapPack tile:tiles){
            if(!tile.id.equals("south-australia")||tile.version!=version||!tile.sourceUrl.equals(source))throw new IOException("Mixed regional sources or revisions");
            for(Feature f:tile.features)if(seen.add(f.fingerprint)){
                for(double[][] path:f.paths)points+=path.length;
                if(out.size()>=60000||points>400000)throw new IOException("Regional density exceeds safe display limit");
                out.add(f);
            }
        }
        return new MapPack(metadata,out);
    }
    public static MapPack parse(byte[] b) throws IOException {
        try { return new MapPack(Json.object(b,(int)UpdateManifest.DATA_LIMIT)); }
        catch(IllegalStateException|NumberFormatException e) { throw new IOException("Malformed map geometry",e); }
    }
    private static JsonArray array(JsonElement e) throws IOException { if(e==null||!e.isJsonArray()) throw new IOException("Expected coordinate array"); return e.getAsJsonArray(); }
    private static double number(JsonElement e) throws IOException { if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()) throw new IOException("Expected finite number"); double x=e.getAsDouble(); if(!Double.isFinite(x)) throw new IOException("Non-finite coordinate"); return x; }
    private double[] coordinate(JsonArray a,int[] count) throws IOException {
        if(a.size()!=2||++count[0]>400000) throw new IOException("Coordinate limit");
        double x=number(a.get(0)),y=number(a.get(1));
        // An OSM extract includes complete ways crossing its boundary. Coverage remains the requested extract, not every vertex.
        if(x < -180 || x > 180 || y < -85 || y > 85) throw new IOException("Coordinate outside WGS84 map range");
        return new double[]{x,y};
    }
    private double[][] path(JsonArray a,boolean ring,int[] count) throws IOException {
        if(a.size()<(ring?4:2)||a.size()>20000) throw new IOException("Invalid path length");
        double[][] p=new double[a.size()][]; for(int i=0;i<p.length;i++) p[i]=coordinate(array(a.get(i)),count);
        if(ring&&!Arrays.equals(p[0],p[p.length-1])) throw new IOException("Unclosed polygon"); return p;
    }
    public boolean covers(double x,double y) { return Double.isFinite(x)&&Double.isFinite(y)&&x>=bbox[0]&&x<=bbox[2]&&y>=bbox[1]&&y<=bbox[3]; }
    public static double[] project(double lon,double lat) { double y=Math.max(-85,Math.min(85,lat)); return new double[]{6378137*Math.toRadians(lon),6378137*Math.log(Math.tan(Math.PI/4+Math.toRadians(y)/2))}; }
    public static double[] unproject(double x,double y) { return new double[]{Math.toDegrees(x/6378137),Math.toDegrees(2*Math.atan(Math.exp(y/6378137))-Math.PI/2)}; }
    public static double distance(double x,double y,double x2,double y2) { double a=Math.pow(Math.sin(Math.toRadians(y2-y)/2),2)+Math.cos(Math.toRadians(y))*Math.cos(Math.toRadians(y2))*Math.pow(Math.sin(Math.toRadians(x2-x)/2),2); return 6371000*2*Math.asin(Math.sqrt(Math.min(1,a))); }
    public List<Feature> nearby(double lon,double lat,int limit) {
        List<Feature> list=new ArrayList<>(); for(Feature f:features) if(f.kind.equals("poi")&&!f.name.isEmpty()) list.add(f);
        list.sort(Comparator.comparingDouble(f->distance(lon,lat,f.lon,f.lat))); return new ArrayList<>(list.subList(0,Math.min(Math.max(0,limit),list.size())));
    }
}
