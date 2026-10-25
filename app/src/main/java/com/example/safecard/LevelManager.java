package com.example.safecard;

import android.content.Context;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Cấp tài khoản, điểm uy tín, trạng thái khóa. Do không có Cloud Functions nên:
 *  - cấp (levels), khóa (statusLocks): chỉ admin ghi (luật Firebase)
 *  - điểm = 100 + tổng sổ cái pointLedger (trần 100, sàn 0), sổ cái chỉ ghi được theo luật nghiêm ngặt
 */
public class LevelManager {
    private static String startedUid = null;

    public static synchronized void start(Context c) {
        final Context ctx = c.getApplicationContext();
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null || u.getUid().equals(startedUid)) return;
        startedUid = u.getUid();
        String uid = u.getUid();
        DatabaseReference root = FirebaseDatabase.getInstance().getReference();

        root.child("levels").child(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                Long v = s.getValue(Long.class);
                LocalCache.setInt(ctx, "level", v == null ? 0 : v.intValue());
            }
            @Override public void onCancelled(DatabaseError e) { }
        });
        root.child("admins").child(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                LocalCache.setBool(ctx, "admin", Boolean.TRUE.equals(s.getValue(Boolean.class)));
            }
            @Override public void onCancelled(DatabaseError e) { }
        });
        root.child("statusLocks").child(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                Long until = s.child("until").getValue(Long.class);
                Boolean perm = s.child("permanent").getValue(Boolean.class);
                LocalCache.setLong(ctx, "lockUntil", until == null ? 0 : until);
                LocalCache.setBool(ctx, "lockPerm", Boolean.TRUE.equals(perm));
            }
            @Override public void onCancelled(DatabaseError e) { }
        });
        root.child("pointLedger").child(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                int p = 100, helps = 0;
                long lastAt = 0;
                for (DataSnapshot s : snap.getChildren()) {   // khóa là eventId/push id nên đã theo thứ tự thời gian
                    Long d = s.child("delta").getValue(Long.class);
                    if (d == null) continue;
                    p = Math.max(0, Math.min(100, p + d.intValue()));
                    if ("helped".equals(s.child("reason").getValue(String.class)) && d > 0) helps++;
                    Long at = s.child("at").getValue(Long.class);
                    if (at != null) lastAt = at;
                }
                LocalCache.setInt(ctx, "points", p);
                LocalCache.setInt(ctx, "helps", helps);
                // về 0 điểm: khóa tạm 30 ngày (chỉ chặn gửi cho người lạ, SOS người thân luôn hoạt động)
                LocalCache.setLong(ctx, "zeroLockUntil", p <= 0 ? lastAt + 30L * 86400000L : 0);
            }
            @Override public void onCancelled(DatabaseError e) { }
        });
    }

    public static int level(Context c) { return LocalCache.getInt(c, "level", 0); }
    public static int points(Context c) { return LocalCache.getInt(c, "points", 100); }
    public static int helps(Context c) { return LocalCache.getInt(c, "helps", 0); }
    public static boolean isAdmin(Context c) { return LocalCache.getBool(c, "admin", false); }

    public static boolean isLocked(Context c) {
        long now = System.currentTimeMillis();
        return LocalCache.getBool(c, "lockPerm", false)
                || LocalCache.getLong(c, "lockUntil", 0) > now
                || LocalCache.getLong(c, "zeroLockUntil", 0) > now;
    }
}
