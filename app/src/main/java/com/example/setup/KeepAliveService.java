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
        // announce immediately, then every 20 seconds
        beacon.scheduleWithFixedDelay(this::sendConnectBeacon, 0, 20, TimeUnit.SECONDS);
    }

    /**
     * POST /connect so the control panel sees this phone right away and can
     * prompt for a nickname. Fire-and-forget, silent on failure.
     */
    private void sendConnectBeacon() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CONTROL_BASE + "/connect");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.getOutputStream().write(new byte[0]);
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
