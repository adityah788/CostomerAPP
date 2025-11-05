package com.example.customer_app;

import static androidx.core.content.ContextCompat.startActivity;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                Intent.ACTION_SCREEN_ON.equals(action)
        ) {

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
                        lockPhoneImmediately(context , true);

                        // Lock again every 1 second for first 15 seconds
                        Handler handler = new Handler();
                        for (int i = 1; i <= 15; i++) {
                            final int delay = i * 1000; // Every 1 second
                            handler.postDelayed(() -> lockPhoneImmediately(context , true), delay);


                        }
                    }

                    // Start service immediately (no delay)
                    startServiceNow(context);
                    updateBuyerLocation(context);

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

    private void lockPhoneImmediately(Context context ,boolean showornot) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(context, LoanDeviceAdminReceiver.class);

            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow();

//                Intent lockIntent = new Intent(context, BlockedAppOverlay.class);
//                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
//                        Intent.FLAG_ACTIVITY_CLEAR_TASK);
//                lockIntent.putExtra("showornot" , showornot);
//                context.startActivity(lockIntent);
//                Log.d(TAG, "Fullscreen lock activity launched");
                Log.d(TAG, "Phone locked via BootReceiver");

            } else {
                Log.e(TAG, "Device Admin not active - cannot lock");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error locking phone: " + e.getMessage());
        }
    }




//    private void lockPhoneImmediately(Context context) {
//        try {
//            if (Build.MANUFACTURER.equalsIgnoreCase("samsung")) {
//                Log.d(TAG, "Skipping DevicePolicyManager lock on Samsung to avoid Knox issues");
//                return;
//            }
//
//            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
//            ComponentName adminComponent = new ComponentName(context, LoanDeviceAdminReceiver.class);
//
//            if (dpm.isAdminActive(adminComponent)) {
//                dpm.lockNow();
//                Log.d(TAG, "Phone locked via BootReceiver");
//            } else {
//                Log.e(TAG, "Device Admin not active - cannot lock");
//            }
//        } catch (Exception e) {
//            Log.e(TAG, "Error locking phone: " + e.getMessage());
//        }
//    }




    private void updateBuyerLocation(Context context) {
        SharedPreferences sp = context.getSharedPreferences("CustomerAppPrefs", Context.MODE_PRIVATE);
        String subAdminId = sp.getString("subAdminId", "");
        String buildNumber = sp.getString("buildNumber", "");

        if (subAdminId.isEmpty() || buildNumber.isEmpty()) {
            Log.e(TAG, "Missing SubAdminId or buildNumber — cannot update location");
            return;
        }

        DatabaseReference dbRef = FirebaseDatabase.getInstance().getReference();

        // Permission check (requires app to have location permission declared)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted — cannot update location");
            return;
        }

        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        Location location = null;
        try {
            location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null)
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            Log.e(TAG, "Error getting location: " + e.getMessage());
        }

        if (location != null) {
            double lat = location.getLatitude();
            double lon = location.getLongitude();

            Log.d(TAG, "Updating buyer location: lat=" + lat + ", lon=" + lon);

            // Find and update buyer record using phoneBuild
            dbRef.child("SubAdmins").child(subAdminId).child("buyers")
                    .orderByChild("phoneBuild").equalTo(buildNumber)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            for (DataSnapshot buyerSnap : snapshot.getChildren()) {
                                buyerSnap.getRef().child("latitude").setValue(lat);
                                buyerSnap.getRef().child("longitude").setValue(lon);
                            }
                            Log.d(TAG, "Buyer location updated successfully");
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {

                        }
                    });
        } else {
            Log.e(TAG, "Location is null — GPS may be off");
        }
    }


}