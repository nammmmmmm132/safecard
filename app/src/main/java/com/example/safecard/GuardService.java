package com.example.safecard;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dịch vụ nền:
 *  1) đếm bật/tắt màn hình (bấm nguồn 5 lần) -> đếm ngược SOS
 *  2) tùy chọn: lắc 3 lần, phát hiện té ngã -> đếm ngược SOS
 *  3) nghe hộp thư cảnh báo (alertsInbox): người thân / tình nguyện viên nhận SOS
 *  4) nghe nút "Báo người thân" từ trang thẻ QR công khai
 */
public class GuardService extends Service {
    private static final int NOTIF_ID = 1001;
    private static final int COUNTDOWN_NOTIF_ID = 2002;

    private final List<Long> times = new ArrayList<>();
    private final List<Long> shakes = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> shown = new HashSet<>();
    private final Set<String> pinged = new HashSet<>();
    private DatabaseReference inboxRef, tokRef, pingRef;
    private ChildEventListener inboxListener, pingListener;
    private ValueEventListener tokListener;
    private SensorManager sensorMgr;
    private boolean optShake, optFall;
    private long freefallAt = 0, lastMotionAt = 0, lastShakeAt = 0;

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { onScreenEvent(); }
    };

    private final SensorEventListener motion = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            double mag = Math.sqrt(x * x + y * y + z * z);
            long now = SystemClock.elapsedRealtime();
            if (Math.abs(mag - 9.8) > 2.5) lastMotionAt = now;
            if (optShake && mag > 24 && now - lastShakeAt > 300) {
                lastShakeAt = now;
                shakes.add(now);
                while (!shakes.isEmpty() && now - shakes.get(0) > 2500) shakes.remove(0);
                if (shakes.size() >= 3) { shakes.clear(); triggerCountdown("shake"); }
            }
            if (optFall) {
                if (mag < 2.5) freefallAt = now;                       // rơi tự do
                else if (mag > 28 && freefallAt > 0 && now - freefallAt < 800) {   // va chạm ngay sau đó
                    freefallAt = 0;
                    handler.postDelayed(new Runnable() {
                        @Override public void run() {
                            // nằm yên sau va chạm -> đếm ngược
                            if (SystemClock.elapsedRealtime() - lastMotionAt >= 1800) triggerCountdown("fall");
                        }
                    }, 2500);
                }
            }
        }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Notification n = buildPersistent();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        ContextCompat.registerReceiver(this, screenRx, f, ContextCompat.RECEIVER_NOT_EXPORTED);

        optShake = LocalCache.getBool(this, "optShake", false);
        optFall = LocalCache.getBool(this, "optFall", false);
        if (optShake || optFall) {
            sensorMgr = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            Sensor acc = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) sensorMgr.registerListener(motion, acc, SensorManager.SENSOR_DELAY_GAME);
        }

        CacheSync.start(this);
        SosManager.resumeTrackingIfActive(this);
        listenInbox();
        listenQrPings();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(screenRx); } catch (Exception ignored) { }
        if (sensorMgr != null) sensorMgr.unregisterListener(motion);
        if (inboxRef != null && inboxListener != null) inboxRef.removeEventListener(inboxListener);
        if (pingRef != null && pingListener != null) pingRef.removeEventListener(pingListener);
        if (tokRef != null && tokListener != null) tokRef.removeEventListener(tokListener);
        super.onDestroy();
    }

    // ---------- thông báo thường trực (hiện cả trên màn hình khóa) ----------
    private Notification buildPersistent() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent sos = new Intent(this, SosActivity.class)
                .putExtra(SosActivity.EXTRA_TRIGGER, "notification")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent sosPi = PendingIntent.getActivity(this, 1, sos, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action.Builder ab = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), "SOS", sosPi);
        if (Build.VERSION.SDK_INT >= 31) ab.setAuthenticationRequired(false);

        return new Notification.Builder(this, Notif.CH_GUARD)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("SafeCard đang bảo vệ bạn")
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openPi)
                .addAction(ab.build())
                .build();
    }

    // ---------- đếm bấm nguồn ----------
    private void onScreenEvent() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        long now = SystemClock.elapsedRealtime();
        times.add(now);
        while (!times.isEmpty() && now - times.get(0) > Config.POWER_WINDOW_MS) times.remove(0);
        if (times.size() == 3) Haptics.vibrate(this, 80);
        if (times.size() >= Config.POWER_PRESS_COUNT) {
            times.clear();
            triggerCountdown("power5");
        }
    }

    /** Đếm ngược 10 giây (bấm nguồn / lắc / té ngã). Hết giờ vẫn gửi dù giao diện không hiện được. */
    private void triggerCountdown(final String trigger) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        if (!SosManager.beginPending(this)) return;
        Haptics.pattern(this, new long[]{0, 200, 100, 200});

        Intent i = new Intent(this, SosActivity.class)
                .putExtra(SosActivity.EXTRA_MODE, SosActivity.MODE_COUNTDOWN)
                .putExtra(SosActivity.EXTRA_TRIGGER, trigger)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, Notif.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("SOS sắp được gửi")
                .setContentText("Chạm để nhập mã hủy trong " + Config.COUNTDOWN_SEC + " giây")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(COUNTDOWN_NOTIF_ID, n);
        try { startActivity(i); } catch (Exception ignored) { }

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                nm.cancel(COUNTDOWN_NOTIF_ID);
                SosManager.endPendingAndSend(GuardService.this, trigger);
            }
        }, (Config.COUNTDOWN_SEC + 1) * 1000L);
    }

    // ---------- nhận cảnh báo ----------
    private void listenInbox() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        final String uid = u.getUid();
        inboxRef = FirebaseDatabase.getInstance().getReference().child("alertsInbox").child(uid);
        inboxListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot s, String prev) {
                String status = s.child("status").getValue(String.class);
                Long started = s.child("startedAt").getValue(Long.class);
                if (!"sent".equals(status)) return;
                if (started != null && System.currentTimeMillis() - started > 2L * 3600 * 1000) return;
                String eventId = s.getKey();
                if (!shown.add(eventId)) return;
                String type = s.child("type").getValue(String.class);
                if (type == null) type = "relative";
                if (type.equals("volunteer") && (LevelManager.level(GuardService.this) != 1
                        || !LocalCache.getBool(GuardService.this, "responderOn", false))) return;
                Long dist = s.child("distanceM").getValue(Long.class);
                String from = s.child("fromName").getValue(String.class);
                showIncoming(eventId, type, dist == null ? -1 : dist, from == null ? "Người thân" : from);
                markReceived(eventId, uid);
            }
            @Override public void onChildChanged(DataSnapshot s, String prev) {
                if ("resolved".equals(s.child("status").getValue(String.class)))
                    getSystemService(NotificationManager.class).cancel(s.getKey().hashCode());
            }
            @Override public void onChildRemoved(DataSnapshot s) { }
            @Override public void onChildMoved(DataSnapshot s, String prev) { }
            @Override public void onCancelled(DatabaseError e) { }
        };
        inboxRef.addChildEventListener(inboxListener);
    }

    private void markReceived(String eventId, String uid) {
        FirebaseDatabase.getInstance().getReference().child("sosEvents").child(eventId)
                .child("alerts").child(uid).child("status")
                .runTransaction(new Transaction.Handler() {
                    @Override public Transaction.Result doTransaction(MutableData d) {
                        String s = d.getValue(String.class);
                        if (s == null || "sent".equals(s)) d.setValue("received");
                        return Transaction.success(d);
                    }
                    @Override public void onComplete(DatabaseError e, boolean committed, DataSnapshot snap) { }
                });
    }

    private void showIncoming(String eventId, String type, long dist, String fromName) {
        Intent i = new Intent(this, AlertActivity.class)
                .putExtra(AlertActivity.EXTRA_EVENT, eventId)
                .putExtra(AlertActivity.EXTRA_TYPE, type)
                .putExtra(AlertActivity.EXTRA_DIST, dist < 0 ? null : String.valueOf(dist))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, eventId.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean vol = type.equals("volunteer");
        Notification n = new Notification.Builder(this, Notif.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(vol ? "🆘 Có người cần giúp cách bạn ~" + dist + " m" : "🚨 " + fromName + " đang cần giúp đỡ!")
                .setContentText(vol ? "Chạm để xem. Chỉ nhận nếu bạn an toàn." : "Chạm để xem vị trí và phản hồi")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(eventId.hashCode(), n);
        Haptics.pattern(this, new long[]{0, 600, 300, 600, 300, 600});
    }

    // ---------- nút "Báo người thân" từ trang thẻ QR ----------
    private void listenQrPings() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        final DatabaseReference db = FirebaseDatabase.getInstance().getReference();
        tokRef = db.child("users").child(u.getUid()).child("qrToken");
        tokListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                if (pingRef != null && pingListener != null) pingRef.removeEventListener(pingListener);
                String t = s.getValue(String.class);
                if (t == null || t.isEmpty()) return;
                pingRef = db.child("qrPings").child(t);
                pingListener = new ChildEventListener() {
                    @Override public void onChildAdded(DataSnapshot p, String prev) {
                        Long at = p.child("at").getValue(Long.class);
                        if (at == null || System.currentTimeMillis() - at > 120000) return;
                        if (!pinged.add(p.getKey())) return;
                        Notif.info(GuardService.this, "Có người báo giúp bạn qua mã QR",
                                "Ai đó quét thẻ QR của bạn và bấm \"Báo người thân\". SafeCard đang gửi SOS cho người thân.", 3010);
                        SosManager.send(GuardService.this, "qr_ping");
                    }
                    @Override public void onChildChanged(DataSnapshot p, String prev) { }
                    @Override public void onChildRemoved(DataSnapshot p) { }
                    @Override public void onChildMoved(DataSnapshot p, String prev) { }
                    @Override public void onCancelled(DatabaseError e) { }
                };
                pingRef.addChildEventListener(pingListener);
            }
            @Override public void onCancelled(DatabaseError e) { }
        };
        tokRef.addValueEventListener(tokListener);
    }
}
