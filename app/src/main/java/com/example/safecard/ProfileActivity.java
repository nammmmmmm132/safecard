package com.example.safecard;

import android.os.Bundle;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.Map;

public class ProfileActivity extends AppCompatActivity {
    private static final String[][] FIELDS = {
            {"dob", "Ngày sinh (dd/mm/yyyy)"},
            {"bloodType", "Nhóm máu"},
            {"allergies", "Dị ứng"},
            {"conditions", "Bệnh nền"},
            {"medications", "Thuốc đang dùng"},
            {"insuranceNo", "Số bảo hiểm"},
            {"note", "Ghi chú"}
    };

    private final Map<String, EditText> edits = new HashMap<>();
    private final Map<String, CheckBox> rel = new HashMap<>(), vol = new HashMap<>(), qr = new HashMap<>();
    private EditText etName;
    private DatabaseReference userRef;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { finish(); return; }
        LinearLayout p = UI.page(this);
        p.addView(UI.title(this, "Hồ sơ y tế"));
        p.addView(UI.text(this, "Với mỗi trường, chọn ai được xem: người thân (khi có SOS), tình nguyện viên (chỉ sau khi họ bấm Nhận hỗ trợ), hoặc công khai trên thẻ QR. Họ tên của bạn luôn được chia sẻ với người thân. Thông tin y tế do bạn tự khai."));
        etName = UI.edit(this, "Họ tên", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        p.addView(etName);

        for (String[] f : FIELDS) {
            EditText et = UI.edit(this, f[1], InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            CheckBox c1 = UI.check(this, "Người thân được xem");
            CheckBox c2 = UI.check(this, "Tình nguyện viên được xem");
            CheckBox c3 = UI.check(this, "Hiện trên QR công khai");
            for (CheckBox c : new CheckBox[]{c1, c2, c3}) c.setTextSize(13);
            p.addView(et); p.addView(c1); p.addView(c2); p.addView(c3);
            edits.put(f[0], et); rel.put(f[0], c1); vol.put(f[0], c2); qr.put(f[0], c3);
        }
        p.addView(UI.btn(this, "Lưu", v -> save()));

        userRef = FirebaseDatabase.getInstance().getReference().child("users").child(u.getUid());
        userRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                String n = s.child("profile").child("name").getValue(String.class);
                if (n != null) etName.setText(n);
                for (String[] f : FIELDS) {
                    String v = s.child("medical").child(f[0]).getValue(String.class);
                    if (v != null) edits.get(f[0]).setText(v);
                    rel.get(f[0]).setChecked(Boolean.TRUE.equals(s.child("privacy").child(f[0]).getValue(Boolean.class)));
                    vol.get(f[0]).setChecked(Boolean.TRUE.equals(s.child("volPrivacy").child(f[0]).getValue(Boolean.class)));
                    qr.get(f[0]).setChecked(Boolean.TRUE.equals(s.child("qrPrivacy").child(f[0]).getValue(Boolean.class)));
                }
            }
            @Override public void onCancelled(DatabaseError e) { }
        });
    }

    private void save() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) { Toast.makeText(this, "Nhập họ tên", Toast.LENGTH_SHORT).show(); return; }
        Map<String, Object> up = new HashMap<>();
        up.put("profile/name", name);
        for (String[] f : FIELDS) {
            up.put("medical/" + f[0], edits.get(f[0]).getText().toString().trim());
            up.put("privacy/" + f[0], rel.get(f[0]).isChecked());
            up.put("volPrivacy/" + f[0], vol.get(f[0]).isChecked());
            up.put("qrPrivacy/" + f[0], qr.get(f[0]).isChecked());
        }
        userRef.updateChildren(up);
        LocalCache.setName(this, name);
        Toast.makeText(this, "Đã lưu. Nếu đã tạo QR, vào mục Mã QR bấm \"Tạo / cập nhật\" để làm mới thẻ.", Toast.LENGTH_LONG).show();
        finish();
    }
}
