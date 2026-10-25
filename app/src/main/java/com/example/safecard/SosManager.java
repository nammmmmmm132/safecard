package com.example.safecard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.firebase.geofire.GeoFire;
import com.firebase.geofire.GeoLocation;
import com.firebase.geofire.GeoQuery;
import com.firebase.geofire.GeoQueryEventListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Toàn bộ logic gửi SOS: lấy GPS, ghi sự kiện, báo người thân, SMS dự phòng, chia sẻ vị trí. */
public class SosManager {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static boolean sending = false;
    private static boolean pending = false;
    /** true khi nhập mã cưỡng ép: gửi âm thầm, không hiện thông báo nào. */
    public static boolean silent = false;
    public static String lastError = null;

    private static Location lastLoc = null;
    private static FusedLocationProviderClient fused = null;
    private static LocationCallback trackCb = null;
    private static Runnable smsRunnable = null;

    private interface LocCb { void done(Location l); }

    // ================= đếm ngược (bấm nguồn 5 lần) =================
    public static synchronized boolean beginPending(Context c) {
        if (pending || sending || LocalCache.getActiveEventId(c) != null) return false;
        pending = true;
        silent = false;
        lastError = null;
        return true;
    }

    public static synchronized boolean isPending() { return pending; }

    public static synchronized void cancelPending() { pending = false; }

    public static void endPendingAndSend(Context c, String trigger) {
        synchronized (SosManager.class) {
            if (!pending) return;
            pending = false;
        }
        send(c, trigger);
    }

    // ================= gửi SOS =================
    public static void send(Context c, final String trigger) {
        final Context ctx = c.getApplicationContext();
        synchronized (SosManager.class) {
            if (sending || LocalCache.getActiveEventId(ctx) != null) return; // mỗi người chỉ một SOS đang hoạt động
            sending = true;
            lastError = null;
        }
        final FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { fail(ctx, "Chưa đăng nhập nên không gửi được SOS."); return; }
        if (LocalCache.sosCountToday(ctx) >= Config.MAX_SOS_PER_DAY) {
            fail(ctx, "Đã quá giới hạn " + Config.MAX_SOS_PER_DAY + " lần SOS trong ngày.");
            return;
        }
        final List<LocalCache.Relative> rels = LocalCache.getRelatives(ctx);
        if (rels.isEmpty()) {
            fail(ctx, "Chưa có người thân đã xác minh nên SOS không gửi đi đâu cả. Hãy thêm người thân trước.");
            return;
        }
        getLocation(ctx, new LocCb() {
            @Override public void done(Location l) { createEvent(ctx, u, trigger, l, rels); }
        });
    }

    private static void fail(Context ctx, String msg) {
        lastError = msg;
        synchronized (SosManager.class) { sending = false; }
        if (!silent) Notif.info(ctx, "SOS chưa gửi được", msg, 3001);
    }

