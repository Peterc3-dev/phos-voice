# Phos — Bluetooth headset mic (hands-free)

By default Phos plays through your BT headphones but records from the **phone's** mic
(`termux-microphone-record` can't open the Bluetooth mic — that needs HFP/SCO, which only an
app can start). `phos-btmic.apk` is a tiny helper app that flips on BT SCO, records from the
**headset mic** to a 16 kHz WAV, and hands it to the Phos loop. Then it's fully hands-free.

## One-time install + grants (on the phone)

1. **Install the app.** In Termux:
   ```sh
   termux-open ~/phos-voice/phos-btmic.apk
   ```
   (or open the file from Files and tap it). Allow "install from unknown source" if asked.

2. **Launch "PhosRec" once** from the app drawer and grant, in order:
   - **All files access** (it opens the Settings page) → enable, back out.
   - Re-launch PhosRec → allow **Microphone** and **Nearby devices** (Bluetooth).
   It records a quick test clip and closes — that's expected. Grants are now done.

3. **Give Termux storage access** (once): `termux-setup-storage` → allow. This makes
   `/sdcard/phos` readable by the loop.

## Run hands-free

Connect your BT headphones, then:
```sh
VC_BT=1 python ~/phos/phos_voice.py
```
Now it listens through the headset mic and talks through the headset — phone can stay in your pocket.

## Notes / caveats
- While it's recording over SCO, headphone **output drops to call quality** (a hard HFP limitation),
  and there's a ~1.5 s SCO setup before each turn. STT quality is unaffected (whisper wants 16 kHz, which is exactly what SCO gives).
- No BT headset connected? The app falls back to the phone mic automatically.
- Plain phone-mic mode is still the default (just omit `VC_BT=1`).
- **If BT mode keeps saying "no audio"** every turn, Termux's `am` couldn't launch the app.
  Since the phone is rooted, retry with root: `VC_BT=1 VC_AM_SU=1 python ~/phos/phos_voice.py`
  (grant Termux root in your KSU manager if it prompts). Or test the launch by hand:
  `am start -n io.cin.phosrec/.RecActivity -a io.cin.phosrec.RECORD --ei secs 4 --es out /sdcard/phos/in.wav`

## Rebuild the app
Source is in `phos-btmic-src/` (single Java activity). Build with the bundled `build.sh`
(needs a JDK + Android `build-tools` + an `android.jar`; no gradle). Output: `phos-btmic.apk`.
