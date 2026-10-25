package com.example.safecard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Màn hình SOS (hiện được trên màn hình khóa). Chỉ có nút SOS / đếm ngược / trạng thái gửi.
 * KHÔNG hiện hồ sơ y tế hay danh bạ, KHÔNG gọi requestDismissKeyguard.
 */
public class SosActivity extends AppCompatActivity implements SensorEventListener {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_TRIGGER = "trigger";
    public static final String MODE_COUNTDOWN = "countdown";

    private View panelHold, panelCount, panelSent;
    private CircularProgressIndicator progress;
    private TextView tvCount, tvCountLabel, tvStatus, tvAlerts;
    private EditText etCode;
    private Button btnSafe, btnVol, btnChat;
    private DataSnapshot lastAlerts;

    private final Handler h = new Handler(Looper.getMainLooper());
    private String trigger = "shortcut_hold";
    private long shownAt;
    private boolean covered = false;
    private boolean fired = false;
    private boolean countdownActive = false;
    private SensorManager sm;
    private Sensor prox;
    private ValueAnimator anim;
    private CountDownTimer timer;
    private DatabaseReference alertsRef;
    private ValueEventListener alertsListener;
    private long sentShownAt;
    private boolean noReceiveWarned = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_sos);

        panelHold = findViewById(R.id.panelHold);
        panelCount = findViewById(R.id.panelCount);
        panelSent = findViewById(R.id.panelSent);
        progress = findViewById(R.id.progress);
        tvCount = findViewById(R.id.tvCount);
        tvCountLabel = findViewById(R.id.tvCountLabel);
        tvStatus = findViewById(R.id.tvStatus);
        tvAlerts = findViewById(R.id.tvAlerts);
        etCode = findViewById(R.id.etCode);
        btnSafe = findViewById(R.id.btnSafe);
        btnVol = findViewById(R.id.btnVol);
        btnChat = findViewById(R.id.btnChat);
        btnVol.setOnClickListener(v -> {
            SosManager.dispatchVolunteers(this, LocalCache.getActiveEventId(this));
            btnVol.setVisibility(View.GONE);
            Toast.makeText(this, "Đang tìm tình nguyện viên gần bạn…", Toast.LENGTH_LONG).show();
        });
        btnChat.setOnClickListener(v -> chooseChat());

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        findViewById(R.id.btnHoldClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        btnSafe.setOnClickListener(v -> askOutcome());

        findViewById(R.id.btnCircle).setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // chống chạm nhầm trong túi: bỏ qua nếu bị che cảm biến tiệm cận hoặc 500 ms đầu
                    if (covered || SystemClock.elapsedRealtime() - shownAt < 500) return true;
                    startHold();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelHold();
                    return true;
                default:
                    return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (countdownActive) return; // không cho thoát khi đang đếm ngược
                finish();
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (timer != null) timer.cancel();
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String t = intent.getStringExtra(EXTRA_TRIGGER);
        trigger = t == null ? "shortcut_hold" : t;
        fired = false;

        if (LocalCache.getActiveEventId(this) != null) {
            showSent();
        } else if (MODE_COUNTDOWN.equals(intent.getStringExtra(EXTRA_MODE))) {
            if (!SosManager.isPending()) { finish(); return; }
            startCountdown();
        } else {
            panelHold.setVisibility(View.VISIBLE);
            panelCount.setVisibility(View.GONE);
            panelSent.setVisibility(View.GONE);
        }
    }

    // ================= giữ 2 giây =================
    private void startHold() {
        Haptics.vibrate(this, 40);
        anim = ValueAnimator.ofInt(0, 100);
        anim.setDuration(Config.HOLD_MS);
        anim.addUpdateListener(a -> progress.setProgress((int) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) { if (!cancelled) onHoldComplete(); }
        });
        anim.start();
    }

    private void cancelHold() {
        if (anim != null) anim.cancel();
        progress.setProgress(0);
    }

    private void onHoldComplete() {
        if (fired) return;
        fired = true;
        Haptics.vibrate(this, 300);
        SosManager.send(getApplicationContext(), trigger);
        showSent();
    }

    // ================= đếm ngược 10 giây =================
    private void startCountdown() {
        panelHold.setVisibility(View.GONE);
        panelSent.setVisibility(View.GONE);
        panelCount.setVisibility(View.VISIBLE);
        countdownActive = true;
        etCode.setText("");
        etCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 4) checkCode(s.toString());
            }
        });
        timer = new CountDownTimer(Config.COUNTDOWN_SEC * 1000L, 250) {
            @Override public void onTick(long ms) { tvCount.setText(String.valueOf((ms + 999) / 1000)); }
            @Override public void onFinish() {
                tvCount.setText("0");
                countdownActive = false;
                SosManager.endPendingAndSend(getApplicationContext(), trigger);
                showSent();
            }
        }.start();
    }

    private void checkCode(String code) {
        int r = CodeStore.check(this, code);
        if (r == 0) {
            etCode.setText("");
            etCode.setHint("Sai mã, thử lại");
            Haptics.vibrate(this, 100);
            return;
        }
        if (timer != null) timer.cancel();
        countdownActive = false;
        if (r == 2) {
            // MÃ CƯỠNG ÉP: giả vờ đã hủy nhưng vẫn âm thầm gửi
            SosManager.silent = true;
            SosManager.endPendingAndSend(getApplicationContext(), trigger);
        } else {
            SosManager.cancelPending();
        }
        // Giao diện của cả hai trường hợp giống hệt nhau
        etCode.setVisibility(View.GONE);
        tvCountLabel.setText("Đã hủy SOS");
        tvCount.setText("✓");
        h.postDelayed(this::finish, 1500);
    }

    // ================= trạng thái đã gửi =================
    private void showSent() {
        panelHold.setVisibility(View.GONE);
        panelCount.setVisibility(View.GONE);
        panelSent.setVisibility(View.VISIBLE);
        sentShownAt = SystemClock.elapsedRealtime();
        poll.run();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            String id = LocalCache.getActiveEventId(SosActivity.this);
            if (id != null) {
                tvStatus.setText("✅ SOS đã được gửi");
                btnSafe.setVisibility(View.VISIBLE);
                btnChat.setVisibility(View.VISIBLE);
                btnVol.setVisibility(LevelManager.level(SosActivity.this) == 1 && !LevelManager.isLocked(SosActivity.this)
                        && !SosManager.isVolunteerDispatched() ? View.VISIBLE : View.GONE);
                if (alertsRef == null) attach(id);
                return;
            }
            if (SosManager.lastError != null) {
                tvStatus.setText("❌ " + SosManager.lastError);
                btnSafe.setVisibility(View.GONE);
                return;
            }
            btnSafe.setVisibility(View.GONE);
            h.postDelayed(this, 700);
        }
    };

    private void attach(String eventId) {
        alertsRef = FirebaseDatabase.getInstance().getReference().child("sosEvents").child(eventId).child("alerts");
        alertsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                lastAlerts = snap;
                StringBuilder sb = new StringBuilder();
                int reached = 0;
                for (DataSnapshot s : snap.getChildren()) {
                    String st = s.child("status").getValue(String.class);
                    String name = s.child("name").getValue(String.class);
                    boolean vol = "volunteer".equals(s.child("type").getValue(String.class));
                    Long pts = s.child("points").getValue(Long.class);
                    if (st != null && !"sent".equals(st)) reached++;
                    sb.append(name == null ? "Người thân" : name);
                    if (vol) sb.append(" (tình nguyện viên").append(pts == null ? "" : ", điểm " + pts).append(")");
                    sb.append(": ").append(label(st)).append("\n");
                }
                tvAlerts.setText(sb.toString().trim());
                if (reached == 0 && !noReceiveWarned
                        && SystemClock.elapsedRealtime() - sentShownAt > 60000) {
                    noReceiveWarned = true;
                    Haptics.pattern(SosActivity.this, new long[]{0, 400, 200, 400});
                    tvStatus.setText("⚠️ Chưa ai nhận được. Hãy thử gọi trực tiếp.");
                }
            }
            @Override public void onCancelled(DatabaseError e) { }
        };
        alertsRef.addValueEventListener(alertsListener);
        // kiểm tra "chưa ai nhận sau 60 giây"
        h.postDelayed(() -> { if (alertsRef != null) alertsRef.get(); }, 61000);
    }

    private String label(String st) {
        if (st == null || "sent".equals(st)) return "đã gửi";
        switch (st) {
            case "received": return "máy đã nhận";
            case "seen": return "đã xem";
            case "accepted": return "đang tới ✔";
            case "arrived": return "đã có mặt ✔✔";
            case "declined": return "không đến được";
            default: return st;
        }
    }

    private void askOutcome() {
        new AlertDialog.Builder(this)
                .setTitle("Kết quả sự việc")
                .setMessage("Vui lòng chọn để hệ thống thống kê báo nhầm.")
                .setCancelable(false)
                .setItems(new String[]{"Tôi đã cần giúp đỡ thật", "Báo nhầm"}, (d, which) -> {
                    if (which == 0) askWhoHelped(); else finishOutcome("false_alarm");
                })
                .show();
    }

    private void askWhoHelped() {
        final java.util.List<String> ids = new java.util.ArrayList<>();
        final java.util.List<String> names = new java.util.ArrayList<>();
        if (lastAlerts != null) {
            for (DataSnapshot s : lastAlerts.getChildren()) {
                if ("volunteer".equals(s.child("type").getValue(String.class))
                        && "arrived".equals(s.child("status").getValue(String.class))) {
                    ids.add(s.getKey());
                    String n = s.child("name").getValue(String.class);
                    names.add(n == null ? "Tình nguyện viên" : n);
                }
            }
        }
        if (ids.isEmpty()) { finishOutcome("real"); return; }
        final boolean[] checked = new boolean[ids.size()];
        new AlertDialog.Builder(this)
                .setTitle("Ai đã đến giúp bạn?")
                .setCancelable(false)
                .setMultiChoiceItems(names.toArray(new String[0]), checked, (d, i, c) -> checked[i] = c)
                .setPositiveButton("Xong", (d, w) -> {
                    String eid = LocalCache.getActiveEventId(this);
                    for (int i = 0; i < ids.size(); i++) if (checked[i]) SosManager.confirmHelp(this, eid, ids.get(i));
                    finishOutcome("real");
                })
                .show();
    }

    private void finishOutcome(String result) {
        SosManager.resolve(getApplicationContext(), result);
        finish();
    }

    private void chooseChat() {
        final String eid = LocalCache.getActiveEventId(this);
        if (eid == null || lastAlerts == null) { Toast.makeText(this, "Chưa có ai", Toast.LENGTH_SHORT).show(); return; }
        final java.util.List<String> ids = new java.util.ArrayList<>();
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (DataSnapshot s : lastAlerts.getChildren()) {
            String st = s.child("status").getValue(String.class);
            if ("accepted".equals(st) || "arrived".equals(st)) {
                ids.add(s.getKey());
                String n = s.child("name").getValue(String.class);
                names.add(n == null ? "Người thân" : n);
            }
        }
        if (ids.isEmpty()) { Toast.makeText(this, "Chưa ai nhận. Khi có người đang tới, bạn nhắn tin được.", Toast.LENGTH_LONG).show(); return; }
        new AlertDialog.Builder(this).setTitle("Nhắn tin với")
                .setItems(names.toArray(new String[0]), (d, w) -> startActivity(new Intent(this, ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_EVENT, eid).putExtra(ChatActivity.EXTRA_RID, ids.get(w))))
                .show();
    }

    // ================= cảm biến tiệm cận =================
    @Override
    protected void onResume() {
        super.onResume();
        shownAt = SystemClock.elapsedRealtime();
        if (prox != null) sm.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (prox != null) sm.unregisterListener(this);
        cancelHold();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        covered = e.values[0] < Math.min(3f, prox.getMaximumRange());
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    @Override
    protected void onDestroy() {
        if (alertsRef != null && alertsListener != null) alertsRef.removeEventListener(alertsListener);
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
