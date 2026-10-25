package com.example.safecard;

import android.app.Application;
import com.google.firebase.database.FirebaseDatabase;

public class SafeCardApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // Giữ hàng đợi ghi khi mất mạng: SOS được đẩy lên ngay khi có lại mạng
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (Exception ignored) {
        }
        Notif.createChannels(this);
    }
}
