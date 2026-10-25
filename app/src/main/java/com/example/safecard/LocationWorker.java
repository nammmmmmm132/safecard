package com.example.safecard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.firebase.geofire.GeoFire;
import com.firebase.geofire.GeoLocation;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Cập nhật vị trí thô của tình nguyện viên (GeoFire) khoảng 15 phút một lần. */
public class LocationWorker extends Worker {
    public LocationWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    @SuppressLint("MissingPermission")
    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null || !LocalCache.getBool(ctx, "responderOn", false)) return Result.success();
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return Result.success();
        try {
            FusedLocationProviderClient f = LocationServices.getFusedLocationProviderClient(ctx);
            CancellationTokenSource cts = new CancellationTokenSource();
            Location l = Tasks.await(f.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.getToken()), 20, TimeUnit.SECONDS);
            if (l == null) l = Tasks.await(f.getLastLocation(), 5, TimeUnit.SECONDS);
            if (l == null) return Result.retry();
            boolean mock = Build.VERSION.SDK_INT >= 31 ? l.isMock() : l.isFromMockProvider();
            if (mock) return Result.success();   // chống GPS giả
            // vị trí "thô": làm tròn 3 chữ số thập phân (~100 m)
            double lat = Math.round(l.getLatitude() * 1000.0) / 1000.0;
            double lng = Math.round(l.getLongitude() * 1000.0) / 1000.0;
            DatabaseReference root = FirebaseDatabase.getInstance().getReference();
            final CountDownLatch latch = new CountDownLatch(1);
            new GeoFire(root.child("responders_geo")).setLocation(u.getUid(), new GeoLocation(lat, lng),
                    (key, err) -> latch.countDown());
            Map<String, Object> up = new HashMap<>();
            up.put("active", true);
            up.put("lastSeen", ServerValue.TIMESTAMP);
            root.child("responders").child(u.getUid()).updateChildren(up);
            latch.await(10, TimeUnit.SECONDS);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
