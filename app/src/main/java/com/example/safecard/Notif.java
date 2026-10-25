package com.example.safecard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

public class Notif {
    public static final String CH_GUARD = "guard";
    public static final String CH_ALERT = "alert";
    public static final String CH_INFO = "info";

    public static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel g = new NotificationChannel(CH_GUARD, "Bảo vệ SafeCard", NotificationManager.IMPORTANCE_LOW);
        g.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(g);

        NotificationChannel a = new NotificationChannel(CH_ALERT, "Cảnh báo SOS", NotificationManager.IMPORTANCE_HIGH);
        a.enableVibration(true);
        a.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(a);

        NotificationChannel i = new NotificationChannel(CH_INFO, "Thông tin", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(i);
    }

    public static void info(Context c, String title, String text, int id) {
        Notification n = new Notification.Builder(c, CH_INFO)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(id, n);
    }
}
