package com.jw.maps;

import android.graphics.*;
import android.location.Location;
import com.jw.maps.core.MapPack;
import java.util.*;

/** Shared phone/car block renderer. Extrusion is a visual treatment, not measured elevation. */
final class MapRenderer {
    private static final int BG=Color.rgb(7,10,15),TEXT=Color.rgb(248,250,252),CYAN=Color.rgb(34,211,238);
    private MapPack pack;
    private final List<Shape> shapes=new ArrayList<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private double cx,cy,scale=0.13;
    private Location location;
    private boolean following,fitRequested;
    private final Path wall=new Path();
    private final Paint groundPaint=new Paint();
    private BitmapShader dayGrass,nightGrass;
    private static final class Label {
        final String text; final float x,y,size; final boolean centered;
        Label(String text,float x,float y,float size,boolean centered){this.text=text;this.x=x;this.y=y;this.size=size;this.centered=centered;}
    }
    private void terrain(Canvas canvas,Rect viewport,boolean dark,float density){
        BitmapShader shader=dark?nightGrass:dayGrass;
        if(shader==null){
            Bitmap tile=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
            for(int py=0;py<8;py++)for(int px=0;px<8;px++){
                int shade=((px*37+py*19)^(px*py*11))&7;
                tile.setPixel(px,py,dark?Color.rgb(20+shade,35+shade,23+shade):Color.rgb(101+shade,151+shade,64+shade));
            }
            shader=new BitmapShader(tile,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT);
            if(dark)nightGrass=shader;else dayGrass=shader;
        }
        Matrix matrix=new Matrix();matrix.setScale(3*density,3*density);shader.setLocalMatrix(matrix);
        groundPaint.setShader(shader);groundPaint.setFilterBitmap(false);canvas.drawRect(viewport,groundPaint);
    }
    private static final class Shape {
        final MapPack.Feature f; final List<double[][]> paths=new ArrayList<>();
        Shape(MapPack.Feature feature) {f=feature; for(double[][] points:f.paths) {double[][] p=new double[points.length][]; for(int i=0;i<p.length;i++) p[i]=MapPack.project(points[i][0],points[i][1]); paths.add(p);} }
    }
    synchronized void setPack(MapPack p) {
        if(pack==p) return; boolean first=pack==null; pack=p; shapes.clear(); if(p==null) return;
        for(MapPack.Feature f:p.features) shapes.add(new Shape(f));
        shapes.sort(Comparator.comparingInt(s->layer(s.f.kind)));
        if(first){preview();following=true;}
        setLocation(location);
    }
    private static int layer(String k) {switch(k) {case "park":return 0;case "water":return 1;case "building":return 2;case "road":return 3;default:return 4;}}
    synchronized MapPack pack() {return pack;}
    synchronized void preview() {if(pack!=null) center((pack.bbox[0]+pack.bbox[2])/2,(pack.bbox[1]+pack.bbox[3])/2);following=false;scale=.13;fitRequested=true;}
    private void moveCenter(double lon,double lat) {double[] p=MapPack.project(lon,lat);cx=p[0];cy=p[1];}
    synchronized void center(double lon,double lat) {moveCenter(lon,lat);following=false;fitRequested=false;}
    synchronized void recenter() {if(location!=null&&pack!=null&&pack.covers(location.getLongitude(),location.getLatitude())) {moveCenter(location.getLongitude(),location.getLatitude());following=true;fitRequested=false;} else {preview();following=true;}}
    synchronized boolean isFollowing() {return following;}
    synchronized void setLocation(Location l) {location=l==null?null:new Location(l);if(following&&location!=null&&pack!=null&&pack.covers(location.getLongitude(),location.getLatitude())) {moveCenter(location.getLongitude(),location.getLatitude());fitRequested=false;}}
    synchronized void pan(float dx,float dy) {cx-=dx/scale;cy+=dy/scale;following=false;}
    synchronized void zoom(double factor) {scale=Math.max(.025,Math.min(4,scale*factor));}
    synchronized double[] center() {return MapPack.unproject(cx,cy);}
    private float x(double x,Rect bounds) {return (float)((x-cx)*scale+bounds.exactCenterX());}
    private float y(double y,Rect bounds) {return (float)((cy-y)*scale+bounds.exactCenterY());}
    synchronized void draw(Canvas c,Rect viewport,boolean dark,float density) {
        c.drawColor(dark?BG:Color.rgb(231,238,242));
        if(viewport.isEmpty()) return;
        int save=c.save(); c.clipRect(viewport);
        if(pack==null) {text(c,"Map pack unavailable",viewport.left+20*density,viewport.top+48*density,18*density,dark?TEXT:Color.BLACK);c.restoreToCount(save);return;}
        if(fitRequested){double[] a=MapPack.project(pack.bbox[0],pack.bbox[1]),b=MapPack.project(pack.bbox[2],pack.bbox[3]);scale=Math.max(.025,Math.min(4,.88*Math.min(viewport.width()/(b[0]-a[0]),viewport.height()/(b[1]-a[1]))));fitRequested=false;}
        terrain(c,viewport,dark,density);
        List<Label> labels=new ArrayList<>();Set<Long> poiCells=new HashSet<>();
        for(Shape s:shapes) {
            double[][] first=s.paths.get(0);
            float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE;
            for(double[] p:first) {float px=x(p[0],viewport),py=y(p[1],viewport);minX=Math.min(minX,px);maxX=Math.max(maxX,px);minY=Math.min(minY,py);maxY=Math.max(maxY,py);}
            if(maxX<viewport.left-80||minX>viewport.right+80||maxY<viewport.top-80||minY>viewport.bottom+80) continue;
            String kind=s.f.kind; paint.setStyle(Paint.Style.FILL);
            if(kind.equals("poi")) {
                long cell=((long)Math.floor(minX/(24*density))<<32)^((long)Math.floor(minY/(24*density))&0xffffffffL);
                if(poiCells.size()>=80||!poiCells.add(cell))continue;
                paint.setColor(CYAN);c.drawRect(minX-3*density,minY-3*density,minX+3*density,minY+3*density,paint);
                if(scale>.35&&!s.f.name.isEmpty())labels.add(new Label(s.f.name,minX+8*density,minY-7*density,12*density,false));
                continue;
            }
            path.reset(); path.setFillType(Path.FillType.EVEN_ODD);
            float extrusion=kind.equals("building")?(float)Math.min(22*density,s.f.height*scale*.65):0;
            for(double[][] ring:s.paths) {
                for(int i=0;i<ring.length;i++) {float px=x(ring[i][0],viewport),py=y(ring[i][1],viewport);if(i==0)path.moveTo(px,py);else path.lineTo(px,py);}
                if(!kind.equals("road")) path.close();
            }
            switch(kind) {
                case "water": paint.setColor(dark?0xff1d5670:0xff278bd0);break;
                case "park": paint.setColor(dark?0xff244830:0xff558742);break;
                case "building": paint.setColor(dark?0xff766c55:0xffa18b68);break;
                default: paint.setColor(dark?0xff9ba38d:0xffd2ccb6);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth((float)Math.max(2*density,Math.min(10*density,12*scale)));paint.setStrokeJoin(Paint.Join.ROUND);break;
            }
            c.drawPath(path,paint);
            if(extrusion>1) {
                for(int i=1;i<first.length;i++) {
                    wall.reset();float ax=x(first[i-1][0],viewport),ay=y(first[i-1][1],viewport),bx=x(first[i][0],viewport),by=y(first[i][1],viewport);
                    wall.moveTo(ax,ay);wall.lineTo(bx,by);wall.lineTo(bx-extrusion*.45f,by-extrusion);wall.lineTo(ax-extrusion*.45f,ay-extrusion);wall.close();paint.setColor(dark?0xff645a46:0xff8c7759);c.drawPath(wall,paint);
                }
                path.offset(-extrusion*.45f,-extrusion);paint.setColor(dark?0xffa49875:0xffc9b48a);c.drawPath(path,paint);
                paint.setColor(dark?0xffc5b892:0xff6b604a);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(.7f*density);c.drawPath(path,paint);
            }
            if(kind.equals("road")&&!s.f.name.isEmpty()&&scale>.35&&maxX-minX>90*density)labels.add(new Label(s.f.name,(minX+maxX)/2,(minY+maxY)/2-5*density,13*density,true));
        }
        drawLabels(c,viewport,labels,dark,density);
        double[] lo=MapPack.project(pack.bbox[0],pack.bbox[1]),hi=MapPack.project(pack.bbox[2],pack.bbox[3]);
        paint.setStyle(Paint.Style.STROKE);paint.setColor(CYAN);paint.setStrokeWidth(density);paint.setPathEffect(new DashPathEffect(new float[]{8*density,6*density},0));
        c.drawRect(x(lo[0],viewport),y(hi[1],viewport),x(hi[0],viewport),y(lo[1],viewport),paint);paint.setPathEffect(null);
        if(location!=null&&pack.covers(location.getLongitude(),location.getLatitude())) {
            double[] p=MapPack.project(location.getLongitude(),location.getLatitude());float px=x(p[0],viewport),py=y(p[1],viewport);
            paint.setStyle(Paint.Style.FILL);paint.setColor(0x3022d3ee);c.drawCircle(px,py,18*density,paint);paint.setColor(CYAN);c.drawCircle(px,py,7*density,paint);
            if(location.hasBearing()) {c.save();c.rotate(location.getBearing(),px,py);paint.setStrokeWidth(3*density);c.drawLine(px,py,px,py-22*density,paint);c.restore();}
        }
        double[] viewCenter=center();if(!pack.covers(viewCenter[0],viewCenter[1]))text(c,"Outside map pack · use Centre",viewport.left+16*density,viewport.top+28*density,14*density,dark?TEXT:Color.BLACK);
        paint.setStyle(Paint.Style.FILL);paint.setColor(dark?0xee111827:0xeef8fafc);
        c.drawRoundRect(viewport.left+8*density,viewport.bottom-31*density,viewport.right-8*density,viewport.bottom-5*density,8*density,8*density,paint);
        text(c,"© OpenStreetMap contributors · ODbL",viewport.left+16*density,viewport.bottom-14*density,11*density,dark?TEXT:Color.BLACK);
        c.restoreToCount(save);
    }
    private void drawLabels(Canvas canvas,Rect viewport,List<Label> labels,boolean dark,float density){
        List<RectF> occupied=new ArrayList<>();
        for(Label label:labels){
            if(occupied.size()>=24)break;
            paint.setTextSize(label.size);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
            float width=paint.measureText(label.text),left=label.centered?label.x-width/2:label.x;
            RectF bounds=new RectF(left-4*density,label.y-label.size-3*density,left+width+4*density,label.y+5*density);
            if(bounds.left<viewport.left+8*density||bounds.right>viewport.right-8*density||bounds.top<viewport.top+8*density||bounds.bottom>viewport.bottom-36*density)continue;
            boolean collision=false;for(RectF previous:occupied)if(RectF.intersects(bounds,previous)){collision=true;break;}
            if(collision)continue;occupied.add(bounds);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3*density);paint.setColor(dark?0xff102016:0xfff8f1d5);canvas.drawText(label.text,left,label.y,paint);
            text(canvas,label.text,left,label.y,label.size,dark?TEXT:0xff192219);
        }
    }
    private void text(Canvas c,String s,float x,float y,float size,int color) {paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextSize(size);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));c.drawText(s,x,y,paint);}
}
