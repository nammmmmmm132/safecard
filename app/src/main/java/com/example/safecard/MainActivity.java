package com.example.safecard;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class MainActivity extends AppCompatActivity {
    private TextView tvLevel, tvRelatives, tvWarn, tvInvites, tvGuard;
    private Button btnAdmin;
    private DatabaseReference linksRef, invRef;
    private ValueEventListener linksL, invL;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() { refreshStatus(); h.postDelayed(this, 2000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        CacheSync.start(this);
        LinearLayout p = UI.page(this);
        TextView logo = UI.title(this, "SafeCard");
        logo.setTextColor(0xFFC62828);
        p.addView(logo);
        tvLevel = UI.text(this, "");
        p.addView(tvLevel);
        tvRelatives = UI.text(this, "");
        tvRelatives.setTextSize(20);
        p.addView(tvRelatives);
        tvWarn = UI.text(this, "");
        tvWarn.setTextColor(0xFFC62828);
        tvWarn.setVisibility(View.GONE);
        p.addView(tvWarn);
        tvInvites = UI.text(this, "");
        tvInvites.setTextColor(0xFF1565C0);
        tvInvites.setVisibility(View.GONE);
        p.addView(tvInvites);
        tvGuard = UI.text(this, "");
        p.addView(tvGuard);

        Button sos = UI.btn(this, "🚨 GỬI SOS", v ->
                startActivity(new Intent(this, SosActivity.class).putExtra(SosActivity.EXTRA_TRIGGER, "shortcut_hold")));
        sos.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFC62828));
        sos.setTextSize(22);
        p.addView(sos, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(this, 80)));

        p.addView(UI.btn(this, "Hồ sơ y tế", v -> startActivity(new Intent(this, ProfileActivity.class))));
        p.addView(UI.btn(this, "Người thân", v -> startActivity(new Intent(this, ContactsActivity.class))));
        p.addView(UI.btn(this, "Thiết lập bảo vệ (quyền, mã hủy, bấm nguồn 5 lần)", v -> startActivity(new Intent(this, SetupActivity.class))));
        p.addView(UI.btn(this, "Mã QR của tôi", v -> startActivity(new Intent(this, QrActivity.class))));
        p.addView(UI.btn(this, "Hồ sơ phụ (con nhỏ…)", v -> startActivity(new Intent(this, DependentsActivity.class))));
        p.addView(UI.btn(this, "Xác minh danh tính (cấp 1)", v -> startActivity(new Intent(this, VerifyActivity.class))));
        p.addView(UI.btn(this, "Tình nguyện viên", v -> startActivity(new Intent(this, ResponderActivity.class))));
        p.addView(UI.btn(this, "Khiếu nại / báo cáo", v -> startActivity(new Intent(this, ReportActivity.class))));
        p.addView(UI.btn(this, "Chính sách & xóa dữ liệu", v -> startActivity(new Intent(this, PolicyActivity.class))));
        btnAdmin = UI.btn(this, "Admin", v -> startActivity(new Intent(this, AdminActivity.class)));
        btnAdmin.setVisibility(View.GONE);
        p.addView(btnAdmin);
        p.addView(UI.btn(this, "Đăng xuất", v -> {
            stopService(new Intent(this, GuardService.class));
            LocalCache.setGuardEnabled(this, false);
            FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        }));
        p.addView(UI.text(this, "SafeCard là công cụ hỗ trợ, không thay thế gọi 115/113."));

        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        linksRef = root.child("relativeLinks").child(u.getUid());
        linksL = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                long n = s.getChildrenCount();
                tvRelatives.setText(n + " người thân sẵn sàng");
                if (n == 0) {
                    tvWarn.setVisibility(View.VISIBLE);
                    tvWarn.setText("⚠️ Chưa có người thân nào đã xác minh: nếu bấm SOS thì sẽ không gửi đi đâu cả. Vào mục Người thân để thêm.");
                } else tvWarn.setVisibility(View.GONE);
            }
            @Override public void onCancelled(DatabaseError e) { }
        };
        linksRef.addValueEventListener(linksL);

        String phone = u.getPhoneNumber();
        if (phone != null) {
            invRef = root.child("invitesByPhone").child(PhoneUtil.key(phone));
            invL = new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    int n = 0;
                    long now = System.currentTimeMillis();
                    for (DataSnapshot c : s.getChildren()) {
                        Long t = c.child("createdAt").getValue(Long.class);
                        if ("pending".equals(c.child("status").getValue(String.class))
                                && (t == null || now - t < Config.INVITE_DAYS * 86400000L)) n++;
                    }
                    tvInvites.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
                    tvInvites.setText("📨 Bạn có " + n + " lời mời làm người thân. Vào mục Người thân để chấp nhận.");
                }
                @Override public void onCancelled(DatabaseError e) { }
            };
            invRef.addValueEventListener(invL);
        }
    }

    private void refreshStatus() {
        int lv = LevelManager.level(this);
        String s = "Tài khoản cấp " + lv;
        if (lv == 1) {
            s += " · Điểm uy tín " + LevelManager.points(this) + "/100 · Đã giúp " + LevelManager.helps(this) + " lần";
            if (LevelManager.isLocked(this)) s += "\n🔒 Đang bị khóa gửi cho người lạ (SOS cho người thân vẫn hoạt động)";
        } else s += " (cảnh báo chỉ gửi cho người thân)";
        tvLevel.setText(s);
        boolean on = LocalCache.isGuardEnabled(this);
        tvGuard.setText(on ? "🛡️ Bảo vệ nền: BẬT (bấm nút nguồn 5 lần)" : "🛡️ Bảo vệ nền: TẮT (vào Thiết lập để bật)");
        btnAdmin.setVisibility(LevelManager.isAdmin(this) ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (tvGuard == null) return;
        if (!LocalCache.getBool(this, "consent", false)) startActivity(new Intent(this, PolicyActivity.class));
        if (LocalCache.isGuardEnabled(this)) {
            try { ContextCompat.startForegroundService(this, new Intent(this, GuardService.class)); } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tvGuard != null) h.post(refresher);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(refresher);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (linksRef != null && linksL != null) linksRef.removeEventListener(linksL);
        if (invRef != null && invL != null) invRef.removeEventListener(invL);
        super.onDestroy();
    }
}
