package com.example.customer_app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";
    private static final String CHANNEL_ID = "CommandChannel";

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private SharedPreferences sp;

    @Override
    public void onCreate() {
        super.onCreate();
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, LoanDeviceAdminReceiver.class);
        sp = getSharedPreferences("CustomerAppPrefs", MODE_PRIVATE);
        createNotificationChannel();
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "New FCM Token: " + token);

        // Save token locally
        sp.edit().putString("fcmToken", token).apply();

        // Upload to Firebase (in SubAdmins/{subAdminId}/buyers/{buildNumber}/fcmToken)
        uploadTokenToFirebase(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);

        Log.d(TAG, "FCM Message received from: " + message.getFrom());

        // Get data payload
        Map<String, String> data = message.getData();

        if (!data.isEmpty()) {
            String commandType = data.get("commandType");
            String commandValue = data.get("commandValue");

            Log.d(TAG, "Command Type: " + commandType + ", Value: " + commandValue);

            // Execute command immediately
            executeCommand(commandType, commandValue);

            // Show notification
            showNotification("Command Received", commandType + ": " + commandValue);
        }
    }

    private void executeCommand(String commandType, String commandValue) {
        if (commandType == null || commandValue == null) return;

        switch (commandType) {
            case "phone":
                if ("LOCK_PHONE".equals(commandValue)) {
                    lockPhone();
                } else if ("UNLOCK_PHONE".equals(commandValue)) {
                    unlockPhone();
                }
                break;

            case "settings":
                String[] settingsPackages = {
                        "com.android.settings",
                        "com.miui.settings",
                        "com.samsung.android.settings",
                        "com.huawei.settings",
                        "com.oppo.settings",
                        "com.oneplus.settings",
                        "com.realme.settings",
                        "com.vivo.settings",
                        "com.tecno.settings",
                        "com.transsion.settings",
                        "com.lenovo.settings",
                        "com.motorola.settings"
                };

                if ("LOCK_SETTINGS".equals(commandValue)) {
                    suspendApps(settingsPackages, true);
                } else if ("UNLOCK_SETTINGS".equals(commandValue)) {
                    suspendApps(settingsPackages, false);
                }
                break;

            case "whatsapp":
                handleAppLock(new String[]{"com.whatsapp"}, commandValue);
                break;

            case "youtube":
                handleAppLock(new String[]{"com.google.android.youtube"}, commandValue);
                break;

            case "facebook":
                handleAppLock(new String[]{"com.facebook.katana"}, commandValue);
                break;

            case "instagram":
                handleAppLock(new String[]{"com.instagram.android"}, commandValue);
                break;

            case "uninstall":
                if ("UNINSTALL_app".equals(commandValue)) {
                    allowAppUninstall();
                } else if ("PREV_UNINSTALL_app".equals(commandValue)) {
                    preventAppUninstall();
                }
                break;

            case "factoryreset":
                if ("FACTORY_RESET".equals(commandValue)) {
                    allowFactoryReset();
                } else if ("PREV_FACTORY_RESET".equals(commandValue)) {
                    preventFactoryReset();
                }
                break;
        }
    }

    private void lockPhone() {
        saveLockState(true);
        showFullscreenLock(true);
        Log.d(TAG, "Phone locked via FCM");
    }

    private void unlockPhone() {
        saveLockState(false);
        showFullscreenLock(false);
        Log.d(TAG, "Phone unlocked via FCM");
    }

    private void handleAppLock(String[] packages, String command) {
        if (command.startsWith("LOCK_APP_")) {
            suspendApps(packages, true);
        } else if (command.startsWith("UNLOCK_APP_")) {
            suspendApps(packages, false);
        }
    }

    private void suspendApps(String[] packages, boolean suspend) {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.setPackagesSuspended(adminComponent, packages, suspend);
            Log.d(TAG, (suspend ? "Suspended" : "Unsuspended") + " apps");
        }
    }

    private void preventAppUninstall() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
            Log.d(TAG, "App uninstall prevented");
        }
    }

    private void allowAppUninstall() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
            Log.d(TAG, "App uninstall allowed");
        }
    }

    private void preventFactoryReset() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS);
            Log.d(TAG, "Factory reset prevented");
        }
    }

    private void allowFactoryReset() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS);
            Log.d(TAG, "Factory reset allowed");
        }
    }

    private void saveLockState(boolean isLocked) {
        sp.edit().putBoolean("shouldLockPhone", isLocked).apply();
    }

    private void showFullscreenLock(boolean show) {
        Intent lockIntent = new Intent(this, BlockedAppOverlay.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        lockIntent.putExtra("showornot", show);
        startActivity(lockIntent);
    }

    private void uploadTokenToFirebase(String token) {
        String subAdminId = sp.getString("subAdminId", "");
        String buildNumber = sp.getString("buildNumber", "");

        if (!subAdminId.isEmpty() && !buildNumber.isEmpty()) {
            // Store in SubAdmins path for easy retrieval
            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("SubAdmins")
                    .child(subAdminId)
                    .child("buyers")
                    .orderByChild("phoneBuild")
                    .equalTo(buildNumber)
                    .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                            for (com.google.firebase.database.DataSnapshot buyerSnap : snapshot.getChildren()) {
                                buyerSnap.getRef().child("fcmToken").setValue(token);
                                Log.d(TAG, "FCM Token uploaded to Firebase");
                            }
                        }

                        @Override
                        public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                            Log.e(TAG, "Failed to upload token: " + error.getMessage());
                        }
                    });
        }
    }

    private void showNotification(String title, String message) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        if (nm != null) {
            nm.notify((int) System.currentTimeMillis(), builder.build());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Command Notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}