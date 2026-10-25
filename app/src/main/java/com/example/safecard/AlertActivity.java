package com.example.safecard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Màn hình nhận cảnh báo: người thân (thấy ngay vị trí) hoặc tình nguyện viên (chỉ khoảng cách đến khi bấm Nhận hỗ trợ). */
public class AlertActivity extends AppCompatActivity {
    public static final String EXTRA_EVENT = "eventId";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_DIST = "dist";

    private TextView tvTitle, tvStatus, tvLoc, tvMed;
    private Button btnMap, btnAccept, btnDecline, btnChat, btnReport;
    private DatabaseReference ev;
    private final List<DatabaseReference> refs = new ArrayList<>();
    private final List<ValueEventListener> ls = new ArrayList<>();
    private String eventId, myUid, type, victimUid;
    private Double lat, lng;
    private String victimName = "Người gặp nạn";
    private boolean accepted = false, arrived = false;
    private FusedLocationProviderClient fused;
    private LocationCallback arriveCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        eventId = getIntent().getStringExtra(EXTRA_EVENT);
        type = getIntent().getStringExtra(EXTRA_TYPE);
        if (type == null) type = "relative";
        if (u == null || eventId == null) { finish(); return; }
        myUid = u.getUid();
        ev = FirebaseDatabase.getInstance().getReference().child("sosEvents").child(eventId);
        getSystemService(NotificationManager.class).cancel(eventId.hashCode());

        LinearLayout p = UI.page(this);
        p.setBackgroundColor(0xFFFFEBEE);
        tvTitle = UI.title(this, "🚨 Đang tải…");
        tvTitle.setTextColor(0xFFB71C1C);
        p.addView(tvTitle);
        tvStatus = UI.text(this, "");
        p.addView(tvStatus);
        tvLoc = UI.text(this, "");
        p.addView(tvLoc);
        btnMap = UI.btn(this, "Mở bản đồ", v -> openMap());
        btnMap.setEnabled(false);
        p.addView(btnMap);
        tvMed = UI.text(this, "");
        p.addView(tvMed);

