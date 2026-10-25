package com.example.safecard;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import android.app.NotificationManager;
import android.widget.EditText;

public class SetupActivity extends AppCompatActivity {
    private TextView tvPerm;
    private SwitchCompat swGuard;
    private boolean askedBg = false;

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                // Android 10+ : vị trí nền phải xin riêng sau khi đã có vị trí chính xác
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)
                        && Build.VERSION.SDK_INT >= 29
                        && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        && !askedBg) {
                    askedBg = true;
                    Toast.makeText(this, "Hãy chọn \"Luôn cho phép\" cho vị trí", Toast.LENGTH_LONG).show();
                    this.permLauncherBg.launch(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION});
                }
                refresh();
            });

    private final ActivityResultLauncher<String[]> permLauncherBg =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> refresh());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_setup);
        tvPerm = findViewById(R.id.tvPerm);
        swGuard = findViewById(R.id.swGuard);
        final EditText etCancel = findViewById(R.id.etCancel);
        final EditText etDuress = findViewById(R.id.etDuress);

        findViewById(R.id.btnLoc).setOnClickListener(v -> permLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}));
        findViewById(R.id.btnNotif).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) permLauncherBg.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});
            else toast("Máy này không cần xin quyền thông báo.");
        });
        findViewById(R.id.btnSms).setOnClickListener(v ->
                permLauncherBg.launch(new String[]{Manifest.permission.SEND_SMS}));
        findViewById(R.id.btnBattery).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        findViewById(R.id.btnFsi).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
            } else toast("Máy này không cần bước này.");
        });

        findViewById(R.id.btnSaveCodes).setOnClickListener(v -> {
            String c = etCancel.getText().toString(), d = etDuress.getText().toString();
            if (!c.matches("\\d{4}") || !d.matches("\\d{4}")) { toast("Mỗi mã gồm đúng 4 chữ số"); return; }
            if (c.equals(d)) { toast("Mã cưỡng ép phải khác mã hủy"); return; }
            CodeStore.save(this, c, d);
            etCancel.setText("");
            etDuress.setText("");
            toast("Đã lưu mã (dạng băm, chỉ trên máy này)");
            refresh();
        });

        findViewById(R.id.btnAcc).setOnClickListener(v -> {
            toast("Chọn SafeCard trong danh sách rồi bật. Nếu bị mờ: Thông tin ứng dụng > ⋮ > Cho phép cài đặt bị hạn chế.");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        SwitchCompat swShake = findViewById(R.id.swShake), swFall = findViewById(R.id.swFall);
        swShake.setChecked(LocalCache.getBool(this, "optShake", false));
        swFall.setChecked(LocalCache.getBool(this, "optFall", false));
        swShake.setOnCheckedChangeListener((x, on) -> { LocalCache.setBool(this, "optShake", on); restartGuard(); });
        swFall.setOnCheckedChangeListener((x, on) -> { LocalCache.setBool(this, "optFall", on); restartGuard(); });

        swGuard.setChecked(LocalCache.isGuardEnabled(this));
        swGuard.setOnCheckedChangeListener((btn, on) -> {
            if (on) {
                if (!CodeStore.hasCodes(this)) {
                    toast("Hãy lưu mã hủy và mã cưỡng ép trước");
                    swGuard.setChecked(false);
                    return;
                }
                LocalCache.setGuardEnabled(this, true);
                ContextCompat.startForegroundService(this, new Intent(this, GuardService.class));
            } else {
                LocalCache.setGuardEnabled(this, false);
                stopService(new Intent(this, GuardService.class));
            }
        });

        ((TextView) findViewById(R.id.tvGuide)).setText(
                "LƯU Ý QUAN TRỌNG\n"
                + "• Android (Pixel, Samsung) có sẵn SOS hệ thống cũng bằng bấm nguồn 5 lần. Hãy tắt trong Cài đặt hệ thống để không bị trùng.\n"
                + "• Xiaomi/Oppo/Vivo/Samsung hay tắt dịch vụ nền: bật \"Tự khởi động\", đặt pin \"Không hạn chế\" và khóa app trong màn hình đa nhiệm.\n"
                + "• Nếu cài từ file APK (Android 13+) mà quyền bị mờ: vào Cài đặt > Ứng dụng > SafeCard > ⋮ > \"Cho phép cài đặt bị hạn chế\".\n"
                + "• Màn hình đếm ngược trên khóa cần quyền thông báo toàn màn hình. Nếu không hiện được, SOS vẫn tự gửi sau 10 giây.\n"
                + "• SafeCard là công cụ hỗ trợ, không thay thế gọi 115/113.");
    }

    private void restartGuard() {
        if (!LocalCache.isGuardEnabled(this)) return;
        stopService(new Intent(this, GuardService.class));
        ContextCompat.startForegroundService(this, new Intent(this, GuardService.class));
    }

    private boolean granted(String p) {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batt = pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS);
        boolean fsi = Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager.class).canUseFullScreenIntent();
        String s = mark(granted(Manifest.permission.ACCESS_FINE_LOCATION)) + " Vị trí chính xác\n"
                + mark(Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) + " Vị trí \"Luôn cho phép\"\n"
                + mark(notif) + " Thông báo\n"
                + mark(granted(Manifest.permission.SEND_SMS)) + " Gửi SMS\n"
                + mark(batt) + " Bỏ tối ưu pin\n"
                + mark(fsi) + " Thông báo toàn màn hình\n"
                + mark(CodeStore.hasCodes(this)) + " Đã đặt mã hủy / mã cưỡng ép";
        tvPerm.setText(s);
    }

    private String mark(boolean ok) { return ok ? "✅" : "❌"; }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}
