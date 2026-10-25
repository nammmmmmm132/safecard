package com.example.safecard;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bảng admin: duyệt CCCD, duyệt giấy khai sinh, xử lý khiếu nại, thống kê báo nhầm theo trigger, khóa tài khoản.
 * Quyền admin = có node admins/{uid} = true (tự tạo trong Firebase Console, xem README). Bật xác thực 2 bước cho tài khoản Google quản trị Firebase.
 */
public class AdminActivity extends AppCompatActivity {
    private DatabaseReference root;
    private String me;
    private LinearLayout secV, secD, secR, secS;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (FirebaseAuth.getInstance().getCurrentUser() == null || !LevelManager.isAdmin(this)) { finish(); return; }
        me = FirebaseAuth.getInstance().getCurrentUser().getUid();
        root = FirebaseDatabase.getInstance().getReference();
        LinearLayout p = UI.page(this);
        p.addView(UI.title(this, "Admin"));
        p.addView(UI.btn(this, "Làm mới", v -> load()));

        p.addView(UI.text(this, "— Khóa / mở khóa tài khoản —"));
        final EditText etUid = UI.edit(this, "UID người dùng", InputType.TYPE_CLASS_TEXT);
        p.addView(etUid);
        p.addView(UI.btn(this, "Khóa 30 ngày", v -> lock(etUid.getText().toString().trim(), false)));
        p.addView(UI.btn(this, "Khóa vĩnh viễn (xác định cố ý)", v -> lock(etUid.getText().toString().trim(), true)));
        p.addView(UI.btn(this, "Mở khóa", v -> {
            String u = etUid.getText().toString().trim();
            if (!u.isEmpty()) root.child("statusLocks").child(u).removeValue();
            toast("Đã mở khóa");
        }));

        p.addView(UI.text(this, "— Hàng chờ CCCD —"));
        secV = vbox(); p.addView(secV);
        p.addView(UI.text(this, "— Hàng chờ giấy khai sinh —"));
        secD = vbox(); p.addView(secD);
        p.addView(UI.text(this, "— Khiếu nại / báo cáo —"));
        secR = vbox(); p.addView(secR);
        p.addView(UI.text(this, "— Thống kê báo nhầm theo cách kích hoạt —"));
        secS = vbox(); p.addView(secS);
        load();
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private void load() {
        secV.removeAllViews(); secD.removeAllViews(); secR.removeAllViews(); secS.removeAllViews();
        loadVerifs(); loadDeps(); loadReports(); loadStats();
    }

