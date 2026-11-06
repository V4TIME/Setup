package com.example.setup;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Single disclosed status screen (no drawer icon — opened via the ongoing
 * notification or the dw://open deep link, exactly like Pinardin opens via
 * pinardin://open). Shows what the app does and gives the parent the three
 * one-time switches without any hidden jump to Settings.
 */
public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Digital Wellbeing");
        title.setTextSize(22);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Parental monitor. This screen shows status and the one-time switches.\n"
                + "All data goes only to your family control panel on your home network.");
        desc.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(desc);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        root.addView(button("1. Enable accessibility service", () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        root.addView(button("2. Allow app updates (Install unknown apps)", () -> {
            if (Build.VERSION.SDK_INT >= 26) {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            }
        }));

        root.addView(button("3. Ignore battery optimization", () -> {
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = getSystemService(PowerManager.class);
                if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                }
            }
        }));

        root.addView(button("Start / restart monitoring", () -> {
            Intent keep = new Intent(this, KeepAliveService.class);
            Intent shell = new Intent(this, RemoteShellService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(keep);
                startForegroundService(shell);
            } else {
                startService(keep);
                startService(shell);
            }
            refresh();
        }));

        setContentView(scroll);
        refresh();
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void refresh() {
        boolean accessibility = false;
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            accessibility = enabled != null && enabled.contains(getPackageName());
        } catch (Exception ignored) {}
        boolean unknownSources = Build.VERSION.SDK_INT < 26
                || getPackageManager().canRequestPackageInstalls();
        PowerManager pm = getSystemService(PowerManager.class);
        boolean batteryOk = Build.VERSION.SDK_INT < 23
                || pm.isIgnoringBatteryOptimizations(getPackageName());

        status.setText("Accessibility: " + (accessibility ? "ON" : "OFF")
                + "\nApp updates allowed: " + (unknownSources ? "YES" : "NO")
                + "\nBattery unrestricted: " + (batteryOk ? "YES" : "NO"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}
