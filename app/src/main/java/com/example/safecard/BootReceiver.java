package com.example.safecard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && LocalCache.isGuardEnabled(c)) {
            try {
                ContextCompat.startForegroundService(c, new Intent(c, GuardService.class));
            } catch (Exception ignored) {
            }
        }
    }
}
