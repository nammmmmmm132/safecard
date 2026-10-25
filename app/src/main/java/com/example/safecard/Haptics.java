package com.example.safecard;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

public class Haptics {
    private static Vibrator get(Context c) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm == null ? null : vm.getDefaultVibrator();
        }
        return (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public static void vibrate(Context c, long ms) {
        Vibrator v = get(c);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    public static void pattern(Context c, long[] p) {
        Vibrator v = get(c);
        if (v != null) v.vibrate(VibrationEffect.createWaveform(p, -1));
    }
}
