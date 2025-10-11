package com.example.customer_app;

import android.app.ActivityManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class AppBlockService extends Service {

    private static final String TAG = "AppBlockService";
    private String blockedPackage = null;
    private Handler handler;
    private Runnable blockRunnable;
    private static final int CHECK_INTERVAL = 200; // Check every 200ms (faster)

    private boolean isOverlayShown = false;

    private final String[] SETTINGS_PACKAGES = {
            "com.android.settings",
            "com.miui.settings",
            "com.samsung.android.settings",
            "com.huawei.settings",
            "com.oppo.settings",
            "com.oneplus.settings"
    };

    private boolean isSettingsPackage(String packageName) {
        if (packageName == null) return false;
        packageName = packageName.toLowerCase();
        return packageName.contains("settings") ||
                packageName.contains("securitycenter") ||
                packageName.contains("systemui") ||
                (packageName.contains("com.miui") && packageName.contains("settings"));
    }


    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler();
        Log.d(TAG, "AppBlockService Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("blocked_package")) {
            blockedPackage = intent.getStringExtra("blocked_package");
            Log.d(TAG, "Blocking package: " + blockedPackage);
            startBlocking();
        }
        return START_STICKY;
    }

    private void startBlocking() {
        if (blockRunnable != null) { handler.removeCallbacks(blockRunnable); }
        blockRunnable = new Runnable() {
            @Override
            public void run() {
                if (blockedPackage != null) {
                    String currentApp = getCurrentForegroundApp();
                    // Log for debugging
//                    if (currentApp != null) {
//                        Log.d(TAG, "Current app: " + currentApp);
//                    }
//
//                    // Check if blocked app is running
//                    if (currentApp != null && currentApp.equals(blockedPackage)) {
//                        Log.d(TAG, "Blocked app detected: " + currentApp + " - BLOCKING NOW");
//                        blockApp();
//                    } else if (currentApp != null && blockedPackage.equals("com.android.settings")) {
//                        // Special check for Settings (has multiple package variants)
//                        if (currentApp.contains("settings") || currentApp.contains("Settings")) {
//                            Log.d(TAG, "Settings variant detected: " + currentApp + " - BLOCKING NOW");
//                            blockApp();
//                        }
//                    }
                    if (currentApp != null) {
                        if ("settings".equalsIgnoreCase(blockedPackage)) {
                                if (isSettingsPackage(currentApp)) {
                                    Log.d(TAG, "Blocked Settings app detected: " + currentApp);
                                    showOverlay();
                                }
                        } else if (currentApp.equals(blockedPackage)) {
                            Log.d(TAG, "Blocked app detected: " + currentApp + " - BLOCKING NOW");
                            showOverlay();
                        }
                    }
                }
                handler.postDelayed(this, CHECK_INTERVAL);
            }
        };
        handler.post(blockRunnable);
    }
    private void showOverlay() {
        if (!isOverlayShown) {
            Intent overlayIntent = new Intent(this, BlockedAppOverlay.class);
            overlayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            overlayIntent.putExtra("blocked_app", blockedPackage);
            startActivity(overlayIntent);
            isOverlayShown = true;
            Log.d(TAG, "Overlay shown for: " + blockedPackage);
        }
    }
    private void hideOverlay() {
        isOverlayShown = false; // Overlay activity will check this and finish itself
    }
    private String getCurrentForegroundApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long currentTime = System.currentTimeMillis();

            // Query last 3 seconds for more accuracy
            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    currentTime - 1000 * 3, // Last 3 seconds
                    currentTime
            );

            if (stats != null && !stats.isEmpty()) {
                SortedMap<Long, UsageStats> sortedStats = new TreeMap<>();
                for (UsageStats usageStats : stats) {
                    sortedStats.put(usageStats.getLastTimeUsed(), usageStats);
                }
                if (!sortedStats.isEmpty()) {
                    String packageName = sortedStats.get(sortedStats.lastKey()).getPackageName();
                    return packageName;
                }
            }
        } else {
            // Fallback for older Android versions
            ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
                if (tasks != null && !tasks.isEmpty()) {
                    return tasks.get(0).topActivity.getPackageName();
                }
            }
        }
        return null;
    }

    private void blockApp() {
        // Go to home screen immediately
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(homeIntent);

        // Show overlay immediately
        Intent overlayIntent = new Intent(this, BlockedAppOverlay.class);
        overlayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);
        overlayIntent.putExtra("blocked_app", blockedPackage);
        startActivity(overlayIntent);

        Log.d(TAG, "App blocked - sent to home + overlay shown");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && blockRunnable != null) {
            handler.removeCallbacks(blockRunnable);
        }
        blockedPackage = null;
        Log.d(TAG, "AppBlockService Destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}


