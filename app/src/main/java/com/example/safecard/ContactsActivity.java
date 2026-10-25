package com.example.safecard;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ContactsActivity extends AppCompatActivity {
    private LinearLayout secIncoming, secVerified, secPending, secProtecting;
    private EditText etRelName, etRelPhone;
    private DatabaseReference root;
    private String myUid, myPhone;
    private DataSnapshot snapLinks, snapSent, snapInv, snapProt;
    private final Map<DatabaseReference, ValueEventListener> listeners = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { finish(); return; }
        setContentView(R.layout.activity_contacts);
        secIncoming = findViewById(R.id.secIncoming);
        secVerified = findViewById(R.id.secVerified);
        secPending = findViewById(R.id.secPending);
        secProtecting = findViewById(R.id.secProtecting);
        etRelName = findViewById(R.id.etRelName);
        etRelPhone = findViewById(R.id.etRelPhone);

        myUid = u.getUid();
        myPhone = u.getPhoneNumber();
        root = FirebaseDatabase.getInstance().getReference();

        findViewById(R.id.btnInvite).setOnClickListener(v -> sendInvite());

        listen(root.child("relativeLinks").child(myUid), 0);
        listen(root.child("users").child(myUid).child("sentInvites"), 1);
        listen(root.child("users").child(myUid).child("protecting"), 3);
        if (myPhone != null) listen(root.child("invitesByPhone").child(PhoneUtil.key(myPhone)), 2);
    }

    private void listen(DatabaseReference ref, final int which) {
        ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                if (which == 0) snapLinks = s;
                else if (which == 1) snapSent = s;
                else if (which == 2) snapInv = s;
                else snapProt = s;
                render();
            }
            @Override public void onCancelled(DatabaseError e) { }
        };
        ref.addValueEventListener(l);
        listeners.put(ref, l);
    }

    private boolean expired(Long createdAt) {
        return createdAt != null && System.currentTimeMillis() - createdAt > Config.INVITE_DAYS * 86400000L;
    }

    // ---------------- gửi lời mời ----------------
    private void sendInvite() {
        String phone = PhoneUtil.normalize(etRelPhone.getText().toString());
        String nick = etRelName.getText().toString().trim();
        if (phone == null) { toast("Số điện thoại không hợp lệ"); return; }
        if (nick.isEmpty()) { toast("Nhập tên gọi người thân"); return; }
        if (phone.equals(myPhone)) { toast("Không thể tự thêm chính mình"); return; }
        String myName = LocalCache.getName(this);
        if (myName.isEmpty()) { toast("Hãy điền họ tên trong Hồ sơ y tế trước"); return; }

        String key = PhoneUtil.key(phone);
        String id = root.child("invitesByPhone").child(key).push().getKey();
        Map<String, Object> inv = new HashMap<>();
        inv.put("fromUid", myUid);
        inv.put("fromName", myName);
        inv.put("relName", nick);
        inv.put("status", "pending");
        inv.put("createdAt", ServerValue.TIMESTAMP);
        root.child("invitesByPhone").child(key).child(id).setValue(inv);

        Map<String, Object> mine = new HashMap<>();
        mine.put("phone", phone);
        mine.put("name", nick);
        mine.put("createdAt", ServerValue.TIMESTAMP);
        mine.put("inviteId", id);
        root.child("users").child(myUid).child("sentInvites").child(id).setValue(mine);

        etRelName.setText("");
        etRelPhone.setText("");
        toast("Đã gửi lời mời. Người thân cần cài app, đăng ký bằng số này và chấp nhận.");
    }

    // ---------------- hiển thị ----------------
    private void render() {
        secIncoming.removeAllViews();
        secVerified.removeAllViews();
        secPending.removeAllViews();
        secProtecting.removeAllViews();

        Set<String> verifiedPhones = new HashSet<>();
        if (snapLinks != null) {
            for (final DataSnapshot s : snapLinks.getChildren()) {
                String name = s.child("name").getValue(String.class);
                String phone = s.child("phone").getValue(String.class);
                if (phone != null) verifiedPhones.add(phone);
                secVerified.addView(row(name + " · " + phone + " ✓", "Gỡ", () ->
                        root.child("relativeLinks").child(myUid).child(s.getKey()).removeValue(), null, null));
            }
            if (snapLinks.getChildrenCount() == 0) secVerified.addView(note("Chưa có ai."));
        }

        int pend = 0;
        if (snapSent != null) {
            for (final DataSnapshot s : snapSent.getChildren()) {
                final String phone = s.child("phone").getValue(String.class);
                if (phone != null && verifiedPhones.contains(phone)) continue;
                Long t = s.child("createdAt").getValue(Long.class);
                boolean exp = expired(t);
                String name = s.child("name").getValue(String.class);
                pend++;
                secPending.addView(row(name + " · " + phone + (exp ? " (hết hạn)" : " (chờ chấp nhận)"), "Xóa", () -> {
                    String id = s.getKey();
                    if (phone != null) root.child("invitesByPhone").child(PhoneUtil.key(phone)).child(id).removeValue();
                    root.child("users").child(myUid).child("sentInvites").child(id).removeValue();
                }, null, null));
            }
        }
        if (pend == 0) secPending.addView(note("Không có."));

        int inc = 0;
        if (snapInv != null && myPhone != null) {
            for (final DataSnapshot s : snapInv.getChildren()) {
                if (!"pending".equals(s.child("status").getValue(String.class))) continue;
                if (expired(s.child("createdAt").getValue(Long.class))) continue;
                inc++;
                final String from = s.child("fromName").getValue(String.class);
                secIncoming.addView(row(from + " mời bạn làm người thân", "Chấp nhận", () -> accept(s), "Từ chối", () ->
                        root.child("invitesByPhone").child(PhoneUtil.key(myPhone)).child(s.getKey()).child("status").setValue("rejected")));
            }
        }
        if (inc == 0) secIncoming.addView(note("Không có."));

        if (snapProt != null) {
            for (final DataSnapshot s : snapProt.getChildren()) {
                secProtecting.addView(row(s.getValue(String.class) + "", "Rút lại", () -> {
                    root.child("relativeLinks").child(s.getKey()).child(myUid).removeValue();
                    root.child("users").child(myUid).child("protecting").child(s.getKey()).removeValue();
                }, null, null));
            }
            if (snapProt.getChildrenCount() == 0) secProtecting.addView(note("Không có."));
        }
    }

    private void accept(DataSnapshot inv) {
        String fromUid = inv.child("fromUid").getValue(String.class);
        String relName = inv.child("relName").getValue(String.class);
        String fromName = inv.child("fromName").getValue(String.class);
        if (fromUid == null) return;
        String phoneKey = PhoneUtil.key(myPhone);

        Map<String, Object> link = new HashMap<>();
        link.put("name", relName == null ? "Người thân" : relName);
        link.put("phone", myPhone);
        link.put("phoneKey", phoneKey);
        link.put("inviteId", inv.getKey());
        link.put("verifiedAt", ServerValue.TIMESTAMP);
        root.child("relativeLinks").child(fromUid).child(myUid).setValue(link);
        root.child("invitesByPhone").child(phoneKey).child(inv.getKey()).child("status").setValue("accepted");
        root.child("users").child(myUid).child("protecting").child(fromUid).setValue(fromName);
        toast("Đã chấp nhận. Bạn sẽ nhận cảnh báo SOS của " + fromName + ".");
    }

    // ---------------- tiện ích giao diện ----------------
    private View row(String text, String b1, final Runnable a1, String b2, final Runnable a2) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(tv);
        Button x = new Button(this);
        x.setText(b1);
        x.setOnClickListener(v -> a1.run());
        r.addView(x);
        if (b2 != null) {
            Button y = new Button(this);
            y.setText(b2);
            y.setOnClickListener(v -> a2.run());
            r.addView(y);
        }
        return r;
    }

    private View note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF888888);
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onDestroy() {
        for (Map.Entry<DatabaseReference, ValueEventListener> e : listeners.entrySet()) e.getKey().removeEventListener(e.getValue());
        super.onDestroy();
    }
}
