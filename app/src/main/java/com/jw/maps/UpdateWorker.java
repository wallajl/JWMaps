package com.jw.maps;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
public final class UpdateWorker extends Worker {
 public UpdateWorker(@NonNull Context c,@NonNull WorkerParameters p) {super(c,p);}
 @NonNull @Override public Result doWork() { Updates.check(getApplicationContext()); return Result.success(); }
}