    private static boolean hasLocPerm(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private static void getLocation(final Context ctx, final LocCb cb) {
        if (!hasLocPerm(ctx)) { cb.done(null); return; }
        final FusedLocationProviderClient f = LocationServices.getFusedLocationProviderClient(ctx);
        final CancellationTokenSource cts = new CancellationTokenSource();
        final boolean[] done = {false};
        final Runnable timeout = new Runnable() {
            @Override public void run() {
                if (done[0]) return;
                done[0] = true;
                cts.cancel();
                fallbackLast(f, cb);
            }
        };
        H.postDelayed(timeout, 8000);
        f.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(loc -> {
                    if (done[0]) return;
                    done[0] = true;
                    H.removeCallbacks(timeout);
                    if (loc != null) cb.done(loc); else fallbackLast(f, cb);
                })
                .addOnFailureListener(e -> {
                    if (done[0]) return;
                    done[0] = true;
                    H.removeCallbacks(timeout);
                    fallbackLast(f, cb);
                });
    }

    @SuppressLint("MissingPermission")
    private static void fallbackLast(FusedLocationProviderClient f, final LocCb cb) {
        f.getLastLocation()
                .addOnSuccessListener(l -> cb.done(l))
                .addOnFailureListener(e -> cb.done(null));
    }

    private static Map<String, Object> locMap(Location l) {
        Map<String, Object> m = new HashMap<>();
        m.put("lat", l.getLatitude());
        m.put("lng", l.getLongitude());
        m.put("acc", (double) l.getAccuracy());
        m.put("t", System.currentTimeMillis());
        return m;
    }

    private static boolean isMock(Location l) {
        return Build.VERSION.SDK_INT >= 31 ? l.isMock() : l.isFromMockProvider();
    }

    private static Map<String, Object> sharedMedical(Context ctx) {
        return jsonToMap(LocalCache.getSharedMedicalJson(ctx));
    }

    private static Map<String, Object> jsonToMap(String json) {
        Map<String, Object> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.getString(k));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void createEvent(Context ctx, FirebaseUser u, String trigger, Location loc, List<LocalCache.Relative> rels) {
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        String uid = u.getUid();
        String eventId = root.child("sosEvents").push().getKey();
        String token = randomToken();
        String name = LocalCache.getName(ctx);
        if (name.isEmpty()) name = "Người dùng SafeCard";
        if (loc != null) lastLoc = loc;

        Map<String, Object> ev = new HashMap<>();
        ev.put("uid", uid);
        ev.put("name", name);
        ev.put("startedAt", ServerValue.TIMESTAMP);
        ev.put("status", "active");
        ev.put("trigger", trigger);
        ev.put("trackToken", token);
        if (loc != null) {
            ev.put("lastLocation", locMap(loc));
            if (isMock(loc)) ev.put("mockLocation", true);
        } else {
            ev.put("noLocation", true);
        }
        Map<String, Object> med = sharedMedical(ctx);
        if (!med.isEmpty()) ev.put("medical", med);
        Map<String, Object> medV = jsonToMap(LocalCache.getVolMedicalJson(ctx));
        if (!medV.isEmpty()) ev.put("medicalVol", medV);
        if (loc != null) {
            Map<String, Object> ap = new HashMap<>();
            ap.put("lat", Math.round(loc.getLatitude() * 100.0) / 100.0);   // khu vực gần đúng ~1 km
            ap.put("lng", Math.round(loc.getLongitude() * 100.0) / 100.0);
            ev.put("approx", ap);
        }
        Map<String, Object> alerts = new HashMap<>();
        for (LocalCache.Relative r : rels) {
            Map<String, Object> a = new HashMap<>();
            a.put("sentAt", ServerValue.TIMESTAMP);
            a.put("status", "sent");
            a.put("type", "relative");
            a.put("name", r.name);
            alerts.put(r.uid, a);
        }
        ev.put("alerts", alerts);
        root.child("sosEvents").child(eventId).setValue(ev);

        for (LocalCache.Relative r : rels) {
            Map<String, Object> inbox = new HashMap<>();
            inbox.put("fromName", name);
            inbox.put("fromUid", uid);
            inbox.put("startedAt", ServerValue.TIMESTAMP);
            inbox.put("status", "sent");
            root.child("alertsInbox").child(r.uid).child(eventId).setValue(inbox);
        }

        Map<String, Object> lt = new HashMap<>();
        lt.put("uid", uid);
        lt.put("name", name);
        lt.put("active", true);
        lt.put("t", ServerValue.TIMESTAMP);
        if (loc != null) {
            lt.put("lat", loc.getLatitude());
            lt.put("lng", loc.getLongitude());
        }
        root.child("liveTracks").child(token).setValue(lt);

        LocalCache.setActive(ctx, eventId, token);
        LocalCache.incSosCount(ctx);
        synchronized (SosManager.class) { sending = false; }

        startTracking(ctx, eventId, token);
        scheduleSms(ctx, eventId);
        volDispatched = false;
        volTotal = 0;
        alertedVol.clear();
        maybeDispatch(ctx, eventId, loc);

        if (!silent && !isOnline(ctx)) {
            Notif.info(ctx, "Chưa gửi được lên mạng", "Không có internet: SOS đã xếp hàng để gửi khi có mạng, SMS đang được gửi bằng sóng di động.", 3005);
        }
        if (!silent) {
            Notif.info(ctx, "SOS đã được gửi",
                    "Đã báo " + rels.size() + " người thân. Vị trí đang được chia sẻ mỗi 10 giây."
                            + (loc == null ? " (Chưa lấy được GPS)" : ""), 3002);
        }
    }

    // ================= chia sẻ vị trí trực tiếp =================
    @SuppressLint("MissingPermission")
    public static void startTracking(final Context c, final String eventId, final String token) {
        final Context ctx = c.getApplicationContext();
        stopTracking();
        if (!hasLocPerm(ctx)) return;
        fused = LocationServices.getFusedLocationProviderClient(ctx);
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, Config.LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(5000).build();
        trackCb = new LocationCallback() {
            @Override public void onLocationResult(LocationResult r) {
                Location l = r.getLastLocation();
                if (l == null) return;
                lastLoc = l;
                DatabaseReference root = FirebaseDatabase.getInstance().getReference();
                root.child("sosEvents").child(eventId).child("lastLocation").setValue(locMap(l));
                Map<String, Object> up = new HashMap<>();
                up.put("lat", l.getLatitude());
                up.put("lng", l.getLongitude());
                up.put("t", ServerValue.TIMESTAMP);
                root.child("liveTracks").child(token).updateChildren(up);
            }
        };
        fused.requestLocationUpdates(req, trackCb, Looper.getMainLooper());
    }

    public static void stopTracking() {
        if (fused != null && trackCb != null) fused.removeLocationUpdates(trackCb);
        trackCb = null;
    }

    /** Gọi khi service khởi động lại: nếu SOS còn hoạt động thì tiếp tục chia sẻ vị trí. */
    public static void resumeTrackingIfActive(Context c) {
        String id = LocalCache.getActiveEventId(c);
        if (id != null && trackCb == null) startTracking(c, id, LocalCache.getActiveToken(c));
    }

    // ================= SMS =================
    private static boolean isOnline(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static void scheduleSms(final Context ctx, final String eventId) {
        final boolean offline = !isOnline(ctx);
        smsRunnable = new Runnable() {
            @Override public void run() { smsPhase(ctx, eventId, offline); }
        };
        H.postDelayed(smsRunnable, offline ? 0 : Config.SMS_DELAY_SEC * 1000L);
    }

    private static void smsPhase(final Context ctx, String eventId, boolean offline) {
        if (!eventId.equals(LocalCache.getActiveEventId(ctx))) return;
        final List<LocalCache.Relative> rels = LocalCache.getRelatives(ctx);
        if (offline) {
            Set<String> all = new HashSet<>();
            for (LocalCache.Relative r : rels) all.add(r.uid);
            sendSms(ctx, rels, all);
            return;
        }
        FirebaseDatabase.getInstance().getReference().child("sosEvents").child(eventId).child("alerts").get()
                .addOnCompleteListener(t -> {
                    Set<String> need = new HashSet<>();
                    if (t.isSuccessful() && t.getResult() != null && t.getResult().exists()) {
                        for (DataSnapshot s : t.getResult().getChildren()) {
                            String st = s.child("status").getValue(String.class);
                            if (st == null || "sent".equals(st)) need.add(s.getKey());
                        }
                    } else {
                        for (LocalCache.Relative r : rels) need.add(r.uid);
                    }
                    sendSms(ctx, rels, need);
                });
    }

    private static String ascii(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('đ', 'd').replace('Đ', 'D');
    }

    private static void sendSms(Context ctx, List<LocalCache.Relative> rels, Set<String> need) {
        if (need.isEmpty()) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            if (!silent) Notif.info(ctx, "Không gửi được SMS", "Chưa cấp quyền SMS cho SafeCard.", 3003);
            return;
        }
        StringBuilder sb = new StringBuilder("SOS tu SafeCard: ");
        sb.append(ascii(LocalCache.getName(ctx).isEmpty() ? "Nguoi dung" : LocalCache.getName(ctx)));
        sb.append(" dang can giup do! ");
        if (lastLoc != null) {
            sb.append("Vi tri: https://maps.google.com/?q=").append(lastLoc.getLatitude()).append(",").append(lastLoc.getLongitude());
        } else {
            sb.append("Chua lay duoc vi tri.");
        }
        if (!Config.TRACK_BASE_URL.isEmpty()) {
            sb.append(" Theo doi: ").append(Config.TRACK_BASE_URL).append("/?t=").append(LocalCache.getActiveToken(ctx));
        }
        String text = sb.toString();
        SmsManager sm = Build.VERSION.SDK_INT >= 31 ? ctx.getSystemService(SmsManager.class) : SmsManager.getDefault();
        for (LocalCache.Relative r : rels) {
            if (!need.contains(r.uid) || r.phone == null || r.phone.isEmpty()) continue;
            try {
                ArrayList<String> parts = sm.divideMessage(text);
                sm.sendMultipartTextMessage(r.phone, null, parts, null, null);
            } catch (Exception ignored) {
            }
        }
    }

    // ================= tình nguyện viên (cấp 1) =================
    private static boolean volDispatched = false;
    private static int volTotal = 0;
    private static final Set<String> alertedVol = new HashSet<>();

    public static boolean isVolunteerDispatched() { return volDispatched; }

    public static void maybeDispatch(Context ctx, String eventId, Location loc) {
        if (LevelManager.level(ctx) != 1 || LevelManager.isLocked(ctx)) return;
        if (loc == null || isMock(loc)) return;            // chống GPS giả
        if (LevelManager.points(ctx) < Config.POINT_LOW) {  // điểm thấp: thêm một bước xác nhận
            if (!silent) Notif.info(ctx, "Điểm uy tín thấp",
                    "Mở màn hình SOS và bấm \"Gửi cho tình nguyện viên\" để xác nhận gửi cho người lạ. Người thân đã được báo.", 3004);
            return;
        }
        dispatchVolunteers(ctx, eventId);
    }

    /** Gửi theo đợt: 1 km tối đa 5 người, sau 60 giây chưa ai nhận thì mở rộng 3 km. Chạy trên máy người gặp nạn (không có Cloud Functions). */
    public static void dispatchVolunteers(Context c, String eventId) {
        Context ctx = c.getApplicationContext();
        if (eventId == null || volDispatched || lastLoc == null) return;
        if (LevelManager.level(ctx) != 1 || LevelManager.isLocked(ctx)) return;
        volDispatched = true;
        volTotal = 0;
        alertedVol.clear();
        runWave(ctx, eventId, 1, Config.RADIUS_1_KM);
    }

    private static void runWave(final Context ctx, final String eventId, final int wave, double radiusKm) {
        if (!eventId.equals(LocalCache.getActiveEventId(ctx)) || lastLoc == null) return;
        final Location center = lastLoc;
        FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) return;
        final String myUid = me.getUid();
        final Map<String, Location> cands = new HashMap<>();
        GeoFire gf = new GeoFire(FirebaseDatabase.getInstance().getReference().child("responders_geo"));
        final GeoQuery q = gf.queryAtLocation(new GeoLocation(center.getLatitude(), center.getLongitude()), radiusKm);
        q.addGeoQueryEventListener(new GeoQueryEventListener() {
            @Override public void onKeyEntered(String key, GeoLocation l) {
                if (key.equals(myUid) || alertedVol.contains(key)) return;
                Location x = new Location("geo");
                x.setLatitude(l.latitude);
                x.setLongitude(l.longitude);
                cands.put(key, x);
            }
            @Override public void onKeyExited(String key) { }
            @Override public void onKeyMoved(String key, GeoLocation l) { }
            @Override public void onGeoQueryReady() {
                q.removeAllListeners();
                pickVolunteers(ctx, eventId, wave, center, cands);
            }
            @Override public void onGeoQueryError(DatabaseError e) {
                q.removeAllListeners();
                afterWave(ctx, eventId, wave, 0);
            }
        });
    }

