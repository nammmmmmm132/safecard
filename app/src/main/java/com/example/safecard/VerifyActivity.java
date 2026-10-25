package com.example.safecard;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.File;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Nâng cấp lên cấp 1: chụp CCCD (OCR đọc số) + selfie, admin duyệt. */
public class VerifyActivity extends AppCompatActivity {
    private File idFile, selfieFile;
    private EditText etId, etName, etDob;
    private TextView tvStatus, tvOcr;
    private Button btnSubmit;
    private boolean hasId = false, hasSelfie = false;
    private long attempts = 0;
    private String curStatus = "";
    private DatabaseReference vRef;
    private ValueEventListener vL;

    private final ActivityResultLauncher<Uri> camId =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
                if (ok) { hasId = true; tvOcr.setText("Đang đọc ảnh…"); runOcr(); }
            });
    private final ActivityResultLauncher<Uri> camSelfie =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
                if (ok) { hasSelfie = true; toast("Đã chụp selfie"); }
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { finish(); return; }
        idFile = ImageUtil.file(this, "cccd.jpg");
        selfieFile = ImageUtil.file(this, "selfie.jpg");

        LinearLayout p = UI.page(this);
        p.addView(UI.title(this, "Xác minh danh tính (cấp 1)"));
        p.addView(UI.text(this, "Cấp 1 cho phép gửi cảnh báo cho người lạ trong 3 km và làm tình nguyện viên. Ảnh chỉ để admin duyệt và bị xóa ngay sau khi duyệt. Mỗi CCCD chỉ dùng cho một tài khoản. Tối đa 3 lần nộp."));
        tvStatus = UI.text(this, "Đang tải trạng thái…");
        p.addView(tvStatus);
        p.addView(UI.btn(this, "1. Chụp mặt trước CCCD", v -> camId.launch(ImageUtil.uri(this, idFile))));
        tvOcr = UI.text(this, "");
        p.addView(tvOcr);
        p.addView(UI.btn(this, "2. Chụp selfie", v -> camSelfie.launch(ImageUtil.uri(this, selfieFile))));
        etId = UI.edit(this, "Số CCCD (12 số)", InputType.TYPE_CLASS_NUMBER);
        etName = UI.edit(this, "Họ tên trên CCCD", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etDob = UI.edit(this, "Ngày sinh (dd/mm/yyyy)", InputType.TYPE_CLASS_TEXT);
        p.addView(etId);
        p.addView(etName);
        p.addView(etDob);
        btnSubmit = UI.btn(this, "3. Nộp cho admin duyệt", v -> submit(u));
        p.addView(btnSubmit);
        p.addView(UI.text(this, "Hạn chế: app không đối chiếu được với VNeID nên chặn được trùng lặp và giả mạo rõ ràng, không chặn được người dùng CCCD của người khác."));

        vRef = FirebaseDatabase.getInstance().getReference().child("verifications").child(u.getUid());
        vL = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                curStatus = s.child("status").getValue(String.class);
                if (curStatus == null) curStatus = "";
                Long a = s.child("attempts").getValue(Long.class);
                attempts = a == null ? 0 : a;
                String reason = s.child("reason").getValue(String.class);
                String t;
                switch (curStatus) {
                    case "pending": t = "⏳ Đang chờ admin duyệt."; break;
                    case "approved": t = "✅ Đã xác minh: tài khoản cấp 1."; break;
                    case "rejected": t = "❌ Bị từ chối" + (reason == null ? "" : ": " + reason) + ". Đã nộp " + attempts + "/3 lần."; break;
                    default: t = "Chưa nộp hồ sơ.";
                }
                tvStatus.setText(t);
                btnSubmit.setEnabled(!curStatus.equals("pending") && !curStatus.equals("approved") && attempts < 3);
            }
            @Override public void onCancelled(DatabaseError e) { tvStatus.setText("Không đọc được trạng thái."); }
        };
        vRef.addValueEventListener(vL);
    }

    private void runOcr() {
        Bitmap bmp = ImageUtil.load(idFile, 1600);
        if (bmp == null) { tvOcr.setText("Không đọc được ảnh."); return; }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(r -> {
                    String text = r.getText();
                    Matcher m = Pattern.compile("(?<!\\d)(\\d{12})(?!\\d)").matcher(text.replace(" ", ""));
                    if (m.find()) etId.setText(m.group(1));
                    Matcher d = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})").matcher(text);
                    if (d.find()) etDob.setText(d.group(1));
                    String[] lines = text.split("\n");
                    for (int i = 0; i < lines.length - 1; i++) {
                        String l = lines[i].toLowerCase();
                        if (l.contains("họ và tên") || l.contains("full name")) { etName.setText(lines[i + 1].trim()); break; }
                    }
                    tvOcr.setText("Đã đọc ảnh. Hãy kiểm tra và sửa các ô bên dưới cho đúng.");
                })
                .addOnFailureListener(e -> tvOcr.setText("Không đọc được chữ, hãy nhập tay."));
    }

    private String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((Config.ID_PEPPER + ":" + s).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private void submit(final FirebaseUser u) {
        final String id = etId.getText().toString().trim();
        final String name = etName.getText().toString().trim();
        final String dob = etDob.getText().toString().trim();
        if (!id.matches("\\d{12}")) { toast("Số CCCD gồm đúng 12 chữ số"); return; }
        if (name.isEmpty() || dob.isEmpty()) { toast("Nhập họ tên và ngày sinh"); return; }
        if (!hasId || !hasSelfie) { toast("Cần chụp cả CCCD và selfie"); return; }
        final String hash = sha256(id);
        btnSubmit.setEnabled(false);
        final DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        root.child("idHashes").child(hash).get().addOnCompleteListener(t -> {
            if (t.isSuccessful() && t.getResult() != null && t.getResult().exists()) {
                toast("CCCD này đã được dùng cho một tài khoản khác");
                btnSubmit.setEnabled(true);
                return;
            }
            String img = ImageUtil.toBase64(idFile), selfie = ImageUtil.toBase64(selfieFile);
            if (img == null || selfie == null) { toast("Không xử lý được ảnh"); btnSubmit.setEnabled(true); return; }
            Map<String, Object> m = new HashMap<>();
            m.put("type", "cccd");
            m.put("status", "pending");
            m.put("attempts", attempts + 1);
            m.put("submittedAt", ServerValue.TIMESTAMP);
            m.put("fullName", name);
            m.put("dob", dob);
            m.put("idLast4", id.substring(8));
            m.put("idHash", hash);
            m.put("idImg", img);
            m.put("selfieImg", selfie);
            vRef.setValue(m).addOnCompleteListener(w -> {
                if (w.isSuccessful()) { toast("Đã nộp. Chờ admin duyệt."); idFile.delete(); selfieFile.delete(); }
                else { toast("Nộp thất bại: " + (w.getException() == null ? "" : w.getException().getMessage())); btnSubmit.setEnabled(true); }
            });
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onDestroy() {
        if (vRef != null && vL != null) vRef.removeEventListener(vL);
        super.onDestroy();
    }
}
