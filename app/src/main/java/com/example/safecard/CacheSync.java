package com.example.safecard;

import android.content.Context;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import org.json.JSONArray;
import org.json.JSONObject;

/** Giữ bản sao cục bộ (người thân, tên, hồ sơ y tế được chia sẻ) luôn mới. */
public class CacheSync {
    private static String startedUid = null;

    public static synchronized void start(Context c) {
        final Context ctx = c.getApplicationContext();
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        LevelManager.start(ctx);
        if (u.getUid().equals(startedUid)) return;
        startedUid = u.getUid();
        final String uid = u.getUid();
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();

        root.child("relativeLinks").child(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                JSONArray arr = new JSONArray();
                try {
                    for (DataSnapshot s : snap.getChildren()) {
                        JSONObject o = new JSONObject();
                        o.put("uid", s.getKey());
                        o.put("name", s.child("name").getValue(String.class));
                        o.put("phone", s.child("phone").getValue(String.class));
                        arr.put(o);
                    }
                } catch (Exception ignored) {
                }
                LocalCache.setRelativesJson(ctx, arr.toString());
            }
            @Override public void onCancelled(DatabaseError e) { }
        });

        root.child("users").child(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String name = snap.child("profile").child("name").getValue(String.class);
                if (name != null) LocalCache.setName(ctx, name);
                JSONObject rel = new JSONObject(), vol = new JSONObject();
                try {
                    for (DataSnapshot m : snap.child("medical").getChildren()) {
                        String v = m.getValue(String.class);
                        if (v == null || v.trim().isEmpty()) continue;
                        if (Boolean.TRUE.equals(snap.child("privacy").child(m.getKey()).getValue(Boolean.class))) rel.put(m.getKey(), v);
                        if (Boolean.TRUE.equals(snap.child("volPrivacy").child(m.getKey()).getValue(Boolean.class))) vol.put(m.getKey(), v);
                    }
                } catch (Exception ignored) {
                }
                LocalCache.setSharedMedicalJson(ctx, rel.toString());
                LocalCache.setVolMedicalJson(ctx, vol.toString());
            }
            @Override public void onCancelled(DatabaseError e) { }
        });
    }
}