    private static void pickVolunteers(final Context ctx, final String eventId, final int wave, final Location center, final Map<String, Location> cands) {
        if (cands.isEmpty()) { afterWave(ctx, eventId, wave, 0); return; }
        final DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        final List<String> ok = new ArrayList<>();
        final Map<String, String> names = new HashMap<>();
        final int[] left = {cands.size()};
        for (final String key : cands.keySet()) {
            root.child("responders").child(key).get().addOnCompleteListener(t -> {
                if (t.isSuccessful() && t.getResult() != null) {
                    DataSnapshot s = t.getResult();
                    Boolean active = s.child("active").getValue(Boolean.class);
                    Long seen = s.child("lastSeen").getValue(Long.class);
                    // chỉ cảnh báo người cập nhật vị trí trong 30 phút gần nhất
                    if (Boolean.TRUE.equals(active) && seen != null && System.currentTimeMillis() - seen < 30 * 60 * 1000L) {
                        ok.add(key);
                        String n = s.child("name").getValue(String.class);
                        names.put(key, n == null ? "Tình nguyện viên" : n);
                    }
                }
                left[0]--;
                if (left[0] == 0) sendWave(ctx, eventId, wave, center, cands, ok, names);
            });
        }
    }

    private static void sendWave(Context ctx, String eventId, int wave, final Location center, final Map<String, Location> cands,
                                 List<String> ok, Map<String, String> names) {
        if (!eventId.equals(LocalCache.getActiveEventId(ctx))) return;
        Collections.sort(ok, (a, b) -> Float.compare(center.distanceTo(cands.get(a)), center.distanceTo(cands.get(b))));
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        int n = 0;
        for (String key : ok) {
            if (n >= Config.MAX_WAVE) break;
            n++;
            alertedVol.add(key);
            int dist = Math.round(center.distanceTo(cands.get(key)));
            Map<String, Object> a = new HashMap<>();
            a.put("type", "volunteer");
            a.put("status", "sent");
            a.put("wave", wave);
            a.put("distanceM", dist);
            a.put("sentAt", ServerValue.TIMESTAMP);
            a.put("name", names.get(key));
            root.child("sosEvents").child(eventId).child("alerts").child(key).setValue(a);
            Map<String, Object> in = new HashMap<>();
            in.put("type", "volunteer");
            in.put("distanceM", dist);
            in.put("fromName", "Người cần giúp");
            in.put("startedAt", ServerValue.TIMESTAMP);
            in.put("status", "sent");
            root.child("alertsInbox").child(key).child(eventId).setValue(in);
        }
        afterWave(ctx, eventId, wave, n);
    }

