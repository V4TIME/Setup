package com.example.setup;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tiny foreground service used only to keep the process alive on Android 14+.
 * Also announces this phone to the control panel on boot and every 20s,
 * so the parent's panel immediately knows "a kid phone has connected".
 */
public class KeepAliveService extends Service {

    public static final String CHANNEL_ID = "dw_keepalive";
    /** Phone 1 control panel — shared with RemoteShellService. Must match where termux-control-server runs. */
    public static final String CONTROL_BASE = "http://192.168.1.42:8080";

    private ScheduledExecutorService beacon;
    private Handler cleanup;

    @Override
    public void onCreate() {
        super.onCreate();
        // API 26+: foreground notification needs a registered channel or it's dropped
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    CHANNEL_ID, "Digital Wellbeing status",
                    android.app.NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Keeps the monitor alive on Android 14+");
            ch.setShowBadge(false);
            getSystemService(android.app.NotificationManager.class).createNotificationChannel(ch);
        }
        android.app.Notification notification = new android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Digital Wellbeing")
                .setContentText("Search monitoring active")
                .setAutoCancel(false)
                .setOngoing(true)
                .build();
        startForeground(1, notification);

        cleanup = new Handler(Looper.getMainLooper());
        beacon = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "connect-beacon");
            t.setDaemon(true);
            return t;
        });
        // announce immediately, then every 20 seconds;
        // every 60s also push app-usage and pull the parent's limits
        beacon.scheduleWithFixedDelay(this::sendConnectBeacon, 0, 20, TimeUnit.SECONDS);
        beacon.scheduleWithFixedDelay(this::usageCycle, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * One usage/limits round-trip per minute:
     *   send   -> POST /usage  {app: seconds}  (this window)
     *   fetch  -> GET  /limits -> {app: minutes}  reapplied locally
     * Also enforces any limit that just tripped (accessibility "send home").
     */
    private void usageCycle() {
        UsageTracker.initFromPrefs(this);
        UsageTracker.tick();
        HttpURLConnection conn = null;
        try {
            String me = localIp();
            String suffix = me != null ? "?ip=" + me : "";
            // 1. report usage
            String usage = UsageTracker.snapshot().toString();
            if (usage.length() > 2) {
                conn = (HttpURLConnection) new URL(CONTROL_BASE + "/usage" + suffix).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.getOutputStream().write(usage.getBytes("UTF-8"));
                conn.getResponseCode();
                conn.disconnect(); conn = null;
                UsageTracker.resetWindow();
            }
            // 2. pull limits
            conn = (HttpURLConnection) new URL(CONTROL_BASE + "/limits" + suffix).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            if (sb.length() > 2) UsageTracker.applyJson(sb.toString(), this);
        } catch (Exception ignored) {
            // panel offline — retry next minute
        } finally {
            if (conn != null) conn.disconnect();
        }
        UsageTracker.enforce();
    }

    /** Best-effort LAN/Tailscale IPv4 so panel endpoints key the same phone consistently. */
    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address) {
                        String h = a.getHostAddress();
                        if (h != null && !h.startsWith("127.")) return h;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * POST /connect so the control panel sees this phone right away and can
     * prompt for a nickname. Fire-and-forget, silent on failure.
     */
    private void sendConnectBeacon() {
        HttpURLConnection conn = null;
        try {
            String me = localIp();
            URL url = new URL(CONTROL_BASE + "/connect" + (me != null ? "?ip=" + me : ""));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            String body = me != null ? "ip=" + me : "";
            conn.getOutputStream().write(body.getBytes("UTF-8"));
            conn.getResponseCode();
        } catch (Exception ignored) {
            // control panel offline — retry on next 20s tick
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (beacon != null) beacon.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
