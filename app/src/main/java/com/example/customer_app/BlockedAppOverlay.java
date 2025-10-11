package com.example.customer_app;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.os.Handler;

public class BlockedAppOverlay extends AppCompatActivity {
    private boolean shouldClose = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_blocked_app_overlay);

        TextView tvMessage = findViewById(R.id.blockedmessage);
        String blockedApp = getIntent().getStringExtra("blocked_app");
        String appName = getAppName(blockedApp);

        tvMessage.setText("Access to " + appName + " is currently restricted by your SubAdmin.");

//        // Auto close after 2 seconds
//        tvMessage.postDelayed(this::finish, 2000);

        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                SharedPreferences sp = getSharedPreferences("CustomerAppPrefs", MODE_PRIVATE);
                boolean lockSettings = sp.getBoolean("shouldLockSettings", false);
                if (!lockSettings) {
                    shouldClose = true;
                    finish();
                } else {
                    handler.postDelayed(this, 500);
                }
            }
        }, 500);
    }

    private String getAppName(String packageName) {
        if (packageName == null) return "this app";

        switch (packageName) {
            case "com.whatsapp":
                return "WhatsApp";
            case "com.facebook.katana":
                return "Facebook";
            case "com.instagram.android":
                return "Instagram";
            case "com.google.android.youtube":
                return "YouTube";
            case "settings":
                return "Settings";
            default:
                return "this app";
        }
    }
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        // Prevent back button
        super.onBackPressed();
        moveTaskToBack(true);
    }

}