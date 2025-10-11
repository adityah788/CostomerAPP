package com.example.customer_app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private TextView tvStatus, tvSubAdminId, tvBuildNumber;
    private Button btnLogout;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences("CustomerAppPrefs", MODE_PRIVATE);

        tvStatus = findViewById(R.id.status);
        tvSubAdminId = findViewById(R.id.subadmin2);
        tvBuildNumber = findViewById(R.id.build2);
        btnLogout = findViewById(R.id.btn_logout);

        String subAdminId = sp.getString("subAdminId", "N/A");
        String buildNumber = sp.getString("buildNumber", "N/A");

        tvSubAdminId.setText("SubAdmin: " + subAdminId);
        tvBuildNumber.setText("Device: " + buildNumber);

        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = new ComponentName(this, LoanDeviceAdminReceiver.class);


        if (dpm.isAdminActive(adminComponent)) {
            tvStatus.setText("✓ Device Admin Active\n✓ Listening for commands...");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            tvStatus.setText("⚠ Device Admin Inactive");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        }

        // Ensure service is running
        Intent serviceIntent = new Intent(this, CommandListenerService.class);
        startService(serviceIntent);

        btnLogout.setOnClickListener(v -> logout());
    }

    private void logout() {
        // Stop services
        Intent serviceIntent = new Intent(this, CommandListenerService.class);
        stopService(serviceIntent);

        Intent appBlockIntent = new Intent(this, AppBlockService.class);
        stopService(appBlockIntent);

        // Clear login data
        SharedPreferences.Editor editor = sp.edit();
        editor.clear();
        editor.apply();

        Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();

        // Go to login
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
    }
}