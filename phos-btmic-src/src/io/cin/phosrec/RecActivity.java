package io.cin.phosrec;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.Manifest;

/**
 * PhosRec foreground recorder + permission-granting entry point. Triggered headlessly:
 *   am start -n io.cin.phosrec/.RecActivity -a io.cin.phosrec.RECORD --ei secs 12 --es out /sdcard/phos/in.wav
 * Records via Capture (VAD). For BACKGROUND (screen-off) capture use RecService instead
 * (am start-foreground-service). On first run it requests all-files + mic + nearby-devices.
 */
public class RecActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (!Environment.isExternalStorageManager()) {                 // all-files access (one-time)
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            finish();
            return;
        }

        String[] perms = {Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT};
        boolean ok = true;
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ok = false;
        if (!ok) {
            requestPermissions(perms, 1);
            return;
        }

        final int secs = getIntent().getIntExtra("secs", 12);
        final String out = getIntent().getStringExtra("out");
        final String path = (out != null) ? out : "/sdcard/phos/in.wav";
        new Thread(() -> { Capture.record(this, secs, path); finish(); }).start();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        finish();
    }
}
