package com.example.customer_app;

import android.Manifest;
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
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
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
    private boolean showingOverlay;

    private boolean shouldLockPhone = false;
    private boolean shouldLockSettings = false;
    private String blockedAppPackage = null;


    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private double lastLat = 0.0, lastLon = 0.0;
    private boolean firstUpdate = true;

    private BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                Log.d(TAG, "Screen turned ON");
                if (shouldLockPhone) {
                    // Wait 500ms then lock again
//                    lockHandler.postDelayed(() -> lockPhone(), 500);
                    lockHandler.postDelayed(() -> showFullscreenLock(true), 500);

                }
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                Log.d(TAG, "User unlocked device");
                if (shouldLockPhone) {
                    // User unlocked, lock again immediately
//                    lockHandler.postDelayed(() -> lockPhone(), 300);
                    lockHandler.postDelayed(() -> showFullscreenLock(true), 300);

                }
            } else if ("android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
                // Handle Quick Boot power-on
                if (shouldLockPhone) {
                    // User unlocked, lock again immediately
//                    lockHandler.postDelayed(() -> lockPhone(), 300);
                    lockHandler.postDelayed(() -> showFullscreenLock(true), 300);

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

        showingOverlay= shouldLockPhone;

        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean("showingBlockedAppOverlay", showingOverlay);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        startLocationUpdates();

    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Location permission not granted");
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 10000
        )
                .setMinUpdateIntervalMillis(5000)
                .setWaitForAccurateLocation(true)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult == null) {
                    Log.w(TAG, "⚠️ onLocationResult: null result");
                    return;
                }

                Location location = locationResult.getLastLocation();
                if (location == null) {
                    Log.w(TAG, "⚠️ Location is null");
                    return;
                }

                double lat = location.getLatitude();
                double lon = location.getLongitude();

                Log.d(TAG, "📍 Location: " + lat + ", " + lon);

                // Always update Firebase every 30 seconds or if moved significantly
                if (firstUpdate || distanceChanged(lat, lon)) {
                    firstUpdate = false;
                    lastLat = lat;
                    lastLon = lon;
                    updateBuyerLocation(lat, lon);
                }
            }
        };

        Log.d(TAG, "🚀 Starting location updates...");
        fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                android.os.Looper.getMainLooper()
        );
    }

    private boolean distanceChanged(double newLat, double newLon) {
        double diffLat = Math.abs(newLat - lastLat);
        double diffLon = Math.abs(newLon - lastLon);
        return (diffLat > 0.00005 || diffLon > 0.00005); // ~5m difference
    }

    private void updateBuyerLocation(double lat, double lon) {
        SharedPreferences sp = getSharedPreferences("CustomerAppPrefs", MODE_PRIVATE);
        String subAdminId = sp.getString("subAdminId", "");
        String buildNumber = sp.getString("buildNumber", "");

        if (subAdminId.isEmpty() || buildNumber.isEmpty()) {
            Log.e(TAG, "⚠️ Missing SubAdminId or BuildNumber — cannot update location");
            return;
        }

        DatabaseReference dbRef = FirebaseDatabase.getInstance().getReference()
                .child("SubAdmins")
                .child(subAdminId)
                .child("buyers");

        dbRef.orderByChild("phoneBuild").equalTo(buildNumber)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!snapshot.exists()) {
                            Log.w(TAG, "⚠️ No buyer found for buildNumber: " + buildNumber);
                            return;
                        }

                        for (DataSnapshot buyerSnap : snapshot.getChildren()) {
                            buyerSnap.getRef().child("latitude").setValue(lat);
                            buyerSnap.getRef().child("longitude").setValue(lon);
                        }

                        Log.d(TAG, "✅ Location updated in Firebase: " + lat + ", " + lon);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "❌ Firebase update error: " + error.getMessage());
                    }
                });
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
                        showFullscreenLock(true);
