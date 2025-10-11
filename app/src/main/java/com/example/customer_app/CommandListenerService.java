package com.example.customer_app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class CommandListenerService extends Service {

    private static final String TAG = "CommandListenerService";
    private static final String CHANNEL_ID = "SmartLockChannel";
    private static final int NOTIFICATION_ID = 1;

    private DatabaseReference commandsRef;
    private ValueEventListener commandListener;
    private SharedPreferences sp;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private PowerManager powerManager;
    private Handler lockHandler;
    private Runnable lockRunnable;

    private boolean shouldLockPhone = false;
    private boolean shouldLockSettings = false;
    private String blockedAppPackage = null;

    private BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                Log.d(TAG, "Screen turned ON");
                if (shouldLockPhone) {
                    // Wait 500ms then lock again
                    lockHandler.postDelayed(() -> lockPhone(), 500);
                }
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                Log.d(TAG, "User unlocked device");
                if (shouldLockPhone) {
                    // User unlocked, lock again immediately
                    lockHandler.postDelayed(() -> lockPhone(), 300);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service Created");

        sp = getSharedPreferences("CustomerAppPrefs", MODE_PRIVATE);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, LoanDeviceAdminReceiver.class);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        lockHandler = new Handler();

        // Register screen on/off listener
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, filter);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service Started");

        String subAdminId = sp.getString("subAdminId", null);
        String buildNumber = sp.getString("buildNumber", null);

        if (subAdminId == null || buildNumber == null) {
            Log.e(TAG, "Missing login data - Service stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        String encodedBuildNumber = encodeFirebasePath(buildNumber);

        commandsRef = FirebaseDatabase.getInstance()
                .getReference("buyers")
                .child(encodedBuildNumber)
                .child("commands");

        startListening();

        return START_STICKY; // Auto-restart if killed
    }

    private void startListening() {
        if (commandListener != null) {
            commandsRef.removeEventListener(commandListener);
        }

        commandListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Log.d(TAG, "No commands found");
                    return;
                }

                Log.d(TAG, "Command received: " + snapshot.toString());

                // PHONE LOCK/UNLOCK
                DataSnapshot phoneSnapshot = snapshot.child("phone");
                if (phoneSnapshot.exists()) {
                    String command = phoneSnapshot.child("command").getValue(String.class);
                    if ("LOCK_PHONE".equals(command)) {
                        shouldLockPhone = true;
                        saveLockState(true);
                        showFullscreenLock();
                    } else if ("UNLOCK_PHONE".equals(command)) {
                        shouldLockPhone = false;
                        saveLockState(false);
                        // Fullscreen lock activity will auto-close when it detects unlock
                    }
                }

                // SETTINGS LOCK/UNLOCK
                DataSnapshot settingsSnapshot = snapshot.child("settings");
                if (settingsSnapshot.exists()) {
                    String command = settingsSnapshot.child("command").getValue(String.class);
                    if ("LOCK_SETTINGS".equals(command)) {
                        shouldLockSettings = true;
                        Log.d(TAG, "Settings lock command received");
                        startAppBlockService("settings");
                    } else if ("UNLOCK_SETTINGS".equals(command)) {
                        shouldLockSettings = false;
                        Log.d(TAG, "Settings unlock command received");
                        stopAppBlockService();
                    }
                }

                // WHATSAPP LOCK/UNLOCK
                DataSnapshot whatsappSnapshot = snapshot.child("whatsapp");
                if (whatsappSnapshot.exists()) {
                    String command = whatsappSnapshot.child("command").getValue(String.class);
                    if ("LOCK_APP_whatsapp".equals(command)) {
                        blockedAppPackage = "com.whatsapp";
                        startAppBlockService(blockedAppPackage);
                    } else if ("UNLOCK_APP_whatsapp".equals(command)) {
                        stopAppBlockService();
                    }
                }

                // YOUTUBE LOCK/UNLOCK
                DataSnapshot youtubeSnapshot = snapshot.child("youtube");
                if (youtubeSnapshot.exists()) {
                    String command = youtubeSnapshot.child("command").getValue(String.class);
                    if ("LOCK_APP_youtube".equals(command)) {
                        blockedAppPackage = "com.google.android.youtube";
                        startAppBlockService(blockedAppPackage);
                    } else if ("UNLOCK_APP_youtube".equals(command)) {
                        stopAppBlockService();
                    }
                }

                // FACEBOOK LOCK/UNLOCK
                DataSnapshot facebookSnapshot = snapshot.child("facebook");
                if (facebookSnapshot.exists()) {
                    String command = facebookSnapshot.child("command").getValue(String.class);
                    if ("LOCK_APP_facebook".equals(command)) {
                        blockedAppPackage = "com.facebook.katana";
                        startAppBlockService(blockedAppPackage);
                    } else if ("UNLOCK_APP_facebook".equals(command)) {
                        stopAppBlockService();
                    }
                }

                // INSTAGRAM LOCK/UNLOCK
                DataSnapshot instagramSnapshot = snapshot.child("instagram");
                if (instagramSnapshot.exists()) {
                    String command = instagramSnapshot.child("command").getValue(String.class);
                    if ("LOCK_APP_instagram".equals(command)) {
                        blockedAppPackage = "com.instagram.android";
                        startAppBlockService(blockedAppPackage);
                    } else if ("UNLOCK_APP_instagram".equals(command)) {
                        stopAppBlockService();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Firebase error: " + error.getMessage());
            }
        };

        commandsRef.addValueEventListener(commandListener);
        Log.d(TAG, "Started listening for commands");
    }

    private void startAggressiveLocking() {
        Log.d(TAG, "Starting aggressive locking mode");

        // Lock immediately
        lockPhone();

        // Setup continuous checking
        if (lockRunnable != null) {
            lockHandler.removeCallbacks(lockRunnable);
        }

        lockRunnable = new Runnable() {
            @Override
            public void run() {
                if (shouldLockPhone) {
                    // Check if screen is on
                    if (powerManager.isInteractive()) {
                        Log.d(TAG, "Screen is on, locking again");
                        lockPhone();
                    }
                    // Check again in 2 seconds
                    lockHandler.postDelayed(this, 2000);
                }
            }
        };

        lockHandler.postDelayed(lockRunnable, 2000);
    }

    private void stopAggressiveLocking() {
        Log.d(TAG, "Stopping aggressive locking mode");
        if (lockRunnable != null) {
            lockHandler.removeCallbacks(lockRunnable);
        }
    }

    private void lockPhone() {
        if (dpm.isAdminActive(adminComponent)) {
            dpm.lockNow();
            Log.d(TAG, "Phone locked");
        } else {
            Log.e(TAG, "Device Admin not active - cannot lock");
        }
    }

    private void startAppBlockService(String packageName) {
        Intent intent = new Intent(this, AppBlockService.class);
        intent.putExtra("blocked_package", packageName);
        startService(intent);
        Log.d(TAG, "App block started for: " + packageName);
    }

    private void stopAppBlockService() {
        Intent intent = new Intent(this, AppBlockService.class);
        stopService(intent);
        Log.d(TAG, "App block stopped");
    }

    private String encodeFirebasePath(String path) {
        if (path == null) return null;
        return path
                .replace(".", "%2E")
                .replace("#", "%23")
                .replace("$", "%24")
                .replace("[", "%5B")
                .replace("]", "%5D")
                .replace("/", "%2F");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Smart Lock Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the app running to listen for commands");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Smart Lock Active")
                .setContentText("Monitoring device status...")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver: " + e.getMessage());
        }

        if (commandsRef != null && commandListener != null) {
            commandsRef.removeEventListener(commandListener);
        }

        if (lockHandler != null && lockRunnable != null) {
            lockHandler.removeCallbacks(lockRunnable);
        }

        Log.d(TAG, "Service Destroyed");

        // Restart service if still logged in
        SharedPreferences sp = getSharedPreferences("CustomerAppPrefs", MODE_PRIVATE);
        if (sp.getBoolean("isLoggedIn", false)) {
            Intent restartIntent = new Intent(this, CommandListenerService.class);
            startService(restartIntent);
            Log.d(TAG, "Service restarted after destroy");
        }
    }
    private void saveLockState(boolean isLocked) {
        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean("shouldLockPhone", isLocked);
        editor.apply();
        Log.d(TAG, "Lock state saved: " + isLocked);
    }

    private void showFullscreenLock() {
        Intent lockIntent = new Intent(this, BlockedAppOverlay.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(lockIntent);
        Log.d(TAG, "Fullscreen lock activity launched");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "Task removed - restarting service");

        // Restart service when app is removed from recents
        Intent restartIntent = new Intent(getApplicationContext(), CommandListenerService.class);
        startService(restartIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}