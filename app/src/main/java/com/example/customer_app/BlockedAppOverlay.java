package com.example.customer_app;

import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.os.Handler;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class BlockedAppOverlay extends AppCompatActivity {
    private boolean shouldClose = false;
    private DevicePolicyManager dpm;
    private TextView txtSubadminName, txtSubadminNumber;
    private String TAG = "BlockedAppOverlay";
    private DatabaseReference dbRef;


    private ComponentName adminComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_blocked_app_overlay);

        adminComponent = new ComponentName(this, LoanDeviceAdminReceiver.class);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        dbRef = FirebaseDatabase.getInstance().getReference();


        if (getIntent().getBooleanExtra("showornot",false)){
            if (dpm.isAdminActive(adminComponent)) {
                dpm.setLockTaskPackages(adminComponent,new String[] {getPackageName()});
                startLockTask();
                Log.d(TAG, "Kiosis Mode start - Single App");
            } else {
                Log.e(TAG, "Device Admin not active - Kiosis Mode start - Single App");
            }
        }else {
            Log.d(TAG , "Stopping Kiosis Mode - Single App");
            stopLockTask();
//            finish();

        }

        txtSubadminName = findViewById(R.id.blockedmessage_Subadmin_name);
        txtSubadminNumber = findViewById(R.id.blockedmessage_subadmin_number);
//        String blockedApp = getIntent().getStringExtra("blocked_app");
//        String appName = getAppName(blockedApp);


        // Todo : Get Name of subAdmin from firebase and set text - Phone Locked by SubAdmin
//        txtSubadminName.setText();

        // Todo: Get Number of subAdmin from firebase and set text - phone no
//        txtSubadminNumber.setText();


        // Get SharedPreferences (same name you used while saving)
        SharedPreferences sp = getSharedPreferences("CustomerAppPrefs", MODE_PRIVATE);

// Retrieve saved SubAdmin ID
        String subAdminId = sp.getString("subAdminId", null);

        if (subAdminId != null) {
            // Reference to SubAdmin -> buyers node
            DatabaseReference subAdminRef = dbRef.child("SubAdmins").child(subAdminId);

            subAdminRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        String name = snapshot.child("name").getValue(String.class);
                        String phone = snapshot.child("phone").getValue(String.class);

                        if (name != null) {
                            txtSubadminName.setText("Phone Locked by " + name);
                        } else {
                            txtSubadminName.setText("Phone Locked by SubAdmin");
                        }

                        if (phone != null) {
                            txtSubadminNumber.setText("Contact: " + phone);
                        } else {
                            txtSubadminNumber.setText("Contact: Store");
                        }
                    } else {
                        Log.e(TAG, "SubAdmin not found for ID: " + subAdminId);
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "Firebase error: " + error.getMessage());
                }
            });

        } else {
            Log.e(TAG, "No SubAdmin ID found in SharedPreferences");
        }



//        // Auto close after 2 seconds
//        tvMessage.postDelayed(this::finish, 2000);

//        Handler handler = new Handler();
//        handler.postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                SharedPreferences sp = getSharedPreferences("CustomerAppPrefs", MODE_PRIVATE);
//                boolean lockSettings = sp.getBoolean("shouldLockSettings", false);
//                if (!lockSettings) {
//                    shouldClose = true;
////                    finish();
//                } else {
//                    handler.postDelayed(this, 500);
//                }
//            }
//        }, 500);
    }

//    private String getAppName(String packageName) {
//        if (packageName == null) return "this app";
//
//        switch (packageName) {
//            case "com.whatsapp":
//                return "WhatsApp";
//            case "com.facebook.katana":
//                return "Facebook";
//            case "com.instagram.android":
//                return "Instagram";
//            case "com.google.android.youtube":
//                return "YouTube";
//            case "settings":
//                return "Settings";
//            default:
//                return "this Phone";
//        }
//    }
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        // Prevent back button
        super.onBackPressed();
        moveTaskToBack(true);
    }

}