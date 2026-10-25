package com.example.safecard;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import com.google.firebase.auth.FirebaseAuth;

/** Giữ đồng thời hai nút âm lượng 3 giây -> gửi SOS ngay (không đếm ngược). Cần người dùng bật trợ năng. */
public class VolumeHoldService extends AccessibilityService {
    private boolean up = false, down = false, scheduled = false;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable fire = new Runnable() {
        @Override public void run() {
            scheduled = false;
            if (up && down && FirebaseAuth.getInstance().getCurrentUser() != null) {
                Haptics.vibrate(VolumeHoldService.this, 300);
                SosManager.send(getApplicationContext(), "volume_hold");
            }
        }
    };

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean isDown = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_VOLUME_UP) up = isDown;
        else if (k == KeyEvent.KEYCODE_VOLUME_DOWN) down = isDown;
        else return false;
        if (up && down) {
            if (!scheduled) { scheduled = true; h.postDelayed(fire, 3000); }
        } else {
            h.removeCallbacks(fire);
            scheduled = false;
        }
        return false;   // vẫn để hệ thống chỉnh âm lượng bình thường
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
