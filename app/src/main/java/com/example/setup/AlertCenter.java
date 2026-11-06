package com.example.setup;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Sends one-way "kid alert" events to the parent control panel:
 * app install / uninstall / update, battery low, charger plugged, screen-time
 * limit reached, and kid-triggered SOS.
 *
 * Deliberately permission-free: PACKAGE_ADDED / PACKAGE_FULLY_REMOVED /
 * BATTERY_LOW / POWER_CONNECTED are exempt broadcasts, and the app label is
 * resolved through the launcher <queries> declaration (no QUERY_ALL_PACKAGES).
 *
 * If the panel is unreachable the event is written to a bounded on-disk spool
 * (the useful idea borrowed from KotlinLogger's FileLogger) and replayed by the
 * next beacon, so an alert fired while Wi-Fi was off is not lost.
 */
public final class AlertCenter {

    private static final int SPOOL_ITEMS = 200;
    private static final long SPOOL_BYTES = 48 * 1024;
    private static final int FLUSH_PER_ROUND = 10;

    private static EventSpool spool;

    private AlertCenter() {}

    /** Fire-and-forget alert. Never throws, never blocks the caller. */
    public static void post(Context ctx, String type, String pkg, String msg) {
        if (ctx == null || type == null) return;
        final Context app = ctx.getApplicationContext();
        final String json = build(app, type, pkg, msg);
        if (json == null) return;
        Thread t = new Thread(() -> {
            if (!sendRaw(json)) spool(app).add(json);
        }, "kid-alert");
        t.setDaemon(true);
        t.start();
    }

    /** Replay alerts queued while the panel was offline. Safe to call often. */
    public static void flush(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            List<String> pending = spool(app).drain();
            int i = 0;
            for (; i < pending.size() && i < FLUSH_PER_ROUND; i++) {
                if (!sendRaw(pending.get(i))) break;
            }
            for (; i < pending.size(); i++) spool(app).add(pending.get(i));
        }, "kid-alert-flush");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized EventSpool spool(Context ctx) {
        if (spool == null) {
            spool = new EventSpool(new File(ctx.getFilesDir(), "alerts-spool.log"),
                    SPOOL_ITEMS, SPOOL_BYTES);
        }
        return spool;
    }

    private static String build(Context ctx, String type, String pkg, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            if (pkg != null) {
                o.put("pkg", pkg);
                o.put("app", label(ctx, pkg));
            }
            if (msg != null) o.put("msg", msg);
            o.put("at", System.currentTimeMillis());
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** One POST; true only on 2xx so the spool knows whether to keep the event. */
    private static boolean sendRaw(String json) {
        HttpURLConnection conn = null;
        try {
            String ip = KeepAliveService.localIp();
            String q = ip != null ? "?ip=" + URLEncoder.encode(ip, "UTF-8") : "";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(KeepAliveService.CONTROL_BASE + "/alert" + q)
                    .openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.getOutputStream().write(body);

            int code = conn.getResponseCode();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8));
            while (r.readLine() != null) { /* drain */ }
            r.close();
            return code >= 200 && code < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Human-readable app name, or the package id if it cannot be resolved. */
    public static String label(Context ctx, String pkg) {
        if (pkg == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
