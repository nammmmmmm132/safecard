package com.example.safecard;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Lưu mã hủy và mã cưỡng ép dạng băm (SHA-256 + muối) trên máy, không đẩy lên server. */
public class CodeStore {
    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("safecard_codes", Context.MODE_PRIVATE);
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((salt + ":" + code).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasCodes(Context c) {
        return p(c).contains("cancelHash") && p(c).contains("duressHash");
    }

    public static void save(Context c, String cancel, String duress) {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        String salt = sb.toString();
        p(c).edit().putString("salt", salt)
                .putString("cancelHash", hash(salt, cancel))
                .putString("duressHash", hash(salt, duress))
                .apply();
    }

    /** 0 = sai, 1 = mã hủy thật, 2 = mã cưỡng ép. */
    public static int check(Context c, String code) {
        String salt = p(c).getString("salt", "");
        String h = hash(salt, code);
        if (h.equals(p(c).getString("cancelHash", "x"))) return 1;
        if (h.equals(p(c).getString("duressHash", "x"))) return 2;
        return 0;
    }
}
