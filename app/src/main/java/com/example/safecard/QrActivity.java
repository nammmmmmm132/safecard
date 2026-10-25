package com.example.safecard;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/** Mã QR chứa liên kết tới "thẻ y tế" công khai (token ngẫu nhiên, thu hồi được). Dùng cho bản thân hoặc một hồ sơ phụ (trẻ nhỏ). */
public class QrActivity extends AppCompatActivity {
    public static final String EXTRA_DEP = "depId";

    private static final String[][] FIELDS = {
            {"bloodType", "Nhóm máu"}, {"allergies", "Dị ứng"}, {"conditions", "Bệnh nền nguy hiểm"},
            {"medications", "Thuốc đang dùng"}, {"dob", "Ngày sinh"}, {"insuranceNo", "Số bảo hiểm"}, {"note", "Ghi chú"}};
    private static final boolean[] DEFAULT_ON = {true, true, true, false, false, false, false};

    private final Map<String, CheckBox> checks = new HashMap<>();
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, Boolean> savedPriv = new HashMap<>();
    private String depId, uid, name = "", token = null, verifyKey;
    private DatabaseReference root, cardOwnerRef;
    private ImageView iv;
    private TextView tvInfo, tvIdent;
    private Bitmap qrBmp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { finish(); return; }
        uid = u.getUid();
        depId = getIntent().getStringExtra(EXTRA_DEP);
        verifyKey = depId == null ? uid : depId;
        root = FirebaseDatabase.getInstance().getReference();
        cardOwnerRef = depId == null ? root.child("users").child(uid) : root.child("dependents").child(uid).child(depId);

        LinearLayout p = UI.page(this);
        p.addView(UI.title(this, depId == null ? "Mã QR của tôi" : "Mã QR hồ sơ phụ"));
        tvInfo = UI.text(this, "Chọn trường được hiện công khai trên thẻ (mặc định: tên, nhóm máu, dị ứng, bệnh nền). Số điện thoại người thân KHÔNG hiện; người cứu hộ dùng nút \"Báo người thân\" trên trang thẻ.");
        p.addView(tvInfo);
        for (int i = 0; i < FIELDS.length; i++) {
            CheckBox cb = UI.check(this, FIELDS[i][1]);
            cb.setChecked(DEFAULT_ON[i]);
            checks.put(FIELDS[i][0], cb);
            p.addView(cb);
        }
        tvIdent = UI.text(this, "");
        p.addView(tvIdent);
        iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        p.addView(iv);
        p.addView(UI.btn(this, "Tạo / cập nhật mã QR", v -> publish(false)));
        p.addView(UI.btn(this, "Tạo mã MỚI (thu hồi mã cũ)", v -> publish(true)));
        p.addView(UI.btn(this, "Thu hồi mã QR", v -> revoke()));
        p.addView(UI.btn(this, "Chia sẻ ảnh QR", v -> share()));
        p.addView(UI.text(this, Config.TRACK_BASE_URL.isEmpty()
                ? "Chưa cấu hình TRACK_BASE_URL nên QR chứa trực tiếp văn bản (đọc được khi không có mạng, nhưng không thu hồi/cập nhật được và không có nút Báo người thân)."
                : "QR trỏ tới: " + Config.TRACK_BASE_URL + "/card.html"));

