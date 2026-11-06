package com.example.setup;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Sends one-way "kid alert" events to the parent control panel:
 * app install / uninstall / update, battery low, charger plugged, screen-time
 * limit reached, and kid-triggered SOS.
 *
 * Deliberately permission-free: PACKAGE_ADDED / PACKAGE_FULLY_REMOVED /
 * BATTERY_LOW / POWER_CONNECTED are exempt broadcasts, and the app label is
 * resolved through the launcher <queries> declaration (no QUERY_ALL_PACKAGES).
 */
public final class AlertCenter {

    private AlertCenter() {}

    /** Fire-and-forget alert. Never throws, never blocks the caller. */
    public static void post(Context ctx, String type, String pkg, String msg) {
        if (ctx == null || type == null) return;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> send(app, type, pkg, msg), "kid-alert");
        t.setDaemon(true);
        t.start();
    }

    private static void send(Context ctx, String type, String pkg, String msg) {
        HttpURLConnection conn = null;
        try {
            String ip = KeepAliveService.localIp();
            String q = ip != null ? "?ip=" + ip : "";
            JSONObject o = new JSONObject();
            o.put("type", type);
            if (pkg != null) {
                o.put("pkg", pkg);
                o.put("app", label(ctx, pkg));
            }
            if (msg != null) o.put("msg", msg);
            o.put("at", System.currentTimeMillis());

            byte[] body = o.toString().getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(KeepAliveService.CONTROL_BASE + "/alert" + q)
                    .openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.getOutputStream().write(body);
            conn.getResponseCode();
        } catch (Exception ignored) {
            // panel unreachable — the next periodic beacon re-establishes contact
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Human-readable app name, or the package id if it cannot be resolved. */
    public static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
