package com.example.safecard;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.firebase.geofire.GeoFire;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ResponderActivity extends AppCompatActivity {
    private TextView tvInfo, tvState;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { finish(); return; }
        LinearLayout p = UI.page(this);
        p.addView(UI.title(this, "Tình nguyện viên"));
        tvInfo = UI.text(this, "");
        tvState = UI.text(this, "");
        p.addView(tvInfo);
        p.addView(UI.text(this, "Khi bật, SafeCard cập nhật vị trí THÔ của bạn khoảng 15 phút một lần. Khi có người cần giúp trong 1 km (rồi 3 km), bạn nhận cảnh báo chỉ gồm khoảng cách và khu vực gần đúng. Bấm \"Nhận hỗ trợ\" mới thấy vị trí chính xác (việc xem được ghi nhật ký). Bạn không bắt buộc phải đến; không đến một mình nếu thấy nguy hiểm."));
        p.addView(tvState);
        p.addView(UI.btn(this, "Bật làm tình nguyện viên", v -> enable(u)));
        p.addView(UI.btn(this, "Tắt", v -> disable(u)));
        p.addView(UI.btn(this, "Cần cấp 1? Xác minh danh tính", v -> startActivity(new Intent(this, VerifyActivity.class))));
    }

    @Override
    protected void onResume() {
        super.onResume();
        int lv = LevelManager.level(this);
        tvInfo.setText("Cấp tài khoản: " + lv + (lv == 1 ? "\nĐiểm uy tín: " + LevelManager.points(this) + "/100\nHuy hiệu: đã giúp " + LevelManager.helps(this) + " lần" : "\nCần tài khoản cấp 1 (đã xác minh CCCD) để làm tình nguyện viên."));
        tvState.setText("Trạng thái: " + (LocalCache.getBool(this, "responderOn", false) ? "ĐANG BẬT" : "tắt"));
    }

    private void enable(FirebaseUser u) {
        if (LevelManager.level(this) != 1) { toast("Cần tài khoản cấp 1"); return; }
        if (LevelManager.isLocked(this)) { toast("Tài khoản đang bị khóa tạm thời"); return; }
        String name = LocalCache.getName(this);
        if (name.isEmpty()) name = "Tình nguyện viên";
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        Map<String, Object> m = new HashMap<>();
        m.put("active", true);
        m.put("lastSeen", ServerValue.TIMESTAMP);
        m.put("name", name);
        root.child("responders").child(u.getUid()).updateChildren(m);
        LocalCache.setBool(this, "responderOn", true);
        WorkManager wm = WorkManager.getInstance(this);
        wm.enqueue(new OneTimeWorkRequest.Builder(LocationWorker.class).build());
        wm.enqueueUniquePeriodicWork("responder_loc", ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(LocationWorker.class, 15, TimeUnit.MINUTES).build());
        toast("Đã bật. Cần quyền vị trí \"Luôn cho phép\" để cập nhật nền.");
        onResume();
    }

    private void disable(FirebaseUser u) {
        LocalCache.setBool(this, "responderOn", false);
        WorkManager.getInstance(this).cancelUniqueWork("responder_loc");
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        root.child("responders").child(u.getUid()).child("active").setValue(false);
        new GeoFire(root.child("responders_geo")).removeLocation(u.getUid());
        onResume();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