    private static void afterWave(final Context ctx, final String eventId, int wave, int count) {
        volTotal += count;
        if (wave == 1) {
            if (count == 0) { runWave(ctx, eventId, 2, Config.RADIUS_2_KM); return; }
            H.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!eventId.equals(LocalCache.getActiveEventId(ctx))) return;
                    FirebaseDatabase.getInstance().getReference().child("sosEvents").child(eventId).child("alerts").get()
                            .addOnCompleteListener(t -> {
                                boolean taken = false;
                                if (t.isSuccessful() && t.getResult() != null) {
                                    for (DataSnapshot s : t.getResult().getChildren()) {
                                        String st = s.child("status").getValue(String.class);
                                        if ("volunteer".equals(s.child("type").getValue(String.class))
                                                && ("accepted".equals(st) || "arrived".equals(st))) taken = true;
                                    }
                                }
                                if (!taken) runWave(ctx, eventId, 2, Config.RADIUS_2_KM);
                            });
                }
            }, Config.WAVE_SECONDS * 1000L);
        } else if (volTotal == 0 && !silent) {
            Notif.info(ctx, "Chưa có tình nguyện viên gần bạn", "Vẫn đã gửi cho người thân của bạn.", 3006);
        }
    }

    // ================= kết thúc & tính điểm =================
    /** Người gặp nạn xác nhận ai đã đến giúp: ghi +điểm cho tình nguyện viên đó (luật Firebase kiểm tra hợp lệ). */
    public static void confirmHelp(Context c, String eventId, String rid) {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        Map<String, Object> e = new HashMap<>();
        e.put("delta", Config.POINT_UNIT);
        e.put("reason", "helped");
        e.put("eventId", eventId);
        e.put("at", ServerValue.TIMESTAMP);
        root.child("pointLedger").child(rid).child(eventId).setValue(e);
        Map<String, Object> p = new HashMap<>();
        p.put("t", ServerValue.TIMESTAMP);
        root.child("helpPairs").child(u.getUid() + "_" + rid).setValue(p);
    }

    public static void resolve(Context c, String result) {
        Context ctx = c.getApplicationContext();
        String id = LocalCache.getActiveEventId(ctx);
        if (id == null) return;
        String token = LocalCache.getActiveToken(ctx);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();

        Map<String, Object> up = new HashMap<>();
        up.put("status", "resolved");
        up.put("resolvedAt", ServerValue.TIMESTAMP);
        up.put("outcome/victimSays", result);
        up.put("lastLocation", null);          // xóa vị trí chính xác khi kết thúc
        root.child("sosEvents").child(id).updateChildren(up);

        // Báo nhầm: tự trừ điểm (chỉ cấp 1). Ghi SAU khi outcome đã ghi (luật kiểm tra outcome).
        if ("false_alarm".equals(result) && u != null && LevelManager.level(ctx) == 1) {
            Map<String, Object> e = new HashMap<>();
            e.put("delta", -Config.POINT_UNIT);
            e.put("reason", "false_alarm");
            e.put("eventId", id);
            e.put("at", ServerValue.TIMESTAMP);
            root.child("pointLedger").child(u.getUid()).child(id).setValue(e);
        }

        Map<String, Object> lt = new HashMap<>();
        lt.put("active", false);
        lt.put("lat", null);
        lt.put("lng", null);
        root.child("liveTracks").child(token).updateChildren(lt);
        for (LocalCache.Relative r : LocalCache.getRelatives(ctx)) {
            root.child("alertsInbox").child(r.uid).child(id).child("status").setValue("resolved");
        }
        for (String rid : alertedVol) {
            root.child("alertsInbox").child(rid).child(id).child("status").setValue("resolved");
        }
        stopTracking();
        if (smsRunnable != null) H.removeCallbacks(smsRunnable);
        LocalCache.clearActive(ctx);
        silent = false;
        volDispatched = false;
        volTotal = 0;
        alertedVol.clear();
        ctx.getSystemService(NotificationManager.class).cancel(3002);
    }
}
