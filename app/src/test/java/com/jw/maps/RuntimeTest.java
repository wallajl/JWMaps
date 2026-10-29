package com.jw.maps;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import android.content.Intent;
import android.widget.TextView;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class RuntimeTest {
 @Test public void phoneRendersPreviewAndAttributionWithoutPermissionPrompt() {
   try(var c=Robolectric.buildActivity(MainActivity.class).setup()) {
     MainActivity a=c.get(); assertNotNull(a.findViewById(MainActivity.MAP_ID));
     assertTrue(((TextView)a.findViewById(MainActivity.STATUS_ID)).getText().toString().contains("preview"));
     assertEquals(0,org.robolectric.Shadows.shadowOf(a).getNextStartedActivity()==null?0:1);
   }
 }
 @Test public void carServiceUsesPoiMapTemplate() {
   var c=Robolectric.buildService(MapsCarService.class).create();
   try { assertNotNull(c.get().createHostValidator()); assertNotNull(c.get().onCreateSession()); if(!BuildConfig.DEBUG)assertNotSame(androidx.car.app.validation.HostValidator.ALLOW_ALL_HOSTS_VALIDATOR,c.get().createHostValidator()); }
   finally { c.destroy(); }
   androidx.car.app.testing.TestCarContext context=androidx.car.app.testing.TestCarContext.createCarContext(androidx.test.core.app.ApplicationProvider.getApplicationContext());
   MapsCarScreen screen=new MapsCarScreen(context);
   androidx.car.app.testing.ScreenController controller=new androidx.car.app.testing.ScreenController(screen);
   controller.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);
   assertTrue(screen.onGetTemplate() instanceof MapWithContentTemplate);
   assertNull(context.getLastPermissionRequestInfo());
   assertTrue(context.getStartCarAppIntents().isEmpty());
   screen.onSurfaceAvailable(new androidx.car.app.SurfaceContainer(null,1280,720,240));
   screen.onStableAreaChanged(new android.graphics.Rect(400,0,1280,700));
   screen.onVisibleAreaChanged(new android.graphics.Rect(350,30,1250,680));
   assertEquals(new android.graphics.Rect(400,30,1250,680),screen.viewport());
   screen.onVisibleAreaChanged(new android.graphics.Rect(0,0,100,100));assertTrue(screen.viewport().isEmpty());
   controller.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED);
 }
}
