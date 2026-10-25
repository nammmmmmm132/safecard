package com.example.safecard;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.database.FirebaseDatabase;
import java.util.concurrent.TimeUnit;

public class LoginActivity extends AppCompatActivity {
    private EditText etPhone, etCode;
    private Button btnSend, btnVerify;
    private TextView tvStatus;
    private FirebaseAuth auth;
    private String verificationId;

    private final PhoneAuthProvider.OnVerificationStateChangedCallbacks cb =
            new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                @Override public void onVerificationCompleted(PhoneAuthCredential c) { signIn(c); }
                @Override public void onVerificationFailed(FirebaseException e) {
                    tvStatus.setText("Lỗi: " + e.getMessage());
                    btnSend.setEnabled(true);
                }
                @Override public void onCodeSent(String id, PhoneAuthProvider.ForceResendingToken t) {
                    verificationId = id;
                    etCode.setVisibility(View.VISIBLE);
                    btnVerify.setVisibility(View.VISIBLE);
                    tvStatus.setText("Đã gửi mã OTP. Nhập mã để tiếp tục.");
                    btnSend.setEnabled(true);
                }
            };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) { goMain(); return; }
        setContentView(R.layout.activity_login);
        etPhone = findViewById(R.id.etPhone);
        etCode = findViewById(R.id.etCode);
        btnSend = findViewById(R.id.btnSend);
        btnVerify = findViewById(R.id.btnVerify);
        tvStatus = findViewById(R.id.tvStatus);

        btnSend.setOnClickListener(v -> {
            String p = PhoneUtil.normalize(etPhone.getText().toString());
            if (p == null) { tvStatus.setText("Số điện thoại không hợp lệ."); return; }
            btnSend.setEnabled(false);
            tvStatus.setText("Đang gửi OTP tới " + p + "…");
            PhoneAuthOptions o = PhoneAuthOptions.newBuilder(auth)
                    .setPhoneNumber(p)
                    .setTimeout(60L, TimeUnit.SECONDS)
                    .setActivity(this)
                    .setCallbacks(cb)
                    .build();
            PhoneAuthProvider.verifyPhoneNumber(o);
        });

        btnVerify.setOnClickListener(v -> {
            String code = etCode.getText().toString().trim();
            if (verificationId == null || code.length() < 6) { tvStatus.setText("Nhập đủ 6 số."); return; }
            signIn(PhoneAuthProvider.getCredential(verificationId, code));
        });
    }

    private void signIn(PhoneAuthCredential cred) {
        auth.signInWithCredential(cred).addOnCompleteListener(t -> {
            if (t.isSuccessful()) {
                FirebaseUser u = auth.getCurrentUser();
                if (u != null && u.getPhoneNumber() != null) {
                    FirebaseDatabase.getInstance().getReference().child("users").child(u.getUid())
                            .child("profile").child("phone").setValue(u.getPhoneNumber());
                }
                goMain();
            } else {
                tvStatus.setText("Đăng nhập thất bại: " + (t.getException() == null ? "" : t.getException().getMessage()));
            }
        });
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
