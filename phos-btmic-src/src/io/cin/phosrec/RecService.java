package io.cin.phosrec;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Background recorder: a microphone-type foreground service so Android permits mic capture while
 * Termux/the screen is in the background. Started headlessly (root, to bypass background-start limits):
 *   am start-foreground-service -n io.cin.phosrec/.RecService -a io.cin.phosrec.RECORD \
 *     --ei secs 12 --es out /sdcard/phos/in.wav
 * Does one VAD capture via Capture, writes <out> + <out>.done, then stops itself.
 */
public class RecService extends Service {
    private static final String CH = "phosrec";

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int secs = (intent != null) ? intent.getIntExtra("secs", 12) : 12;
        final String out = (intent != null) ? intent.getStringExtra("out") : null;
        final String path = (out != null) ? out : "/sdcard/phos/in.wav";
        try {
            startFg();                                          // mic FGS needs RECORD_AUDIO=allow op (see setup)
        } catch (Exception e) {
            try {
                java.io.FileWriter fw = new java.io.FileWriter(path + ".stats");
                fw.write("FGS_START_FAILED " + e + "\n");       // visible failure instead of an opaque crash
                fw.close();
            } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        new Thread(() -> {
            try { Capture.record(this, secs, path); }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        }).start();
        return START_NOT_STICKY;
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(
                new NotificationChannel(CH, "Phos mic", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("Phos — listening")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    }
}
