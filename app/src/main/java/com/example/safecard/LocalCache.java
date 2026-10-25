package com.example.safecard;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Bản sao cục bộ của dữ liệu cần khi gửi SOS để SOS vẫn chạy khi mất mạng. */
public class LocalCache {
    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("safecard_prefs", Context.MODE_PRIVATE);
    }

    public static class Relative {
        public String uid, name, phone;
    }

    public static void setName(Context c, String n) { p(c).edit().putString("name", n == null ? "" : n).apply(); }
    public static String getName(Context c) { return p(c).getString("name", ""); }

    public static void setRelativesJson(Context c, String j) { p(c).edit().putString("relatives", j).apply(); }

    public static List<Relative> getRelatives(Context c) {
        List<Relative> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("relatives", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Relative r = new Relative();
                r.uid = o.optString("uid");
                r.name = o.optString("name");
                r.phone = o.optString("phone");
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void setSharedMedicalJson(Context c, String j) { p(c).edit().putString("medical", j).apply(); }
    public static String getSharedMedicalJson(Context c) { return p(c).getString("medical", "{}"); }

    public static boolean isGuardEnabled(Context c) { return p(c).getBoolean("guardEnabled", false); }
    public static void setGuardEnabled(Context c, boolean b) { p(c).edit().putBoolean("guardEnabled", b).apply(); }

    // ---- SOS đang hoạt động ----
    public static String getActiveEventId(Context c) {
        String id = p(c).getString("activeId", null);
        if (id == null) return null;
        long t = p(c).getLong("activeAt", 0);
        if (System.currentTimeMillis() - t > 24L * 3600 * 1000) {
            clearActive(c);
            return null;
        }
        return id;
    }

    public static String getActiveToken(Context c) { return p(c).getString("activeToken", ""); }

    public static void setActive(Context c, String id, String token) {
        p(c).edit().putString("activeId", id).putString("activeToken", token)
                .putLong("activeAt", System.currentTimeMillis()).apply();
    }

    public static void clearActive(Context c) {
        p(c).edit().remove("activeId").remove("activeToken").remove("activeAt").apply();
    }

    // ---- giới hạn SOS mỗi ngày ----
    private static String today() { return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); }

    public static int sosCountToday(Context c) {
        if (!today().equals(p(c).getString("sosDay", ""))) return 0;
        return p(c).getInt("sosCount", 0);
    }

    public static void incSosCount(Context c) {
        int n = sosCountToday(c) + 1;
        p(c).edit().putString("sosDay", today()).putInt("sosCount", n).apply();
    }

    public static void setInt(Context c, String k, int v) { p(c).edit().putInt(k, v).apply(); }
    public static int getInt(Context c, String k, int d) { return p(c).getInt(k, d); }
    public static void setLong(Context c, String k, long v) { p(c).edit().putLong(k, v).apply(); }
    public static long getLong(Context c, String k, long d) { return p(c).getLong(k, d); }
    public static void setBool(Context c, String k, boolean v) { p(c).edit().putBoolean(k, v).apply(); }
    public static boolean getBool(Context c, String k, boolean d) { return p(c).getBoolean(k, d); }
    public static void setStr(Context c, String k, String v) { p(c).edit().putString(k, v).apply(); }
    public static String getStr(Context c, String k, String d) { return p(c).getString(k, d); }

    public static void setVolMedicalJson(Context c, String j) { p(c).edit().putString("volMedical", j).apply(); }
    public static String getVolMedicalJson(Context c) { return p(c).getString("volMedical", "{}"); }
}