        load();
    }

    private void load() {
        cardOwnerRef.get().addOnCompleteListener(t -> {
            if (!t.isSuccessful() || t.getResult() == null) return;
            DataSnapshot s = t.getResult();
            if (depId == null) {
                name = s.child("profile").child("name").getValue(String.class);
                for (DataSnapshot m : s.child("medical").getChildren()) values.put(m.getKey(), m.getValue(String.class));
                for (DataSnapshot m : s.child("qrPrivacy").getChildren()) savedPriv.put(m.getKey(), m.getValue(Boolean.class));
            } else {
                name = s.child("name").getValue(String.class);
                for (DataSnapshot m : s.getChildren()) if (m.getValue() instanceof String) values.put(m.getKey(), (String) m.getValue());
                for (DataSnapshot m : s.child("qrPrivacy").getChildren()) savedPriv.put(m.getKey(), m.getValue(Boolean.class));
            }
            if (name == null) name = "";
            for (Map.Entry<String, Boolean> e : savedPriv.entrySet()) {
                CheckBox cb = checks.get(e.getKey());
                if (cb != null) cb.setChecked(Boolean.TRUE.equals(e.getValue()));
            }
            token = s.child("qrToken").getValue(String.class);
            if (name.isEmpty()) tvInfo.setText("Hãy điền họ tên trong hồ sơ trước khi tạo QR.");
            if (token != null) render();
            loadIdentity();
        });
    }

    private void loadIdentity() {
        root.child("verifiedIdentity").child(verifyKey).child("name").get().addOnCompleteListener(t -> {
            String vn = t.isSuccessful() && t.getResult() != null ? t.getResult().getValue(String.class) : null;
            tvIdent.setText((vn != null ? "Danh tính: ĐÃ XÁC MINH bởi admin (" + vn + ")" : "Danh tính: chưa xác minh")
                    + "\nThông tin y tế: chủ thẻ tự khai, chưa được cơ sở y tế xác nhận");
        });
    }

    private String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void publish(boolean fresh) {
        if (name.isEmpty()) { toast("Cần họ tên trong hồ sơ"); return; }
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("kind", depId == null ? "self" : "dependent");
        data.put("verifyKey", verifyKey);
        Map<String, Object> priv = new HashMap<>();
        for (String[] f : FIELDS) {
            boolean on = checks.get(f[0]).isChecked();
            priv.put(f[0], on);
            String v = values.get(f[0]);
            if (on && v != null && !v.trim().isEmpty()) data.put(f[0], v);
        }
        boolean isNew = fresh || token == null;
        if (fresh && token != null) revokeToken(token);
        if (isNew) {
            token = randomToken();
            root.child("publicCards").child(token).child("uid").setValue(uid);   // ghi uid trước để luật cho phép ghi data
        }
        root.child("publicCards").child(token).child("data").setValue(data);
        cardOwnerRef.child("qrToken").setValue(token);
        cardOwnerRef.child("qrPrivacy").setValue(priv);
        qrContentCache = null;
        qrText = data;
        render();
        toast("Đã cập nhật thẻ");
    }

    private Map<String, Object> qrText = null;
    private String qrContentCache = null;

    private void revokeToken(String t) {
        root.child("publicCards").child(t).child("data").removeValue();
        root.child("publicCards").child(t).child("uid").removeValue();
    }

    private void revoke() {
        if (token == null) return;
        revokeToken(token);
        cardOwnerRef.child("qrToken").removeValue();
        token = null;
        iv.setImageBitmap(null);
        toast("Đã thu hồi. Mã QR cũ không còn dùng được.");
    }

    private String content() {
        if (token == null) return "";
        if (!Config.TRACK_BASE_URL.isEmpty()) return Config.TRACK_BASE_URL + "/card.html?t=" + token;
        StringBuilder sb = new StringBuilder("SAFECARD\nTên: ").append(name);
        for (String[] f : FIELDS) {
            CheckBox cb = checks.get(f[0]);
            String v = values.get(f[0]);
            if (cb.isChecked() && v != null && !v.trim().isEmpty()) sb.append("\n").append(f[1]).append(": ").append(v);
        }
        sb.append("\n(Thông tin chủ thẻ tự khai)");
        return sb.toString();
    }

    private void render() {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix m = new MultiFormatWriter().encode(content(), BarcodeFormat.QR_CODE, 600, 600, hints);
            Bitmap bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < 600; x++) for (int y = 0; y < 600; y++) bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            qrBmp = bmp;
            iv.setImageBitmap(bmp);
        } catch (Exception e) {
            toast("Không tạo được QR (nội dung quá dài?). Bỏ bớt trường.");
        }
    }

    private void share() {
        if (qrBmp == null) { toast("Chưa có mã QR"); return; }
        try {
            File f = ImageUtil.file(this, "qr.png");
            FileOutputStream fo = new FileOutputStream(f);
            qrBmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            Intent i = new Intent(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, ImageUtil.uri(this, f))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Chia sẻ mã QR"));
        } catch (Exception e) {
            toast("Không chia sẻ được");
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
