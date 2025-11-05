package com.example.customer_app;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private TextView tvStatus, tvSubAdminId, tvBuildNumber;
    private Button btnLogout;
    private SharedPreferences sp;

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;

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

        checkAndRequestLocationPermission();


        // Ensure service is running
        Intent serviceIntent = new Intent(this, CommandListenerService.class);
        startService(serviceIntent);

        btnLogout.setOnClickListener(v -> logout());
    }


    private void checkAndRequestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST_CODE
            );
        }
    }

    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Location permission granted ✅", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠ Location permission is required for tracking!", Toast.LENGTH_LONG).show();
            }
        }
    }




    private void logout() {
        // Stop services
        Intent serviceIntent = new Intent(this, CommandListenerService.class);
        stopService(serviceIntent);

//        Intent appBlockIntent = new Intent(this, AppBlockService.class);

//        stopService(appBlockIntent);

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