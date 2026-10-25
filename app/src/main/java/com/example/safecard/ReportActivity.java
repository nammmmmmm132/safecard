package com.example.safecard;

import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;

public class ReportActivity extends AppCompatActivity {
    private static final String[] LABELS = {"Khiếu nại điểm uy tín / bị khóa", "Báo cáo người dùng vi phạm", "Góp ý khác"};
    private static final String[] TYPES = {"appeal", "abuse", "other"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { finish(); return; }
        LinearLayout p = UI.page(this);
        p.addView(UI.title(this, "Khiếu nại / báo cáo"));
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, LABELS));
        p.addView(sp);
        final EditText etTarget = UI.edit(this, "ID người bị báo cáo (nếu có)", InputType.TYPE_CLASS_TEXT);
        final EditText etText = UI.edit(this, "Mô tả (ví dụ mã sự kiện, thời gian, lý do)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        p.addView(etTarget);
        p.addView(etText);
        p.addView(UI.text(this, "ID của bạn (gửi cho admin nếu cần): " + u.getUid()));
        p.addView(UI.btn(this, "Gửi", v -> {
            String t = etText.getText().toString().trim();
            if (t.isEmpty()) { Toast.makeText(this, "Nhập nội dung", Toast.LENGTH_SHORT).show(); return; }
            DatabaseReference r = FirebaseDatabase.getInstance().getReference().child("reports").push();
            Map<String, Object> m = new HashMap<>();
            m.put("from", u.getUid());
            m.put("type", TYPES[sp.getSelectedItemPosition()]);
            m.put("text", t);
            m.put("targetUid", etTarget.getText().toString().trim());
            m.put("status", "open");
            m.put("createdAt", ServerValue.TIMESTAMP);
            r.setValue(m);
            Toast.makeText(this, "Đã gửi cho admin", Toast.LENGTH_LONG).show();
            finish();
        }));
    }
}
