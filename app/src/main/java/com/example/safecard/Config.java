package com.example.safecard;

public class Config {
    public static final int COUNTDOWN_SEC = 10;          // đếm ngược trước khi gửi (bấm nguồn 5 lần)
    public static final int HOLD_MS = 2000;              // giữ nút SOS bao lâu thì gửi
    public static final int POWER_PRESS_COUNT = 5;       // số lần bật/tắt màn hình
    public static final long POWER_WINDOW_MS = 4000;     // trong bao nhiêu ms
    public static final int SMS_DELAY_SEC = 30;          // sau bao lâu chưa ai nhận thì gửi SMS
    public static final int LOCATION_INTERVAL_MS = 10000;
    public static final int INVITE_DAYS = 7;
    public static final int MAX_SOS_PER_DAY = 5;
    // Điền sau khi deploy Firebase Hosting, ví dụ "https://safecard-abc12.web.app". Để trống = SMS chỉ có link Google Maps.
    public static final String TRACK_BASE_URL = "";

    public static final int POINT_UNIT = 20;             // +/- điểm mỗi lần (luật Firebase đang cố định 20)
    public static final int POINT_LOW = 60;              // dưới mức này phải xác nhận thêm khi gửi cho người lạ
    public static final int WAVE_SECONDS = 60;
    public static final double RADIUS_1_KM = 1.0;
    public static final double RADIUS_2_KM = 3.0;
    public static final int MAX_WAVE = 5;
    public static final int ARRIVE_METERS = 100;
    // "Muối" cố định trong app để băm CCCD (KHÔNG phải HMAC bí mật như đặc tả vì không có server). Đổi chuỗi này trước khi nộp.
    public static final String ID_PEPPER = "safecard-doi-chuoi-nay-truoc-khi-nop";
}
