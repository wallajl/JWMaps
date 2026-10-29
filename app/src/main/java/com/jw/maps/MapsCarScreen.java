package com.jw.maps;

import android.graphics.*;
import android.location.Location;
import android.os.*;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.car.app.*;
import androidx.car.app.model.*;
import androidx.car.app.navigation.model.*;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.*;
import com.jw.maps.core.MapPack;

public final class MapsCarScreen extends Screen implements DefaultLifecycleObserver,SurfaceCallback {
    final MapRenderer renderer=new MapRenderer();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LocationClient location;
    private Surface surface;
    private final Rect visible=new Rect(),stable=new Rect();
    private int width,height,dpi=160;
    private boolean started,destroyed;
    private final Runnable tick=new Runnable(){public void run(){if(started){refreshPack();redraw();invalidate();main.postDelayed(this,30000);}}};
    public MapsCarScreen(CarContext context){
        super(context);location=new LocationClient(context,this::redraw);getLifecycle().addObserver(this);
    }
    @Override public void onStart(@NonNull LifecycleOwner owner){
        started=true;getCarContext().getCarService(AppManager.class).setSurfaceCallback(this);location.start();refreshPack();main.post(tick);
    }
    private void refreshPack(){
        MapsApplication app=(MapsApplication)getCarContext().getApplicationContext();
        app.io.execute(()->{MapPack p=app.loadPack();main.post(()->{if(!destroyed){if(renderer.pack()==null||!renderer.pack().id.equals("south-australia"))renderer.setPack(p);redraw();if(started)invalidate();}});});
    }
    @Override public void onStop(@NonNull LifecycleOwner owner){started=false;main.removeCallbacks(tick);location.stop();getCarContext().getCarService(AppManager.class).setSurfaceCallback(null);release();}
    @Override public void onDestroy(@NonNull LifecycleOwner owner){destroyed=true;started=false;main.removeCallbacksAndMessages(null);location.stop();((MapsApplication)getCarContext().getApplicationContext()).forgetRegion(renderer);release();}
    @NonNull @Override public Template onGetTemplate(){
        MapPack p=renderer.pack();Location fix=location.fix();boolean live=fix!=null&&p!=null&&p.covers(fix.getLongitude(),fix.getLatitude());
        MapsApplication app=(MapsApplication)getCarContext().getApplicationContext();boolean regional=p!=null&&p.id.equals("south-australia");
        ItemList.Builder list=new ItemList.Builder().setNoItemsMessage("No named places in this loaded window");
        String status=app.hasRegion()?(live?"South Australia · Nearby places":"South Australia · Local window"):p==null?"Loading Adelaide preview":live?"Nearby places":fix!=null?"Outside coverage · Adelaide preview":"Adelaide city-centre preview";
        if(p!=null){
            double lon=live?fix.getLongitude():(p.bbox[0]+p.bbox[2])/2,lat=live?fix.getLatitude():(p.bbox[1]+p.bbox[3])/2;
            for(MapPack.Feature f:p.nearby(lon,lat,5))list.addItem(new Row.Builder().setTitle(f.name).addText(MainActivity.distance(MapPack.distance(lon,lat,f.lon,f.lat))+(live?" away":regional?" from window centre":" from preview centre")).setOnClickListener(()->{renderer.center(f.lon,f.lat);redraw();}).build());
        }
        list.addItem(new Row.Builder().setTitle("© OpenStreetMap contributors · ODbL").addText(app.hasRegion()?app.regionStatus(renderer):live?"Explorer only · no route guidance":"Set up location on phone while parked").addText("North up · Dashed line is the local window boundary").build());
        ListTemplate content=new ListTemplate.Builder().setSingleList(list.build()).setHeader(new Header.Builder().setTitle(status).setStartHeaderAction(Action.APP_ICON).build()).build();
        ActionStrip mapActions=new ActionStrip.Builder().addAction(Action.PAN).build();
        ActionStrip actions=new ActionStrip.Builder()
                .addAction(new Action.Builder().setTitle("Zoom in").setOnClickListener(()->{renderer.zoom(1.5);redraw();}).build())
                .addAction(new Action.Builder().setTitle("Zoom out").setOnClickListener(()->{renderer.zoom(1/1.5);redraw();}).build())
                .addAction(new Action.Builder().setTitle("Follow me").setOnClickListener(()->{renderer.recenter();redraw();}).build()).build();
        return new MapWithContentTemplate.Builder().setContentTemplate(content).setActionStrip(actions).setMapController(new MapController.Builder().setMapActionStrip(mapActions).build()).build();
    }
    void redraw(){
        if(destroyed)return;
        renderer.setLocation(location.fix());((MapsApplication)getCarContext().getApplicationContext()).requestRegion(renderer,()->{if(!destroyed){redraw();if(started)invalidate();}});
        if(!started||surface==null||!surface.isValid())return;
        Canvas canvas=null;
        try{canvas=surface.lockCanvas(null);renderer.draw(canvas,viewport(),getCarContext().isDarkMode(),dpi/160f);}
        catch(IllegalArgumentException|IllegalStateException ignored){/* Host can destroy its surface between callbacks. */}
        finally{if(canvas!=null&&surface!=null)try{surface.unlockCanvasAndPost(canvas);}catch(IllegalArgumentException|IllegalStateException ignored){}}
    }
    Rect viewport(){
        Rect full=new Rect(0,0,width,height),r=new Rect(full);
        if(!stable.isEmpty()&&!r.intersect(stable))return new Rect();
        if(!visible.isEmpty()&&!r.intersect(visible))return new Rect();
        return r;
    }
    @Override public void onSurfaceAvailable(@NonNull SurfaceContainer container){Surface next=container.getSurface();if(surface!=next)release();surface=next;width=container.getWidth();height=container.getHeight();dpi=container.getDpi();redraw();}
    @Override public void onVisibleAreaChanged(@NonNull Rect r){visible.set(r);redraw();}
    @Override public void onStableAreaChanged(@NonNull Rect r){stable.set(r);redraw();}
    @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer container){if(surface==container.getSurface())release();else if(container.getSurface()!=null)container.getSurface().release();}
    @Override public void onScroll(float dx,float dy){renderer.pan(-dx,-dy);redraw();}
    @Override public void onScale(float x,float y,float factor){if(Float.isFinite(factor)&&factor>0)renderer.zoom(factor);redraw();}
    private void release(){if(surface!=null){surface.release();surface=null;}visible.setEmpty();stable.setEmpty();}
}
