package com.example.safecard;

public class PhoneUtil {
    /** Chuẩn hóa về dạng +84xxxxxxxxx. Trả về null nếu không hợp lệ. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^0-9+]", "");
        if (s.startsWith("+")) {
            // giữ nguyên
        } else if (s.startsWith("0")) {
            s = "+84" + s.substring(1);
        } else if (s.startsWith("84")) {
            s = "+" + s;
        } else {
            return null;
        }
        if (s.length() < 11 || s.length() > 15) return null;
        return s;
    }

    /** Khóa dùng trong database: bỏ dấu +. */
    public static String key(String e164) {
        return e164.startsWith("+") ? e164.substring(1) : e164;
    }
}
