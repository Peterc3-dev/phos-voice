package io.cin.phosrec;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.Manifest;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;

/**
 * PhosRec — records from the Bluetooth headset mic (HFP/SCO) to a 16 kHz mono WAV that
 * the Termux Phos voice loop reads. Triggered headlessly:
 *   am start -n io.cin.phosrec/.RecActivity -a io.cin.phosrec.RECORD \
 *     --ei secs 6 --es out /sdcard/phos/in.wav
 * Writes <out> then <out>.done as a completion marker. Falls back to the default mic if
 * no Bluetooth SCO device is available. One-time grants: mic + nearby-devices + all-files.
 */
public class RecActivity extends Activity {
    static final int SR = 16000;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // All-files access (to write /sdcard/phos so Termux can read it) — one-time Settings grant.
        if (!Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            finish();
            return;
        }

        // Runtime permissions (mic + nearby Bluetooth). MODIFY_AUDIO_SETTINGS is install-time.
        String[] perms = {Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT};
        boolean ok = true;
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ok = false;
        if (!ok) {
            requestPermissions(perms, 1);
            return;   // user grants once; next trigger proceeds
        }

        final int secs = getIntent().getIntExtra("secs", 6);
        final String out = getIntent().getStringExtra("out");
        final String path = (out != null) ? out : "/sdcard/phos/in.wav";
        new Thread(() -> { record(secs, path); finish(); }).start();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        finish();   // after the grant; user re-triggers
    }

    private void record(int secs, String outPath) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean sco = false;
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            AudioDeviceInfo dev = null;
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { dev = d; break; }
            }
            if (dev != null) {
                sco = am.setCommunicationDevice(dev);
                sleep(1500);   // let the SCO link establish before reading
            }
        } catch (Exception ignored) {}

        int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min, SR);   // ~0.5s of int16 samples
        AudioRecord rec = null;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord init failed");
            byte[] chunk = new byte[buf];
            rec.startRecording();
            long end = System.currentTimeMillis() + secs * 1000L;
            while (System.currentTimeMillis() < end) {
                int n = rec.read(chunk, 0, chunk.length);
                if (n > 0) pcm.write(chunk, 0, n);
            }
        } catch (Exception ignored) {
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception e) {} rec.release(); }
            try { am.clearCommunicationDevice(); } catch (Exception e) {}
            try { am.setMode(AudioManager.MODE_NORMAL); } catch (Exception e) {}
        }

        try {
            if (pcm.size() > 0) {                            // only signal done on a real capture
                writeWav(outPath, pcm.toByteArray());
                new File(outPath + ".done").createNewFile(); // completion marker for the shell
            }
        } catch (IOException ignored) {}
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }

    private static void writeWav(String path, byte[] data) throws IOException {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        File done = new File(path + ".done");
        if (done.exists()) done.delete();
        int dataLen = data.length, byteRate = SR * 2;
        DataOutputStream o = new DataOutputStream(new FileOutputStream(f));
        o.writeBytes("RIFF"); intLE(o, 36 + dataLen); o.writeBytes("WAVE");
        o.writeBytes("fmt "); intLE(o, 16); shortLE(o, (short) 1); shortLE(o, (short) 1);
        intLE(o, SR); intLE(o, byteRate); shortLE(o, (short) 2); shortLE(o, (short) 16);
        o.writeBytes("data"); intLE(o, dataLen); o.write(data);
        o.close();
    }

    private static void intLE(DataOutputStream o, int v) throws IOException {
        o.write(v & 0xff); o.write((v >> 8) & 0xff); o.write((v >> 16) & 0xff); o.write((v >> 24) & 0xff);
    }

    private static void shortLE(DataOutputStream o, short v) throws IOException {
        o.write(v & 0xff); o.write((v >> 8) & 0xff);
    }
}
