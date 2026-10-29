package com.jw.maps;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.jw.maps.core.*;
import java.util.Locale;

public final class MainActivity extends Activity {
    public static final int MAP_ID=1001,STATUS_ID=1002;
    private static final int WHITE=0xfff8fafc,MUTED=0xff94a3b8,CYAN=0xff22d3ee;
    private MapView map;
    private TextView status,coords;
    private LinearLayout content;
    private LocationClient location;
    private String page="Places";
    private boolean active,checking;
    private String panelKey="";
    private final java.util.List<Button> tabButtons=new java.util.ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){if(active){refresh();handler.postDelayed(this,5000);}}};
    private MapsApplication app(){return (MapsApplication)getApplication();}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved); if(saved!=null)page=saved.getString("page","Places");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff070a0f);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout title=new LinearLayout(this);title.setPadding(dp(18),dp(10),dp(18),dp(8));title.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("JWMaps",24,WHITE);brand.setTypeface(null,Typeface.BOLD);title.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button locate=button("My location",this::enableLocation);title.addView(locate);root.addView(title);
        status=text("Adelaide city-centre preview · location not acquired",14,MUTED);status.setId(STATUS_ID);status.setPadding(dp(18),0,dp(18),dp(6));root.addView(status);
        coords=text("Preview, not your GPS position",12,MUTED);coords.setPadding(dp(18),0,dp(18),dp(8));root.addView(coords);
        FrameLayout frame=new FrameLayout(this);map=new MapView(this);map.changed=this::refresh;map.setId(MAP_ID);frame.addView(map,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setBackground(card());
        controls.addView(button("+",()->{map.renderer.zoom(1.5);map.invalidate();}));controls.getChildAt(0).setContentDescription("Zoom in");
        controls.addView(button("−",()->{map.renderer.zoom(1/1.5);map.invalidate();}));controls.getChildAt(1).setContentDescription("Zoom out");
        controls.addView(button("Follow me",()->{map.renderer.recenter();map.invalidate();}));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-2,-2,Gravity.END|Gravity.TOP);cp.setMargins(dp(12),dp(12),dp(12),dp(12));frame.addView(controls,cp);
        root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout tabs=new LinearLayout(this);tabs.setBackground(card());
        for(String name:new String[]{"Places","Updates","Info"}){Button b=button(name,()->{page=name;refreshPanel();});tabButtons.add(b);tabs.addView(b,new LinearLayout.LayoutParams(0,-2,1));}root.addView(tabs);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),dp(10),dp(18),dp(18));scroll.addView(content);
        int panelHeight=Math.min(240,Math.max(140,getResources().getConfiguration().screenHeightDp/3));root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(panelHeight)));
        setContentView(root);root.requestApplyInsets();location=new LocationClient(this,this::refresh);
        refreshPanel();app().io.execute(()->{MapPack p=app().loadPack();runOnUiThread(()->{if(!isDestroyed()){if(map.renderer.pack()==null||!map.renderer.pack().id.equals("south-australia"))map.renderer.setPack(p);refresh();}});});
    }
    @Override protected void onStart(){super.onStart();active=true;location.start();handler.post(tick);Updates.startup(this);}
    @Override protected void onStop(){active=false;handler.removeCallbacks(tick);location.stop();super.onStop();}
    @Override protected void onResume(){super.onResume();if(location!=null){location.stop();location.start();}if(map!=null)refresh();}
    @Override protected void onSaveInstanceState(Bundle b){b.putString("page",page);super.onSaveInstanceState(b);}
    private void enableLocation(){
        if(!LocationService.permitted(this)){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},12);return;}
        android.location.LocationManager manager=getSystemService(android.location.LocationManager.class);
        if(!manager.isLocationEnabled()){new AlertDialog.Builder(this).setTitle("Device location is off").setMessage("Enable device location to show your position. You can explore installed maps without it.").setPositiveButton("Location settings",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).setNegativeButton("Keep exploring",null).show();return;}
        location.stop();location.start();map.renderer.recenter();map.invalidate();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==12){location.stop();location.start();refresh();if(!LocationService.permitted(this))new AlertDialog.Builder(this).setTitle("Location is denied").setMessage("You can still explore installed maps. To show your position later, allow location in Android app settings.").setPositiveButton("Keep exploring",null).show();}}
    @android.annotation.SuppressLint("SetTextI18n") // First release is English-only, with local WGS84 coordinate formatting.
    private void refresh(){
        if(map==null||location==null)return;MapPack loaded=app().currentPack();if(loaded!=null&&(map.renderer.pack()==null||!map.renderer.pack().id.equals("south-australia")))map.renderer.setPack(loaded);Location fix=location.fix();map.renderer.setLocation(fix);app().requestRegion(map.renderer,this::refresh);map.invalidate();MapPack p=map.renderer.pack();
        String state=location.status()+" · "+(app().hasRegion()?app().regionStatus(map.renderer):"Adelaide city-centre preview");
        if(fix!=null){boolean covered=p!=null&&p.covers(fix.getLongitude(),fix.getLatitude());if(!covered&&!app().hasRegion())state="Outside map coverage · Adelaide city-centre preview";
            coords.setText(String.format(Locale.ROOT,"%.5f, %.5f · %s",fix.getLatitude(),fix.getLongitude(),fix.hasBearing()?String.format(Locale.ROOT,"heading %.0f°",fix.getBearing()):"heading unavailable"));
        }else coords.setText("Map view, not your GPS position · No trip history saved");

        if(p==null)state="Loading map · "+app().regionStatus(map.renderer);status.setText(state);
        String nextKey=page+":"+(p==null?0:System.identityHashCode(p))+":"+(fix==null?"preview":Math.round(fix.getLongitude()*10000)+","+Math.round(fix.getLatitude()*10000))+":"+getSharedPreferences(Updates.PREFS,0).getString("status","")+":"+app().regionStatus()+":"+app().regionStatus(map.renderer);
        if(!panelKey.equals(nextKey)){panelKey=nextKey;refreshPanel();}
    }
    private void refreshPanel(){
        if(content==null)return;for(Button b:tabButtons)b.setSelected(b.getText().toString().equals(page));content.removeAllViews();MapPack p=map==null?null:map.renderer.pack();
        switch(page){
            case "Updates":
                heading("App & map updates");line("JWMaps "+BuildConfig.VERSION_NAME+" · "+(p==null?"No map loaded":p.name+" v"+p.version));
                line(app().regionStatus());
                if(!app().hasRegion()){Button download=button(checking?"Checking…":"Download South Australia",this::downloadSouthAustralia);download.setEnabled(!checking);content.addView(download);}
                line("South Australia uses local 3×3 tile windows from the installed OSM extract. Rural tiles may contain no mapped features. OSM detail and extract boundaries limit coverage.");
                line(getSharedPreferences(Updates.PREFS,0).getString("status","Checks use public GitHub releases. No account or location upload."));
                Button check=button(checking?"Checking…":"Check for updates",this::checkUpdates);check.setEnabled(!checking);content.addView(check);
                if(BuildConfig.EXTERNAL_APK_UPDATES && Updates.apk(this).exists())content.addView(button("Install verified update",this::install));
                line(BuildConfig.EXTERNAL_APK_UPDATES?"Automatic checks run about every 12 hours when connected. Android battery policies can delay them. Installation always needs your approval on this phone.":"Maps update from GitHub. App installation and app updates are managed by Google Play. Background map checks run about every 12 hours when connected.");break;
            case "Info":
                heading("Real places. A block-style view.");
                line("First release: map explorer and nearby places, not turn-by-turn navigation. No routes, traffic or worldwide downloads.");
                if(p!=null)line(String.format(Locale.ROOT,"Loaded bounds: %.4f, %.4f to %.4f, %.4f. Dashed cyan line marks the %s.",p.bbox[1],p.bbox[0],p.bbox[3],p.bbox[2],p.id.equals("south-australia")?"local window boundary, not statewide coverage":"CBD preview boundary"));
                line("Map stays north up. The position marker shows bearing when available. Dragging pauses GPS follow; Follow me resumes it.");
                line("Buildings use OSM geometry. Missing building heights use an 8 m visual default, not a measured height. Coordinates and heading stay on this device.");
                line("Android Auto requires a compatible trusted host and distribution channel. Sideloading this APK does not guarantee it appears in the car launcher. No physical car or live GPS test is claimed.");
                line("Release hosts are validated. Development builds allow test hosts; release builds use AndroidX's signed host allowlist and privileged template hosts.");
                line("© OpenStreetMap contributors · Open Database License 1.0. "+(p==null?"":p.attribution));
                content.addView(button("OpenStreetMap & data licence",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")))));break;
            default:
                Location fix=location==null?null:location.fix();boolean live=fix!=null&&p!=null&&p.covers(fix.getLongitude(),fix.getLatitude());
                boolean regional=p!=null&&p.id.equals("south-australia");heading(live?"Nearby places":regional?"Places in this local window":"Places in the Adelaide preview");
                if(p==null){line("Loading the bundled map pack…");break;}
                if(fix!=null&&!live){line(regional?"Your position is outside the loaded window. These places are near the window centre, not near you.":"You are outside this preview. These places are near the preview centre, not near you.");}
                double lon=live?fix.getLongitude():(p.bbox[0]+p.bbox[2])/2,lat=live?fix.getLatitude():(p.bbox[1]+p.bbox[3])/2;
                java.util.List<MapPack.Feature> places=p.nearby(lon,lat,12);
                if(places.isEmpty())line("No named places in this loaded window.");
                for(MapPack.Feature f:places){Button b=button(f.name+" · "+distance(MapPack.distance(lon,lat,f.lon,f.lat))+(live?" away":regional?" from window centre":" from preview centre"),()->{map.renderer.center(f.lon,f.lat);map.invalidate();});b.setAllCaps(false);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);content.addView(b);}
        }
    }
    private void downloadSouthAustralia(){new AlertDialog.Builder(this).setTitle("Download South Australia?").setMessage("The full offline map can use several hundred MB of mobile data. Wi-Fi is recommended. Your existing map stays available until the download is verified.").setPositiveButton("Download",(d,w)->checkUpdates(true)).setNegativeButton("Cancel",null).show();}
    private void checkUpdates(){checkUpdates(false);}
    private void checkUpdates(boolean allowMetered){checking=true;refreshPanel();app().io.execute(()->{Updates.check(this,allowMetered);MapPack p=app().loadPack();runOnUiThread(()->{checking=false;if(!isDestroyed()){if(map.renderer.pack()==null||!map.renderer.pack().id.equals("south-australia"))map.renderer.setPack(p);refresh();}});});}
    private void install(){
        if(!BuildConfig.EXTERNAL_APK_UPDATES)return;
        app().io.execute(()->{try{UpdateManifest manifest=Updates.staged(this);runOnUiThread(()->{if(isDestroyed())return;
            if(!getPackageManager().canRequestPackageInstalls())new AlertDialog.Builder(this).setTitle("Allow APK installation").setMessage("Android needs your approval to install updates from JWMaps. Allow this source, return here, then tap Install verified update again.").setPositiveButton("Review Android consent",(d,w)->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())))).setNegativeButton("Cancel",null).show();
            else new AlertDialog.Builder(this).setTitle("Install JWMaps "+manifest.versionName+"?").setMessage("The signed release, file hash, package, version and installed signing identity were checked. Android will ask you to approve installation.").setPositiveButton("Open installer",(d,w)->{try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",Updates.apk(this));startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(ActivityNotFoundException e){Toast.makeText(this,"No Android package installer is available",Toast.LENGTH_LONG).show();}}).setNegativeButton("Cancel",null).show();
        });}catch(Exception e){runOnUiThread(()->{if(!isDestroyed())new AlertDialog.Builder(this).setTitle("Update cannot be installed").setMessage(e.getMessage()).setPositiveButton("Close",null).show();});}});
    }
    static String distance(double metres){return metres<1000?String.format(Locale.ROOT,"%.0f m",metres):String.format(Locale.ROOT,"%.1f km",metres/1000);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private void heading(String s){TextView t=text(s,18,WHITE);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,0,0,dp(8));content.addView(t);}
    private void line(String s){TextView t=text(s,14,MUTED);t.setPadding(0,0,0,dp(10));content.addView(t);}
    private Button button(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setTextColor(WHITE);b.setAllCaps(false);b.setMinHeight(dp(48));b.setPadding(dp(12),dp(4),dp(12),dp(4));b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x5022d3ee),card(),null));b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_selected},new int[]{}},new int[]{CYAN,WHITE}));b.setOnClickListener(v->r.run());return b;}
    private GradientDrawable card(){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xf01e293b,0xf0111827});d.setCornerRadius(dp(18));d.setStroke(dp(1),0xff334155);return d;}
}
