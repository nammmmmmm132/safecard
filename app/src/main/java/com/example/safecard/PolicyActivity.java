package com.example.safecard;

import android.content.Intent;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class PolicyActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout p = UI.page(this);
        p.addView(UI.title(this, "Chính sách quyền riêng tư"));
        p.addView(UI.text(this,
                "• SafeCard là công cụ hỗ trợ, KHÔNG thay thế gọi 115/113.\n"
                + "• Khi gửi SOS, vị trí và các trường hồ sơ y tế bạn cho phép được chia sẻ với người thân (và tình nguyện viên nếu bạn là cấp 1). Vị trí được xóa khỏi sự kiện khi bạn bấm \"Đã an toàn\".\n"
                + "• Ảnh CCCD/selfie chỉ để admin duyệt và bị xóa ngay sau khi duyệt. Hệ thống chỉ giữ mã băm số CCCD để chặn đăng ký lại (kể cả khi bạn xóa/bị cấm tài khoản).\n"
                + "• Thông tin y tế do bạn tự khai, chưa được cơ sở y tế xác nhận.\n"
                + "• Dữ liệu của trẻ em chỉ được nhập khi có sự đồng ý của cha mẹ/người giám hộ (tham chiếu Nghị định 13/2023/NĐ-CP về bảo vệ dữ liệu cá nhân – cần đọc lại để trích dẫn chính xác trong báo cáo).\n"
                + "• Bạn có quyền xóa dữ liệu của mình bằng nút bên dưới."));
        final CheckBox cb = UI.check(this, "Tôi đã đọc và đồng ý");
        cb.setChecked(LocalCache.getBool(this, "consent", false));
        p.addView(cb);
        p.addView(UI.btn(this, "Xác nhận", v -> {
            if (!cb.isChecked()) { Toast.makeText(this, "Cần đồng ý để sử dụng", Toast.LENGTH_SHORT).show(); return; }
            LocalCache.setBool(this, "consent", true);
            finish();
        }));
        p.addView(UI.btn(this, "Xóa toàn bộ dữ liệu của tôi", v -> new AlertDialog.Builder(this)
                .setTitle("Xóa dữ liệu?")
                .setMessage("Hồ sơ, người thân, thẻ QR, trạng thái tình nguyện viên và tài khoản sẽ bị xóa. Không thể hoàn tác.")
                .setPositiveButton("Xóa", (d, w) -> deleteAll())
                .setNegativeButton("Hủy", null).show()));
    }

    @Override
    public void onBackPressed() {
        if (!LocalCache.getBool(this, "consent", false)) { finishAffinity(); return; }
        super.onBackPressed();
    }

    private void deleteAll() {
        final FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        final String uid = u.getUid();
        final DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        stopService(new Intent(this, GuardService.class));
        LocalCache.setGuardEnabled(this, false);
        root.child("users").child(uid).child("qrToken").get().addOnCompleteListener(t -> {
            if (t.isSuccessful() && t.getResult() != null && t.getResult().getValue(String.class) != null) {
                String tok = t.getResult().getValue(String.class);
                root.child("publicCards").child(tok).child("data").removeValue();
                root.child("publicCards").child(tok).child("uid").removeValue();
            }
            root.child("dependents").child(uid).get().addOnCompleteListener(t2 -> {
                if (t2.isSuccessful() && t2.getResult() != null) {
                    for (DataSnapshot d : t2.getResult().getChildren()) {
                        String tk = d.child("qrToken").getValue(String.class);
                        if (tk != null) {
                            root.child("publicCards").child(tk).child("data").removeValue();
                            root.child("publicCards").child(tk).child("uid").removeValue();
                        }
                    }
                }
                root.child("dependents").child(uid).removeValue();
                root.child("responders").child(uid).removeValue();
                root.child("responders_geo").child(uid).removeValue();
                root.child("relativeLinks").child(uid).get().addOnCompleteListener(t3 -> {
                    if (t3.isSuccessful() && t3.getResult() != null)
                        for (DataSnapshot c : t3.getResult().getChildren())
                            root.child("relativeLinks").child(uid).child(c.getKey()).removeValue();
                    root.child("users").child(uid).removeValue().addOnCompleteListener(t4 -> {
                        u.delete().addOnCompleteListener(t5 -> {
                            FirebaseAuth.getInstance().signOut();
                            Toast.makeText(this, t5.isSuccessful() ? "Đã xóa tài khoản và dữ liệu."
                                    : "Đã xóa dữ liệu. Tài khoản đăng nhập chưa xóa được (cần đăng nhập lại gần đây), đã đăng xuất.", Toast.LENGTH_LONG).show();
                            startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        });
                    });
                });
            });
        });
    }
}
