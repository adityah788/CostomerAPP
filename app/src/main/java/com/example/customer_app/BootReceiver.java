package com.example.customer_app;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Log.d(TAG, "Device booted - Action: " + action);

            // Acquire WakeLock for faster execution
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartLock:BootLock");
            wakeLock.acquire(20000); // 20 seconds

            try {
                SharedPreferences sp = context.getSharedPreferences("CustomerAppPrefs", Context.MODE_PRIVATE);
                boolean isLoggedIn = sp.getBoolean("isLoggedIn", false);
                boolean wasPhoneLocked = sp.getBoolean("shouldLockPhone", false);

                if (isLoggedIn) {
                    // CRITICAL: If phone was locked before reboot, lock IMMEDIATELY
                    if (wasPhoneLocked) {
                        Log.d(TAG, "Phone was locked before reboot - locking NOW");

                        // Lock immediately (no delay)
                        lockPhoneImmediately(context);

                        // Lock again every 1 second for first 15 seconds
                        Handler handler = new Handler();
                        for (int i = 1; i <= 15; i++) {
                            final int delay = i * 1000; // Every 1 second
                            handler.postDelayed(() -> lockPhoneImmediately(context), delay);
                        }
                    }

                    // Start service immediately (no delay)
                    startServiceNow(context);
                } else {
                    Log.d(TAG, "User not logged in, service not started");
                }
            } finally {
                wakeLock.release();
            }
        }
    }

    private void startServiceNow(Context context) {
        try {
            Intent serviceIntent = new Intent(context, CommandListenerService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

            Log.d(TAG, "CommandListenerService started immediately after boot");
        } catch (Exception e) {
            Log.e(TAG, "Error starting service: " + e.getMessage());
        }
    }

    private void lockPhoneImmediately(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(context, LoanDeviceAdminReceiver.class);

            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow();
                Log.d(TAG, "Phone locked via BootReceiver");
            } else {
                Log.e(TAG, "Device Admin not active - cannot lock");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error locking phone: " + e.getMessage());
        }
    }
}