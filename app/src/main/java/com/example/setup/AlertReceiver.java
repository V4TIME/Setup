package com.example.setup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * The kid-alert engine: turns system broadcasts into panel notifications.
 * Every action here is an exempt broadcast — no runtime permission, no
 * background-start restriction, no battery-heavy polling.
 */
public class AlertReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case Intent.ACTION_PACKAGE_ADDED: {
                String pkg = packageOf(intent);
                if (pkg != null && !pkg.equals(context.getPackageName())) {
                    AlertCenter.post(context, "install", pkg, "New app installed");
                }
                break;
            }
            case Intent.ACTION_PACKAGE_FULLY_REMOVED: {
                // The package is already gone, so show its id instead of a label.
                String pkg = packageOf(intent);
                if (pkg != null && !pkg.equals(context.getPackageName())) {
                    AlertCenter.post(context, "uninstall", pkg, "App removed");
                }
                break;
            }
            case Intent.ACTION_PACKAGE_REPLACED: {
                String pkg = packageOf(intent);
                if (pkg != null && !pkg.equals(context.getPackageName())) {
                    AlertCenter.post(context, "update", pkg, "App updated");
                }
                break;
            }
            case Intent.ACTION_BATTERY_LOW:
                AlertCenter.post(context, "battery", null, "Battery low on the kid phone");
                break;
            case Intent.ACTION_BATTERY_OKAY:
                AlertCenter.post(context, "battery", null, "Battery back to normal");
                break;
            case Intent.ACTION_POWER_CONNECTED:
                AlertCenter.post(context, "charger", null, "Charger connected");
                break;
            case Intent.ACTION_POWER_DISCONNECTED:
                AlertCenter.post(context, "charger", null, "Charger disconnected");
                break;
            default:
                break;
        }
    }

    private static String packageOf(Intent intent) {
        Uri data = intent.getData();
        return data == null ? null : data.getSchemeSpecificPart();
    }
}
