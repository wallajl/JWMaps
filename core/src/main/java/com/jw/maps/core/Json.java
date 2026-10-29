package com.jw.maps.core;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;

/** Bounded strict JSON with duplicate-key rejection and lexical integer checks. */
public final class Json {
    private Json() {}
    public static JsonObject object(byte[] bytes, int max) throws IOException {
        if(bytes.length==0 || bytes.length>max) throw new IOException("JSON size limit");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try(JsonReader r=new JsonReader(new StringReader(text))) {
            r.setStrictness(Strictness.STRICT);
            JsonElement e=read(r,0,new int[]{0});
            if(r.peek()!=JsonToken.END_DOCUMENT || !e.isJsonObject()) throw new IOException("Expected JSON object");
            return e.getAsJsonObject();
        } catch(IllegalStateException|NumberFormatException e) { throw new IOException("Invalid JSON",e); }
    }
    private static JsonElement read(JsonReader r,int depth,int[] nodes) throws IOException {
        if(depth>24 || ++nodes[0]>2000000) throw new IOException("JSON complexity limit");
        switch(r.peek()) {
            case BEGIN_OBJECT: {
                JsonObject o=new JsonObject(); r.beginObject();
                while(r.hasNext()) { String k=r.nextName(); if(o.has(k)) throw new IOException("Duplicate key"); o.add(k,read(r,depth+1,nodes)); }
                r.endObject(); return o;
            }
            case BEGIN_ARRAY: {
                JsonArray a=new JsonArray(); r.beginArray(); while(r.hasNext()) a.add(read(r,depth+1,nodes)); r.endArray(); return a;
            }
            case STRING: return new JsonPrimitive(r.nextString());
            case NUMBER: return JsonParser.parseString(r.nextString());
            case BOOLEAN: return new JsonPrimitive(r.nextBoolean());
            case NULL: r.nextNull(); return JsonNull.INSTANCE;
            default: throw new IOException("Invalid JSON token");
        }
    }
    public static JsonObject obj(JsonObject o,String k) throws IOException { JsonElement e=o.get(k); if(e==null||!e.isJsonObject()) throw new IOException("Missing object: "+k); return e.getAsJsonObject(); }
    public static String str(JsonObject o,String k,int max) throws IOException {
        JsonElement e=o.get(k); if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString()) throw new IOException("Missing string: "+k);
        String s=e.getAsString(); if(s.trim().isEmpty()||s.length()>max||s.chars().anyMatch(c->c<32)) throw new IOException("Invalid string: "+k); return s;
    }
    public static long integer(JsonObject o,String k) throws IOException {
        JsonElement e=o.get(k); if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()||!e.toString().matches("[1-9][0-9]{0,17}")) throw new IOException("Expected positive integer: "+k);
        try { return Long.parseLong(e.toString()); } catch(NumberFormatException ex) { throw new IOException("Integer overflow",ex); }
    }
}
