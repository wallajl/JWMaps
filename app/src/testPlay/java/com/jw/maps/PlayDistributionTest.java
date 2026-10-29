package com.jw.maps;

import android.content.Context;
import android.content.pm.PackageManager;
import androidx.test.core.app.ApplicationProvider;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class PlayDistributionTest {
    @Test public void playBuildDoesNotRequestExternalPackageInstallation() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        String[] permissions=context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_PERMISSIONS).requestedPermissions;
        assertFalse(permissions!=null && Arrays.asList(permissions).contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        assertEquals(Boolean.FALSE,BuildConfig.class.getField("EXTERNAL_APK_UPDATES").get(null));
    }
}
