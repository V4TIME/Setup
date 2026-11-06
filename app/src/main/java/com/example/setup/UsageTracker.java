package com.example.setup;

import android.content.Context;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tracks which app is in the foreground (fed by SafetyService accessibility
 * window events) and enforces per-app daily screen-time limits set from the
 * control panel. Enforcement = "send home" (accessibility GLOBAL_ACTION_HOME)
 * — the same gentle-block pattern Pinardin uses for app limits, with zero
 * extra permissions.
 */
public final class UsageTracker {

    private static final String PREFS = "usage_tracker";
    /** pkg -> accumulated seconds this reporting window */
    public static final Map<String, Long> secs = new HashMap<>();
    /** pkg -> allowed minutes per day (0/absent = unlimited) */
    public static final Map<String, Long> limits = new HashMap<>();

    /** currently foreground package + when it got focus */
    private static String currentPkg = null;
    private static long currentStart = 0;
    /** set by SafetyService: performs the "send home" global action */
    public static volatile Runnable sendHome = null;
    /** pkg -> minutes already used today, for limit math across restarts */
    private static final Map<String, Long> usedToday = new HashMap<>();

    private UsageTracker() {}

    /** SafetyService forwards every window change here. */
    public static synchronized void onWindow(String pkg) {
        if (pkg == null || pkg.length() == 0) return;
        long now = System.currentTimeMillis();
        settle(now);
        currentPkg = pkg;
        currentStart = now;
    }

    /** Call once per minute from the beacon thread: credits the foreground app. */
    public static synchronized void tick() {
        settle(System.currentTimeMillis());
    }

    private static void settle(long now) {
        if (currentPkg != null && currentStart > 0 && now > currentStart) {
            long sec = (now - currentStart) / 1000;
            if (sec > 0) {
                secs.put(currentPkg, secs.getOrDefault(currentPkg, 0L) + sec);
                usedToday.put(currentPkg, usedToday.getOrDefault(currentPkg, 0L) + sec);
            }
        }
        currentStart = now;
    }

    /** Enforce limits; returns list of packages that were just blocked. */
    public static synchronized String enforce() {
        StringBuilder blocked = new StringBuilder();
        for (Map.Entry<String, Long> e : limits.entrySet()) {
            Long used = usedToday.get(e.getKey());
            long minUsed = used == null ? 0 : used / 60;
            if (e.getValue() > 0 && minUsed >= e.getValue()) {
                usedToday.put(e.getKey(), 0L); // re-arm for next window
                secs.put(e.getKey(), secs.getOrDefault(e.getKey(), 0L));
                if (blocked.length() > 0) blocked.append(',');
                blocked.append(e.getKey());
                Runnable r = sendHome;
                if (r != null) r.run();
            }
        }
        return blocked.toString();
    }

    /** limit <pkg> <minutes> — from the panel terminal. pkg "all" resets. */
    public static synchronized String applyCommand(String args, Context ctx) {
        String[] p = args.trim().split("\\s+");
        if (p.length != 2) return "usage: limit <package> <minutes|off>";
        String pkg = p[0];
        String mins = p[1].equalsIgnoreCase("off") ? "0" : p[1].replaceAll("[^0-9]", "");
        if (mins.isEmpty()) return "bad minutes value";
        JSONObject lim = prefs(ctx);
        try {
            if (pkg.equalsIgnoreCase("all")) lim = new JSONObject();
            else {
                if (mins.equals("0")) lim.remove(pkg);
                else lim.put(pkg, Integer.parseInt(mins));
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .edit().putString("limits", lim.toString()).apply();
            loadPrefs(lim);
            return "limits now: " + lim;
        } catch (Exception ex) {
            return "error: " + ex.getMessage();
        }
    }

    /** Pull limits JSON from the panel /limits endpoint body. */
    public static synchronized void applyJson(String json, Context ctx) {
        try {
            JSONObject lim = new JSONObject(json);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .edit().putString("limits", json).apply();
            loadPrefs(lim);
        } catch (Exception ignored) {}
    }

    private static void loadPrefs(JSONObject lim) {
        limits.clear();
        usedToday.clear();
        Iterator<String> it = lim.keys();
        while (it.hasNext()) {
            String k = it.next();
            limits.put(k, lim.optLong(k, 0));
        }
    }

    public static void initFromPrefs(Context ctx) {
        loadPrefs(prefs(ctx));
    }

    private static JSONObject prefs(Context ctx) {
        try {
            return new JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                      .getString("limits", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Serialize current usage for the beacon: {pkg: seconds}. */
    public static synchronized JSONObject snapshot() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Long> e : secs.entrySet()) {
            try { o.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
        }
        return o;
    }

    /** Called after a successful report so numbers reflect "latest window". */
    public static synchronized void resetWindow() {
        secs.clear();
    }
}