        boolean vol = type.equals("volunteer");
        btnAccept = UI.btn(this, vol ? "Nhận hỗ trợ" : "Tôi đang đến", v -> accept());
        btnDecline = UI.btn(this, vol ? "Từ chối" : "Tôi không đến được", v -> decline());
        btnChat = UI.btn(this, "Nhắn tin", v -> startActivity(new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_EVENT, eventId).putExtra(ChatActivity.EXTRA_RID, myUid)));
        btnChat.setVisibility(vol ? android.view.View.GONE : android.view.View.VISIBLE);
        btnReport = UI.btn(this, "Đánh dấu báo nhầm", v -> reportFalse());
        btnReport.setVisibility(vol ? android.view.View.GONE : android.view.View.VISIBLE);
        p.addView(btnAccept);
        p.addView(btnDecline);
        p.addView(btnChat);
        p.addView(btnReport);
        p.addView(UI.btn(this, "Đóng", v -> finish()));
        p.addView(UI.text(this, vol
                ? "Không đến một mình nếu thấy nguy hiểm. Bạn không bắt buộc phải đến. Nếu nguy hiểm đến tính mạng, hãy gọi 115/113."
                : "SafeCard chỉ là công cụ hỗ trợ. Nếu nguy hiểm đến tính mạng, hãy gọi 115/113."));

        listen(ev.child("status"), s -> {
            String st = s.getValue(String.class);
            boolean active = "active".equals(st);
            tvStatus.setText(st == null ? "" : (active ? "Đang diễn ra." : "Sự kiện đã kết thúc."));
            btnAccept.setEnabled(active);
            btnDecline.setEnabled(active);
        });
        if (vol) {
            tvTitle.setText("🆘 Có người cần giúp gần bạn");
            String d = getIntent().getStringExtra(EXTRA_DIST);
            tvLoc.setText("Cách bạn khoảng " + (d == null ? "?" : d) + " m. Vị trí chính xác chỉ hiện sau khi bạn bấm Nhận hỗ trợ.");
            listen(ev.child("approx"), s -> {
                Double a = s.child("lat").getValue(Double.class), c = s.child("lng").getValue(Double.class);
                if (a != null && c != null && !accepted) tvLoc.setText(tvLoc.getText() + String.format("\nKhu vực gần đúng: %.2f, %.2f", a, c));
            });
        } else {
            setStatusIfNot("seen", "accepted", "declined");
            listen(ev.child("name"), s -> {
                String n = s.getValue(String.class);
                if (n != null) { victimName = n; tvTitle.setText("🚨 " + n + " đang cần giúp đỡ!"); }
            });
            listen(ev.child("uid"), s -> victimUid = s.getValue(String.class));
            attachLocation(false);
            listen(ev.child("medical"), s -> showMedical(s));
        }
    }

    private interface SnapCb { void on(DataSnapshot s); }

    private void listen(DatabaseReference r, final SnapCb cb) {
        ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) { cb.on(s); }
            @Override public void onCancelled(DatabaseError e) { }
        };
        r.addValueEventListener(l);
        refs.add(r);
        ls.add(l);
    }

    private void attachLocation(boolean volunteer) {
        listen(ev.child("lastLocation"), s -> {
            lat = s.child("lat").getValue(Double.class);
            lng = s.child("lng").getValue(Double.class);
            if (lat != null && lng != null) {
                tvLoc.setText(String.format("Vị trí: %.5f, %.5f", lat, lng));
                btnMap.setEnabled(true);
            } else {
                tvLoc.setText("Chưa có vị trí (chưa lấy được GPS hoặc sự kiện đã kết thúc).");
                btnMap.setEnabled(false);
            }
        });
        if (volunteer) listen(ev.child("medicalVol"), s -> showMedical(s));
    }

    private void showMedical(DataSnapshot s) {
        StringBuilder sb = new StringBuilder();
        for (DataSnapshot m : s.getChildren()) sb.append(label(m.getKey())).append(": ").append(m.getValue(String.class)).append("\n");
        tvMed.setText(sb.length() == 0 ? "Người gặp nạn không chia sẻ thông tin y tế." : "Thông tin y tế được chia sẻ (tự khai):\n" + sb.toString().trim());
    }

    private void accept() {
        Map<String, Object> up = new HashMap<>();
        up.put("status", "accepted");
        up.put("acceptedAt", ServerValue.TIMESTAMP);
        if (type.equals("volunteer")) {
            String n = LocalCache.getName(this);
            up.put("name", n.isEmpty() ? "Tình nguyện viên" : n);
            up.put("points", LevelManager.points(this));
        }
        ev.child("alerts").child(myUid).updateChildren(up).addOnCompleteListener(t -> {
            if (!t.isSuccessful()) { toast("Không gửi được phản hồi"); return; }
            accepted = true;
            if (type.equals("volunteer")) {
                Map<String, Object> log = new HashMap<>();
                log.put("uid", myUid);
                log.put("at", ServerValue.TIMESTAMP);
                log.put("what", "lastLocation+medicalVol");
                ev.child("accessLog").push().setValue(log);   // nhật ký ai xem gì
                attachLocation(true);
                btnChat.setVisibility(android.view.View.VISIBLE);
                btnAccept.setEnabled(false);
                startArrivalTracking();
                toast("Đã nhận hỗ trợ. Hãy giữ an toàn cho bản thân.");
            } else {
                toast("Đã báo cho người gặp nạn: bạn đang đến");
                openMap();
            }
        });
    }

    private void decline() {
        ev.child("alerts").child(myUid).child("status").setValue("declined");
        toast("Đã ghi nhận");
        if (type.equals("volunteer")) finish();
    }

    private void reportFalse() {
        Map<String, Object> m = new HashMap<>();
        m.put("from", myUid);
        m.put("type", "abuse");
        m.put("targetUid", victimUid == null ? "" : victimUid);
        m.put("text", "Người thân đánh dấu SOS báo nhầm. Sự kiện: " + eventId);
        m.put("status", "open");
        m.put("createdAt", ServerValue.TIMESTAMP);
        FirebaseDatabase.getInstance().getReference().child("reports").push().setValue(m);
        toast("Đã báo cho admin");
    }

    @SuppressLint("MissingPermission")
    private void startArrivalTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        fused = LocationServices.getFusedLocationProviderClient(this);
        LocationRequest r = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build();
        arriveCb = new LocationCallback() {
            @Override public void onLocationResult(LocationResult res) {
                Location l = res.getLastLocation();
                if (l == null || lat == null || lng == null || arrived) return;
                boolean mock = Build.VERSION.SDK_INT >= 31 ? l.isMock() : l.isFromMockProvider();
                if (mock) return;   // chống GPS giả
                float[] d = new float[1];
                Location.distanceBetween(l.getLatitude(), l.getLongitude(), lat, lng, d);
                if (d[0] < Config.ARRIVE_METERS) {
                    arrived = true;
                    Map<String, Object> up = new HashMap<>();
                    up.put("status", "arrived");
                    up.put("arrivedAt", ServerValue.TIMESTAMP);
                    ev.child("alerts").child(myUid).updateChildren(up);
                    toast("Đã ghi nhận bạn có mặt tại nơi xảy ra sự việc");
                }
            }
        };
        fused.requestLocationUpdates(r, arriveCb, Looper.getMainLooper());
    }

    private void setStatusIfNot(final String newStatus, final String... keep) {
        ev.child("alerts").child(myUid).child("status").runTransaction(new Transaction.Handler() {
            @Override public Transaction.Result doTransaction(MutableData d) {
                String s = d.getValue(String.class);
                for (String k : keep) if (k.equals(s)) return Transaction.success(d);
                d.setValue(newStatus);
                return Transaction.success(d);
            }
            @Override public void onComplete(DatabaseError e, boolean c, DataSnapshot s) { }
        });
    }

    private void openMap() {
        if (lat == null || lng == null) return;
        Uri uri = Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng + "(" + Uri.encode(victimName) + ")");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=" + lat + "," + lng)));
        }
    }

    private String label(String k) {
        switch (k) {
            case "dob": return "Ngày sinh";
            case "bloodType": return "Nhóm máu";
            case "allergies": return "Dị ứng";
            case "conditions": return "Bệnh nền";
            case "medications": return "Thuốc đang dùng";
            case "insuranceNo": return "Số bảo hiểm";
            case "note": return "Ghi chú";
            default: return k;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onDestroy() {
        for (int i = 0; i < refs.size(); i++) refs.get(i).removeEventListener(ls.get(i));
        if (fused != null && arriveCb != null) fused.removeLocationUpdates(arriveCb);
        super.onDestroy();
    }
}