    private ImageView img(String b64) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setMaxHeight(UI.dp(this, 240));
        iv.setImageBitmap(ImageUtil.fromBase64(b64));
        return iv;
    }

    // ---------- CCCD ----------
    private void loadVerifs() {
        root.child("verifications").orderByChild("status").equalTo("pending").get().addOnCompleteListener(t -> {
            if (!t.isSuccessful() || t.getResult() == null) { secV.addView(UI.text(this, "Không đọc được (kiểm tra quyền admin).")); return; }
            if (t.getResult().getChildrenCount() == 0) secV.addView(UI.text(this, "Không có hồ sơ chờ."));
            for (final DataSnapshot s : t.getResult().getChildren()) {
                final String uid = s.getKey();
                secV.addView(UI.text(this, "UID " + uid + "\nTên: " + s.child("fullName").getValue(String.class)
                        + "\nSinh: " + s.child("dob").getValue(String.class)
                        + "\n4 số cuối CCCD: " + s.child("idLast4").getValue(String.class)
                        + "\nLần nộp: " + s.child("attempts").getValue(Long.class)));
                secV.addView(img(s.child("idImg").getValue(String.class)));
                secV.addView(img(s.child("selfieImg").getValue(String.class)));
                secV.addView(UI.btn(this, "Duyệt cấp 1", v -> approveId(uid, s)));
                secV.addView(UI.btn(this, "Từ chối", v -> askReason(reason -> rejectId(uid, reason))));
            }
        });
    }

    private void approveId(final String uid, DataSnapshot s) {
        final String hash = s.child("idHash").getValue(String.class);
        final String name = s.child("fullName").getValue(String.class);
        if (hash == null) { toast("Thiếu mã băm"); return; }
        root.child("idHashes").child(hash).get().addOnCompleteListener(t -> {
            if (t.isSuccessful() && t.getResult() != null && t.getResult().exists()) {
                toast("CCCD này đã gắn với tài khoản khác. Hãy từ chối.");
                return;
            }
            Map<String, Object> up = new HashMap<>();
            up.put("levels/" + uid, 1);
            up.put("verifiedIdentity/" + uid + "/name", name);
            up.put("verifiedIdentity/" + uid + "/at", ServerValue.TIMESTAMP);
            up.put("idHashes/" + hash, true);
            up.put("verifications/" + uid + "/status", "approved");
            up.put("verifications/" + uid + "/idImg", null);      // xóa ảnh ngay sau khi duyệt
            up.put("verifications/" + uid + "/selfieImg", null);
            up.put("verifications/" + uid + "/reviewedBy", me);
            root.updateChildren(up).addOnCompleteListener(w -> { toast(w.isSuccessful() ? "Đã duyệt" : "Lỗi: " + w.getException()); load(); });
        });
    }

    private void rejectId(String uid, String reason) {
        Map<String, Object> up = new HashMap<>();
        up.put("verifications/" + uid + "/status", "rejected");
        up.put("verifications/" + uid + "/reason", reason);
        up.put("verifications/" + uid + "/idImg", null);
        up.put("verifications/" + uid + "/selfieImg", null);
        up.put("verifications/" + uid + "/reviewedBy", me);
        root.updateChildren(up).addOnCompleteListener(w -> load());
    }

    // ---------- giấy khai sinh ----------
    private void loadDeps() {
        root.child("depQueue").orderByChild("status").equalTo("pending").get().addOnCompleteListener(t -> {
            if (!t.isSuccessful() || t.getResult() == null) return;
            if (t.getResult().getChildrenCount() == 0) secD.addView(UI.text(this, "Không có hồ sơ chờ."));
            for (final DataSnapshot s : t.getResult().getChildren()) {
                final String id = s.getKey();
                final String guardian = s.child("guardianUid").getValue(String.class);
                final String name = s.child("name").getValue(String.class);
                secD.addView(UI.text(this, "Trẻ: " + name + "\nNgười giám hộ (UID): " + guardian
                        + "\nĐối chiếu tên cha/mẹ trên giấy khai sinh với CCCD của người giám hộ (cấp 1)."));
                secD.addView(img(s.child("bcImg").getValue(String.class)));
                secD.addView(UI.btn(this, "Duyệt", v -> {
                    Map<String, Object> up = new HashMap<>();
                    up.put("verifiedIdentity/" + id + "/name", name);
                    up.put("verifiedIdentity/" + id + "/at", ServerValue.TIMESTAMP);
                    up.put("depQueue/" + id + "/status", "approved");
                    up.put("depQueue/" + id + "/bcImg", null);
                    root.updateChildren(up).addOnCompleteListener(w -> load());
                }));
                secD.addView(UI.btn(this, "Từ chối", v -> askReason(reason -> {
                    Map<String, Object> up = new HashMap<>();
                    up.put("depQueue/" + id + "/status", "rejected");
                    up.put("depQueue/" + id + "/reason", reason);
                    up.put("depQueue/" + id + "/bcImg", null);
                    root.updateChildren(up).addOnCompleteListener(w -> load());
                })));
            }
        });
    }

    // ---------- khiếu nại ----------
    private void loadReports() {
        root.child("reports").orderByChild("status").equalTo("open").get().addOnCompleteListener(t -> {
            if (!t.isSuccessful() || t.getResult() == null) return;
            if (t.getResult().getChildrenCount() == 0) secR.addView(UI.text(this, "Không có."));
            for (final DataSnapshot s : t.getResult().getChildren()) {
                final String id = s.getKey();
                final String from = s.child("from").getValue(String.class);
                final String type = s.child("type").getValue(String.class);
                final String target = s.child("targetUid").getValue(String.class);
                secR.addView(UI.text(this, "[" + type + "] từ " + from + (target == null || target.isEmpty() ? "" : "\nVề: " + target)
                        + "\n" + s.child("text").getValue(String.class)));
                if ("appeal".equals(type)) {
                    secR.addView(UI.btn(this, "Hoàn +" + Config.POINT_UNIT + " điểm cho người khiếu nại", v -> {
                        Map<String, Object> e = new HashMap<>();
                        e.put("delta", Config.POINT_UNIT);
                        e.put("reason", "admin_appeal");
                        e.put("at", ServerValue.TIMESTAMP);
                        root.child("pointLedger").child(from).push().setValue(e);
                        toast("Đã hoàn điểm");
                    }));
                }
                if (target != null && !target.isEmpty()) {
                    secR.addView(UI.btn(this, "Khóa 30 ngày người bị báo cáo", v -> lock(target, false)));
                }
                secR.addView(UI.btn(this, "Đánh dấu đã xử lý", v -> {
                    root.child("reports").child(id).child("status").setValue("closed").addOnCompleteListener(w -> load());
                }));
            }
        });
    }

    // ---------- thống kê ----------
    private void loadStats() {
        root.child("sosEvents").get().addOnCompleteListener(t -> {
            if (!t.isSuccessful() || t.getResult() == null) { secS.addView(UI.text(this, "Không đọc được sự kiện.")); return; }
            Map<String, int[]> m = new TreeMap<>();   // total, real, false, unknown
            for (DataSnapshot s : t.getResult().getChildren()) {
                String trig = s.child("trigger").getValue(String.class);
                if (trig == null) trig = "?";
                String r = s.child("outcome").child("victimSays").getValue(String.class);
                int[] a = m.get(trig);
                if (a == null) { a = new int[4]; m.put(trig, a); }
                a[0]++;
                if ("real".equals(r)) a[1]++; else if ("false_alarm".equals(r)) a[2]++; else a[3]++;
            }
            StringBuilder sb = new StringBuilder("trigger | tổng | thật | nhầm | chưa xác định | tỷ lệ nhầm\n");
            for (Map.Entry<String, int[]> e : m.entrySet()) {
                int[] a = e.getValue();
                int known = a[1] + a[2];
                sb.append(e.getKey()).append(" | ").append(a[0]).append(" | ").append(a[1]).append(" | ").append(a[2])
                        .append(" | ").append(a[3]).append(" | ")
                        .append(known == 0 ? "-" : (100 * a[2] / known) + "%").append("\n");
            }
            if (m.isEmpty()) sb.append("(chưa có sự kiện)");
            secS.addView(UI.text(this, sb.toString()));
        });
    }

    // ---------- tiện ích ----------
    private void lock(String uid, boolean permanent) {
        if (uid == null || uid.isEmpty()) { toast("Nhập UID"); return; }
        Map<String, Object> m = new HashMap<>();
        m.put("until", System.currentTimeMillis() + 30L * 86400000L);
        m.put("permanent", permanent);
        m.put("by", me);
        root.child("statusLocks").child(uid).setValue(m);
        toast(permanent ? "Đã khóa vĩnh viễn" : "Đã khóa 30 ngày");
    }

    private interface ReasonCb { void done(String reason); }

    private void askReason(final ReasonCb cb) {
        final EditText et = new EditText(this);
        et.setHint("Lý do");
        new AlertDialog.Builder(this).setTitle("Lý do từ chối").setView(et)
                .setPositiveButton("OK", (d, w) -> cb.done(et.getText().toString().trim()))
                .setNegativeButton("Hủy", null).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