//                        lockPhone();


                    } else if ("UNLOCK_PHONE".equals(command)) {

                        if (shouldLockPhone){
                            shouldLockPhone = false;
                            saveLockState(false);
                            showFullscreenLock(false);

                            // Fullscreen lock activity will auto-close when it detects unlock
                        }

                    }
                }

                // SETTINGS LOCK/UNLOCK
                DataSnapshot settingsSnapshot = snapshot.child("settings");
                if (settingsSnapshot.exists()) {
                    String command = settingsSnapshot.child("command").getValue(String.class);
                    String[] blockedAppPackages = {
                            "com.android.settings",
                            "com.miui.settings",
                            "com.samsung.android.settings",
                            "com.huawei.settings",
                            "com.oneplus.settings",
                            "com.oppo.settings",
                            "com.realme.settings",
                            "com.vivo.settings",
                            "com.tecno.settings",
                            "com.transsion.settings",
                            "com.lenovo.settings",
                            "com.motorola.settings",
                    };

                    if ("LOCK_SETTINGS".equals(command)) {
//                        shouldLockSettings = true;
//                        lockDangerousSettings();
                        dpm.setPackagesSuspended(adminComponent,blockedAppPackages,true);
                        Log.d(TAG, "Settings lock command received");
//                        startAppBlockService("settings");
                    } else if ("UNLOCK_SETTINGS".equals(command)) {
//                        shouldLockSettings = false;
                        Log.d(TAG, " Settings unlock command received");
                        dpm.setPackagesSuspended(adminComponent,blockedAppPackages,false);
//                        unlockDangerousSettings();
//                        stopAppBlockService();
                    }
                }

                // WHATSAPP LOCK/UNLOCK
                DataSnapshot whatsappSnapshot = snapshot.child("whatsapp");
                if (whatsappSnapshot.exists()) {
                    String command = whatsappSnapshot.child("command").getValue(String.class);
                    String[] blockedAppPackages = {"com.whatsapp"};

                    if ("LOCK_APP_whatsapp".equals(command)) {
//                        blockedAppPackage = "com.whatsapp";
//                        startAppBlockService(blockedAppPackage);
                        dpm.setPackagesSuspended(adminComponent,blockedAppPackages,true);


                    } else if ("UNLOCK_APP_whatsapp".equals(command)) {
//                        stopAppBlockService();
                        dpm.setPackagesSuspended(adminComponent,blockedAppPackages,false);

                    }
                }

                // YOUTUBE LOCK/UNLOCK
                DataSnapshot youtubeSnapshot = snapshot.child("youtube");
                if (youtubeSnapshot.exists()) {
                    String command = youtubeSnapshot.child("command").getValue(String.class);
//                    blockedAppPackage = "com.google.android.youtube";
                    String[] blockedAppPackages = {"com.google.android.youtube"};

                    if ("LOCK_APP_youtube".equals(command)) {
//                        blockedAppPackage = "com.google.android.youtube";
//                        startAppBlockService(blockedAppPackage);
                        dpm.setPackagesSuspended(adminComponent,blockedAppPackages,true);
                    } else if ("UNLOCK_APP_youtube".equals(command)) {
//                        stopAppBlockService();
                        dpm.setPackagesSuspended(adminComponent,blockedAppPackages,false);

                    }
                }

                // FACEBOOK LOCK/UNLOCK
                DataSnapshot facebookSnapshot = snapshot.child("facebook");
                if (facebookSnapshot.exists()) {
                    String command = facebookSnapshot.child("command").getValue(String.class);
                    String[] blockedAppPackages = {"com.facebook.katana"};

                    if ("LOCK_APP_facebook".equals(command)) {
//                        blockedAppPackage = "com.facebook.katana";
//                        startAppBlockService(blockedAppPackage);
                        dpm.setPackagesSuspended(adminComponent,blockedAppPackages,true);

                    } else if ("UNLOCK_APP_facebook".equals(command)) {
//                        stopAppBlockService();
                        dpm.setPackagesSuspended(adminComponent,blockedAppPackages,false);

                    }
                }

                // INSTAGRAM LOCK/UNLOCK
                DataSnapshot instagramSnapshot = snapshot.child("instagram");
                if (instagramSnapshot.exists()) {
                    String command = instagramSnapshot.child("command").getValue(String.class);
                    String[] blockedAppPackages = {"com.instagram.android"};

                    if ("LOCK_APP_instagram".equals(command)) {
//                        blockedAppPackage = "com.instagram.android";
//                        startAppBlockService(blockedAppPackage);
                        dpm.setPackagesSuspended(adminComponent,blockedAppPackages,true);

                    } else if ("UNLOCK_APP_instagram".equals(command)) {
//                        stopAppBlockService();
                        dpm.setPackagesSuspended(adminComponent,blockedAppPackages,false);

                    }
                }



                // Unistall App LOCK/UNLOCK
                DataSnapshot unistallappSnapshot = snapshot.child("uninstall");
                if (unistallappSnapshot.exists()) {
                    String command = unistallappSnapshot.child("command").getValue(String.class);
                    if ("UNINSTALL_app".equals(command)) {
                        AppUninstall();
                        Log.d(TAG, "Giving permission for Unistalling App");
                    } else if ("PREV_UNINSTALL_app".equals(command)) {
                        PrevAppUninstall();
                        Log.d(TAG, "Preventing User from  Unistalling App");
//                        unlockDangerousSettings();
//                        stopAppBlockService();
                    }
                }


                // Factory Reset LOCK/UNLOCK
                DataSnapshot factoryresetappSnapshot = snapshot.child("factoryreset");
                if (factoryresetappSnapshot.exists()) {
                    String command = factoryresetappSnapshot.child("command").getValue(String.class);
                    if ("FACTORY_RESET".equals(command)) {
                        AllowFactoryReset();

                        Log.d(TAG, "Giving permission for Factory reset App");
                    } else if ("PREV_FACTORY_RESET".equals(command)) {
                        PrevFactoryReset();
                        Log.d(TAG, "Preventing User from Factory reset App");
//                        unlockDangerousSettings();
//                        stopAppBlockService();
                    }
                }


                // GAccount Allow/Disallow
                DataSnapshot gaccountappSnapshot = snapshot.child("gaccount");
                if (gaccountappSnapshot.exists()) {
                    String command = gaccountappSnapshot.child("command").getValue(String.class);
                    if ("ALLOW_GOOGLE_ACCOUNT".equals(command)) {
                        AllowGAccount();

                        Log.d(TAG, "Giving permission for Factory reset App");
                    } else if ("PREV_GOOGLE_ACCOUNT".equals(command)) {
                        PrevGAccount();
                        Log.d(TAG, "Preventing User from Factory reset App");
//                        unlockDangerousSettings();
//                        stopAppBlockService();
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
//        lockPhone();
        showFullscreenLock(true);


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
//                        lockPhone();
                        showFullscreenLock(true);

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
//            dpm.lockNow();
            showFullscreenLock(true);

            Log.d(TAG, "Phone locked");
        } else {
            Log.e(TAG, "Device Admin not active - cannot lock");
        }
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

//        try {
//            unregisterReceiver(screenReceiver);
//        } catch (Exception e) {
//            Log.e(TAG, "Error unregistering receiver: " + e.getMessage());
//        }

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

    private void showFullscreenLock(boolean showornot) {
        Intent lockIntent = new Intent(this, BlockedAppOverlay.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TASK);
        lockIntent.putExtra("showornot" , showornot);
        startActivity(lockIntent);
        Log.d(TAG, "Fullscreen lock activity launched");
    }


    // ------------------- SETTINGS LOCK -------------------


    private void PrevAppUninstall() {
        if (!dpm.isAdminActive(adminComponent)) {
            Log.e(TAG, "Device Admin not active - cannot lock settings");
            return;
        }


        try {
            // 2. Disable App Uninstall (prevents user from removing the DPC or other apps)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
            Log.d(TAG, "Restriction applied: DISALLOW_UNINSTALL_APPS");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to apply DISALLOW_UNINSTALL_APPS restriction. Error: " + e.getMessage());
        }


        // Note: Individual try-catch blocks are used to ensure that a failure in one
        // restriction doesn't prevent the others from being attempted.
    }


    private void AppUninstall() {
        if (!dpm.isAdminActive(adminComponent)) {
            Log.e(TAG, "Device Admin not active - cannot unlock settings");
            return;
        }


        try {
            // 2. Re-enable App Uninstall
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
            Log.d(TAG, "Restriction cleared: DISALLOW_UNINSTALL_APPS");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to clear DISALLOW_UNINSTALL_APPS restriction. Error: " + e.getMessage());
        }

    }



    private void PrevFactoryReset() {
        if (!dpm.isAdminActive(adminComponent)) {
            Log.e(TAG, "Device Admin not active - cannot lock settings");
            return;
        }

        try {
            // 1. Disable Factory Reset
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            Log.d(TAG, "Restriction applied: DISALLOW_FACTORY_RESET");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to apply DISALLOW_FACTORY_RESET restriction. Error: " + e.getMessage());
        }

//        try {
//            // 3. Disable Modification of accounts (to prevent removal of critical accounts)
//            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS);
//            Log.d(TAG, "Restriction applied: DISALLOW_MODIFY_ACCOUNTS");
//        } catch (SecurityException e) {
//            Log.e(TAG, "Failed to apply DISALLOW_MODIFY_ACCOUNTS restriction. Error: " + e.getMessage());
//        }

        // Note: Individual try-catch blocks are used to ensure that a failure in one
        // restriction doesn't prevent the others from being attempted.
    }



    private void AllowFactoryReset() {
        if (!dpm.isAdminActive(adminComponent)) {
            Log.e(TAG, "Device Admin not active - cannot unlock settings");
            return;
        }

        try {
            // 1. Re-enable Factory Reset
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            Log.d(TAG, "Restriction cleared: DISALLOW_FACTORY_RESET");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to clear DISALLOW_FACTORY_RESET restriction. Error: " + e.getMessage());
        }


//        try {
//            // 3. Re-enable Modification of accounts
//            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS);
//            Log.d(TAG, "Restriction cleared: DISALLOW_MODIFY_ACCOUNTS");
//        } catch (SecurityException e) {
//            Log.e(TAG, "Failed to clear DISALLOW_MODIFY_ACCOUNTS restriction. Error: " + e.getMessage());
//        }
    }



    private void AllowGAccount() {
        if (!dpm.isAdminActive(adminComponent)) {
            Log.e(TAG, "Device Admin not active - cannot unlock settings");
            return;
        }



        try {
            // 3. Re-enable Modification of accounts
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS);
            Log.d(TAG, "Restriction cleared: DISALLOW_MODIFY_ACCOUNTS");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to clear DISALLOW_MODIFY_ACCOUNTS restriction. Error: " + e.getMessage());
        }
    }


    private void PrevGAccount() {
        if (!dpm.isAdminActive(adminComponent)) {
            Log.e(TAG, "Device Admin not active - cannot lock settings");
            return;
        }


        try {
            // 3. Disable Modification of accounts (to prevent removal of critical accounts)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS);
            Log.d(TAG, "Restriction applied: DISALLOW_MODIFY_ACCOUNTS");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to apply DISALLOW_MODIFY_ACCOUNTS restriction. Error: " + e.getMessage());
        }

        // Note: Individual try-catch blocks are used to ensure that a failure in one
        // restriction doesn't prevent the others from being attempted.
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