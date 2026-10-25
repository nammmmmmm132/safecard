package com.example.safecard;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Hồ sơ phụ (con nhỏ chưa có CCCD...). Cần người giám hộ cấp 1; admin đối chiếu giấy khai sinh với CCCD người giám hộ. */
public class DependentsActivity extends AppCompatActivity {
    private File bcFile;
    private boolean hasBc = false;
    private LinearLayout listBox;
    private DatabaseReference ref;
    private ValueEventListener l;
    private String uid;

    private final ActivityResultLauncher<Uri> cam =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
                if (ok) { hasBc = true; toast("Đã chụp giấy khai sinh"); }
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { finish(); return; }
        uid = u.getUid();
        bcFile = ImageUtil.file(this, "birthcert.jpg");
        LinearLayout p = UI.page(this);
        p.addView(UI.title(this, "Hồ sơ phụ (con nhỏ / người thân)"));
        p.addView(UI.text(this, "Chỉ người giám hộ đã ở cấp 1 mới tạo được. Trẻ em chỉ ở cấp 0 và không làm tình nguyện viên. Giấy khai sinh dùng để xác nhận tên (không có ảnh, không chứng minh nhóm máu/dị ứng) và bị xóa sau khi duyệt."));
        final EditText etName = UI.edit(this, "Họ tên", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final EditText etDob = UI.edit(this, "Ngày sinh", InputType.TYPE_CLASS_TEXT);
        final EditText etBlood = UI.edit(this, "Nhóm máu", InputType.TYPE_CLASS_TEXT);
        final EditText etAllergy = UI.edit(this, "Dị ứng", InputType.TYPE_CLASS_TEXT);
        final EditText etCond = UI.edit(this, "Bệnh nền", InputType.TYPE_CLASS_TEXT);
        final EditText etNote = UI.edit(this, "Ghi chú", InputType.TYPE_CLASS_TEXT);
        p.addView(etName); p.addView(etDob); p.addView(etBlood); p.addView(etAllergy); p.addView(etCond); p.addView(etNote);
        p.addView(UI.btn(this, "Chụp giấy khai sinh", v -> cam.launch(ImageUtil.uri(this, bcFile))));
        final CheckBox consent = UI.check(this, "Tôi là cha/mẹ hoặc người giám hộ hợp pháp và đồng ý cho SafeCard xử lý dữ liệu của trẻ");
        p.addView(consent);
        p.addView(UI.btn(this, "Thêm hồ sơ", v -> {
            if (LevelManager.level(this) != 1) { toast("Cần tài khoản cấp 1 (xác minh CCCD)"); return; }
            if (!consent.isChecked()) { toast("Cần đồng ý của người giám hộ"); return; }
            String name = etName.getText().toString().trim();
            if (name.isEmpty() || !hasBc) { toast("Nhập họ tên và chụp giấy khai sinh"); return; }
            String img = ImageUtil.toBase64(bcFile);
            if (img == null) { toast("Không xử lý được ảnh"); return; }
            DatabaseReference root = FirebaseDatabase.getInstance().getReference();
            String id = root.child("dependents").child(uid).push().getKey();
            Map<String, Object> d = new HashMap<>();
            d.put("name", name);
            d.put("dob", etDob.getText().toString().trim());
            d.put("bloodType", etBlood.getText().toString().trim());
            d.put("allergies", etAllergy.getText().toString().trim());
            d.put("conditions", etCond.getText().toString().trim());
            d.put("note", etNote.getText().toString().trim());
            d.put("createdAt", ServerValue.TIMESTAMP);
            root.child("dependents").child(uid).child(id).setValue(d);
            Map<String, Object> q = new HashMap<>();
            q.put("guardianUid", uid);
            q.put("name", name);
            q.put("status", "pending");
            q.put("bcImg", img);
            q.put("submittedAt", ServerValue.TIMESTAMP);
            root.child("depQueue").child(id).setValue(q);
            toast("Đã thêm. Chờ admin xác nhận danh tính.");
            etName.setText(""); hasBc = false;
        }));
        p.addView(UI.text(this, "— Danh sách —"));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        p.addView(listBox);

        ref = FirebaseDatabase.getInstance().getReference().child("dependents").child(uid);
        l = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                listBox.removeAllViews();
                if (s.getChildrenCount() == 0) listBox.addView(UI.text(DependentsActivity.this, "Chưa có."));
                for (final DataSnapshot d : s.getChildren()) {
                    final String id = d.getKey();
                    final android.widget.TextView tv = UI.text(DependentsActivity.this, d.child("name").getValue(String.class) + " (đang kiểm tra trạng thái…)");
                    listBox.addView(tv);
                    FirebaseDatabase.getInstance().getReference().child("verifiedIdentity").child(id).child("name").get().addOnCompleteListener(t -> {
                        boolean ok = t.isSuccessful() && t.getResult() != null && t.getResult().exists();
                        tv.setText(d.child("name").getValue(String.class) + (ok ? " ✓ danh tính đã xác minh" : " (chưa xác minh)"));
                    });
                    listBox.addView(UI.btn(DependentsActivity.this, "Mã QR", v ->
                            startActivity(new Intent(DependentsActivity.this, QrActivity.class).putExtra(QrActivity.EXTRA_DEP, id))));
                    listBox.addView(UI.btn(DependentsActivity.this, "Xóa", v -> {
                        String tk = d.child("qrToken").getValue(String.class);
                        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
                        if (tk != null) {
                            root.child("publicCards").child(tk).child("data").removeValue();
                            root.child("publicCards").child(tk).child("uid").removeValue();
                        }
                        root.child("dependents").child(uid).child(id).removeValue();
                    }));
                }
            }
            @Override public void onCancelled(DatabaseError e) { }
        };
        ref.addValueEventListener(l);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onDestroy() {
        if (ref != null && l != null) ref.removeEventListener(l);
        super.onDestroy();
    }
}
