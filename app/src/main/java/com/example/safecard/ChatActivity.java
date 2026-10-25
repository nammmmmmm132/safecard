package com.example.safecard;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
import java.util.Map;

/** Nhắn tin trong app giữa người gặp nạn và một người đang tới (không lộ số điện thoại). */
public class ChatActivity extends AppCompatActivity {
    public static final String EXTRA_EVENT = "eventId";
    public static final String EXTRA_RID = "rid";

    private DatabaseReference ref;
    private ValueEventListener listener;
    private LinearLayout list;
    private ScrollView sv;
    private String myUid;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        String eventId = getIntent().getStringExtra(EXTRA_EVENT);
        String rid = getIntent().getStringExtra(EXTRA_RID);
        if (u == null || eventId == null || rid == null) { finish(); return; }
        myUid = u.getUid();
        ref = FirebaseDatabase.getInstance().getReference().child("sosEvents").child(eventId).child("chat").child(rid);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = UI.dp(this, 12);
        root.setPadding(pad, pad, pad, pad);
        root.addView(UI.title(this, "Nhắn tin"));
        sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final EditText et = UI.edit(this, "Nhập tin nhắn…", InputType.TYPE_CLASS_TEXT);
        root.addView(et);
        root.addView(UI.btn(this, "Gửi", v -> {
            String t = et.getText().toString().trim();
            if (t.isEmpty()) return;
            Map<String, Object> m = new HashMap<>();
            m.put("from", myUid);
            m.put("text", t);
            m.put("at", ServerValue.TIMESTAMP);
            ref.push().setValue(m);
            et.setText("");
        }));
        setContentView(root);

        listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                list.removeAllViews();
                for (DataSnapshot m : s.getChildren()) {
                    boolean mine = myUid.equals(m.child("from").getValue(String.class));
                    TextView t = UI.text(ChatActivity.this, (mine ? "Tôi: " : "Họ: ") + m.child("text").getValue(String.class));
                    t.setBackgroundColor(mine ? 0xFFE3F2FD : 0xFFEEEEEE);
                    list.addView(t);
                }
                sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
            }
            @Override public void onCancelled(DatabaseError e) { }
        };
        ref.addValueEventListener(listener);
    }

    @Override
    protected void onDestroy() {
        if (ref != null && listener != null) ref.removeEventListener(listener);
        super.onDestroy();
    }
}
