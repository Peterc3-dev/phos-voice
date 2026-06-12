package io.cin.phosrec;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Shared VAD recorder used by both RecActivity (foreground) and RecService (background, mic-type
 * foreground service). Captures from the BT headset mic (HFP/SCO) if present, else the phone mic;
 * stops ~0.7s after speech ends; writes a 16 kHz mono WAV + a `.done` marker (+ a `.stats` line).
 */
public class Capture {
    static final int SR = 16000;

    public static void record(Context ctx, int secs, String outPath) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
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
        // Phone mic: plain MIC source, NO comm-mode (comm-mode + VOICE_COMMUNICATION on the built-in
        // mic applies far-field/echo processing that kills the level). BT headset: VOICE_COMMUNICATION.
        int source = useSco ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                            : MediaRecorder.AudioSource.MIC;

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
                if (n < 0) break;
                if (n == 0) {
                    if ((System.nanoTime() - startNs) / 1_000_000L > MAX_MS + 1500) break;
                    continue;
                }
                pcm.write(chunk, 0, n);
                totalMs += CHUNK_MS;
                dbgMs = totalMs;
                double rms = rms16(chunk, n);
                if (rms > peakRms) peakRms = rms;
                if (totalMs <= AMBIENT_MS) { noiseSum += rms; noiseN++; continue; }
                dbgFloor = Math.min(noiseN > 0 ? noiseSum / noiseN : 200.0, 400.0);
                dbgThresh = Math.max(dbgFloor * 3.0, 500.0);
                if (rms > dbgThresh) { speaking = true; silentMs = 0; }
                else if (speaking) { silentMs += CHUNK_MS; if (silentMs >= TRAIL_SILENCE_MS) break; }
                if (!speaking && totalMs >= NOSPEECH_GIVEUP_MS) break;
            }
        } catch (Exception ignored) {
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception e) {} rec.release(); }
            if (useSco) {
                try { am.clearCommunicationDevice(); } catch (Exception e) {}
                try { am.setMode(AudioManager.MODE_NORMAL); } catch (Exception e) {}
            }
        }

        try {
            java.io.FileWriter fw = new java.io.FileWriter(outPath + ".stats");
            fw.write("src=" + (useSco ? "SCO" : "MIC") + " peak_rms=" + (long) peakRms
                    + " floor=" + (long) dbgFloor + " thresh=" + (long) dbgThresh
                    + " speaking=" + speaking + " ms=" + dbgMs + " bytes=" + pcm.size() + "\n");
            fw.close();
        } catch (Exception ignored) {}

        try {
            if (speaking && pcm.size() > 4000) {
                writeWav(outPath, pcm.toByteArray());
                new File(outPath + ".done").createNewFile();
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
