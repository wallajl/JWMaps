package com.jw.maps;
import android.content.Context;
import android.graphics.*;
import android.view.*;
final class MapView extends View {
    final MapRenderer renderer=new MapRenderer();
    Runnable changed=this::invalidate;
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    MapView(Context context) {
        super(context);setContentDescription("Map explorer. Drag to pan and pause GPS follow. Pinch or use zoom buttons to zoom.");setFocusable(true);
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){ @Override public boolean onScale(ScaleGestureDetector d){renderer.zoom(d.getScaleFactor());invalidate();return true;}});
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onScroll(MotionEvent first,MotionEvent now,float dx,float dy){if(!scale.isInProgress()){renderer.pan(-dx,-dy);invalidate();}return true;}
            @Override public boolean onSingleTapUp(MotionEvent e){performClick();return true;}
            @Override public boolean onDoubleTap(MotionEvent e){renderer.zoom(1.6);invalidate();return true;}
        });
    }
    private final Rect bounds=new Rect();
    @Override protected void onDraw(Canvas c){super.onDraw(c);((MapsApplication)getContext().getApplicationContext()).requestRegion(renderer,changed);bounds.set(0,0,getWidth(),getHeight());renderer.draw(c,bounds,true,getResources().getDisplayMetrics().density);}
    @Override protected void onDetachedFromWindow(){((MapsApplication)getContext().getApplicationContext()).forgetRegion(renderer);super.onDetachedFromWindow();}
    @android.annotation.SuppressLint("ClickableViewAccessibility") // GestureDetector dispatches single taps to the performClick override.
    @Override public boolean onTouchEvent(MotionEvent e){scale.onTouchEvent(e);gestures.onTouchEvent(e);return true;}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onKeyDown(int code,KeyEvent event){switch(code){case KeyEvent.KEYCODE_PLUS:renderer.zoom(1.4);break;case KeyEvent.KEYCODE_MINUS:renderer.zoom(1/1.4);break;case KeyEvent.KEYCODE_DPAD_LEFT:renderer.pan(60,0);break;case KeyEvent.KEYCODE_DPAD_RIGHT:renderer.pan(-60,0);break;case KeyEvent.KEYCODE_DPAD_UP:renderer.pan(0,60);break;case KeyEvent.KEYCODE_DPAD_DOWN:renderer.pan(0,-60);break;default:return super.onKeyDown(code,event);}invalidate();return true;}
}
