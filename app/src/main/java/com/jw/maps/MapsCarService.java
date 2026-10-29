package com.jw.maps;
import android.content.Intent;
import android.content.res.Configuration;
import androidx.annotation.NonNull;
import androidx.car.app.*;
import androidx.car.app.validation.HostValidator;

public final class MapsCarService extends CarAppService {
    @android.annotation.SuppressLint("PrivateResource") // This pinned library's documented default host list is explicitly recommended by CarAppService.
    @NonNull @Override public HostValidator createHostValidator() {
        // Development only, as recommended by the Car App Library documentation.
        if(BuildConfig.DEBUG)return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        return new HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build();
    }
    @NonNull @Override public Session onCreateSession(){return new Session(){
        private MapsCarScreen screen;
        @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent){screen=new MapsCarScreen(getCarContext());return screen;}
        @Override public void onCarConfigurationChanged(@NonNull Configuration configuration){if(screen!=null)screen.redraw();}
    };}
}
