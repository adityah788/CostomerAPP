package com.example.customer_app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessaging;

public class LoginActivity extends AppCompatActivity {

    private EditText etSubAdminId, etPassword, etBuildNumber;
    private Button btnLogin;
    private ProgressBar progressBar;
    private SharedPreferences sp;
    private DatabaseReference dbRef;

    private static final int REQUEST_CODE_ENABLE_ADMIN = 100;
    private static final int REQUEST_CODE_BATTERY_OPTIMIZATION = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        sp = getSharedPreferences("CustomerAppPrefs", MODE_PRIVATE);

        // Check if already logged in
//        if (sp.getBoolean("isLoggedIn", false)) {
//            navigateToMain();
//            return;
//        }

        getFCMToken(); // Add this


        etSubAdminId = findViewById(R.id.subID);
        etPassword = findViewById(R.id.et_password);
        etBuildNumber = findViewById(R.id.et_build);
        btnLogin = findViewById(R.id.btn_login);
        progressBar = findViewById(R.id.progress_bar);

        dbRef = FirebaseDatabase.getInstance().getReference();

        btnLogin.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String subAdminId = etSubAdminId.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String buildNumber = etBuildNumber.getText().toString().trim();

        if (subAdminId.isEmpty()) {
            etSubAdminId.setError("Required");
            return;
        }
        if (password.isEmpty()) {
            etPassword.setError("Required");
            return;
        }
        if (buildNumber.isEmpty()) {
            etBuildNumber.setError("Required");
            return;
        }

        showLoading(true);

        // Validate SubAdmin credentials
        dbRef.child("SubAdmins").child(subAdminId).child("pass")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            String correctPassword = snapshot.getValue(String.class);
                            if (password.equals(correctPassword)) {
                                // Valid credentials
                                saveLoginData(subAdminId, buildNumber);
                                requestBatteryOptimization();
                            } else {
                                showLoading(false);
                                Toast.makeText(LoginActivity.this, "Incorrect password", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            showLoading(false);
                            Toast.makeText(LoginActivity.this, "SubAdmin ID not found", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        showLoading(false);
                        Toast.makeText(LoginActivity.this, "Login failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void saveLoginData(String subAdminId, String buildNumber) {
        SharedPreferences.Editor editor = sp.edit();
        editor.putString("subAdminId", subAdminId);
        editor.putString("buildNumber", buildNumber);
        editor.putBoolean("isLoggedIn", true);
        editor.apply();
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                        .setTitle("Battery Optimization")
                        .setMessage("To work properly, this app needs to run in background. Please disable battery optimization.")
                        .setPositiveButton("OK", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_CODE_BATTERY_OPTIMIZATION);
                        })
                        .setCancelable(false)
                        .show();
            } else {
                requestDeviceAdmin();
            }
        } else {
            requestDeviceAdmin();
        }
    }

    private void requestDeviceAdmin() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = new ComponentName(this, LoanDeviceAdminReceiver.class);

        if (dpm.isAdminActive(adminComponent)) {
            // Already admin, proceed
            onDeviceAdminEnabled();
        } else {
            // Request admin permission
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "This app needs Device Admin permission to lock your phone remotely.");
            startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_BATTERY_OPTIMIZATION) {
            requestDeviceAdmin();
        } else if (requestCode == REQUEST_CODE_ENABLE_ADMIN) {
            if (resultCode == RESULT_OK) {
                onDeviceAdminEnabled();
            } else {
                showLoading(false);
                Toast.makeText(this, "Device Admin permission required", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void onDeviceAdminEnabled() {
        showLoading(false);
        Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show();

        // Start Command Listener Service
        Intent serviceIntent = new Intent(this, CommandListenerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        navigateToMain();
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!show);
    }


    private void getFCMToken() {
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        String token = task.getResult();
                        Log.d("FCM", "Token: " + token);

                        // Save locally
                        sp.edit().putString("fcmToken", token).apply();

                        // Upload to Firebase
                        uploadTokenToFirebase(token);
                    }
                });
    }

    private void uploadTokenToFirebase(String token) {
        String subAdminId = sp.getString("subAdminId", "");
        String buildNumber = sp.getString("buildNumber", "");

        if (!subAdminId.isEmpty() && !buildNumber.isEmpty()) {
            FirebaseDatabase.getInstance()
                    .getReference("SubAdmins")
                    .child(subAdminId)
                    .child("buyers")
                    .orderByChild("phoneBuild")
                    .equalTo(buildNumber)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            for (DataSnapshot buyerSnap : snapshot.getChildren()) {
                                buyerSnap.getRef().child("fcmToken").setValue(token);
                            }
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {}
                    });
        }
    }




}