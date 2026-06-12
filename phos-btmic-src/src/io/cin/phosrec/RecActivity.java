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
        boolean useSco = false;
        try {
            AudioDeviceInfo dev = null;
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { dev = d; break; }
            }
            if (dev != null) {                                   // only enter comm-mode for an actual BT headset
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                useSco = am.setCommunicationDevice(dev);
                if (useSco) sleep(1500);                         // let the SCO link establish
            }
        } catch (Exception ignored) {}
        // Phone mic: plain MIC source, NO comm-mode (comm-mode + VOICE_COMMUNICATION on the
        // built-in mic applies far-field/echo processing that kills the level). BT: VOICE_COMMUNICATION.
        int source = useSco ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                            : MediaRecorder.AudioSource.MIC;

        // VAD capture: calibrate an ambient floor, record while you speak, stop after a short
        // trailing silence — capped at `secs`. No fixed wait; gives up if you never speak.
        final int CHUNK_MS = 50;
        final int chunkBytes = (SR / 1000 * CHUNK_MS) * 2;        // 50 ms of int16 mono = 1600 B
        final int MAX_MS = Math.max(secs, 2) * 1000;
        final int AMBIENT_MS = 400, TRAIL_SILENCE_MS = 700, NOSPEECH_GIVEUP_MS = 3500;
        int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        boolean speaking = false;
        double peakRms = 0, dbgFloor = 0, dbgThresh = 0;
        int dbgMs = 0;
        try {
            rec = new AudioRecord(source, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, chunkBytes * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord init failed");
            byte[] chunk = new byte[chunkBytes];
            rec.startRecording();
            double noiseSum = 0; int noiseN = 0;
            int totalMs = 0, silentMs = 0;
            long startNs = System.nanoTime();
            while (totalMs < MAX_MS) {
                int n = rec.read(chunk, 0, chunk.length);
                if (n < 0) break;                                                    // hard read error
                if (n == 0) {                                                        // no frame yet (e.g. SCO drop)
                    if ((System.nanoTime() - startNs) / 1_000_000L > MAX_MS + 1500) break;
                    continue;
                }
                pcm.write(chunk, 0, n);
                totalMs += CHUNK_MS;
                dbgMs = totalMs;
                double rms = rms16(chunk, n);
                if (rms > peakRms) peakRms = rms;
                if (totalMs <= AMBIENT_MS) { noiseSum += rms; noiseN++; continue; }   // ambient floor
                dbgFloor = Math.min(noiseN > 0 ? noiseSum / noiseN : 200.0, 400.0);   // cap so early speech can't blow up the gate
                dbgThresh = Math.max(dbgFloor * 3.0, 500.0);
                if (rms > dbgThresh) { speaking = true; silentMs = 0; }
                else if (speaking) { silentMs += CHUNK_MS; if (silentMs >= TRAIL_SILENCE_MS) break; }
                if (!speaking && totalMs >= NOSPEECH_GIVEUP_MS) break;                 // never spoke
            }
        } catch (Exception ignored) {
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception e) {} rec.release(); }
            if (useSco) {                                        // only undo comm-mode if we set it
                try { am.clearCommunicationDevice(); } catch (Exception e) {}
                try { am.setMode(AudioManager.MODE_NORMAL); } catch (Exception e) {}
            }
        }

        // debug stats (read over ssh to tune VAD): mic source, levels, whether speech fired
        try {
            java.io.FileWriter fw = new java.io.FileWriter(outPath + ".stats");
            fw.write("src=" + (useSco ? "SCO" : "MIC") + " peak_rms=" + (long) peakRms
                    + " floor=" + (long) dbgFloor + " thresh=" + (long) dbgThresh
                    + " speaking=" + speaking + " ms=" + dbgMs + " bytes=" + pcm.size() + "\n");
            fw.close();
        } catch (Exception ignored) {}

        try {
            if (speaking && pcm.size() > 4000) {             // only signal done on real captured speech
                writeWav(outPath, pcm.toByteArray());
                new File(outPath + ".done").createNewFile(); // completion marker for the shell
            }
        } catch (IOException ignored) {}
    }

    private static double rms16(byte[] b, int n) {
        long sum = 0; int cnt = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            short s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += (long) s * s; cnt++;
        }
        return cnt == 0 ? 0.0 : Math.sqrt((double) sum / cnt);
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
