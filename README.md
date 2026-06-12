# Phos — local voice companion for the Nothing Phone 2a

A fully on-device, offline voice back-and-forth assistant that runs in **Termux**:

```
🎤 termux-microphone-record → ffmpeg → whisper-cli (STT)
   → ollama qwen3:1.7b (brain) → termux-tts-speak (Android TTS) 🔊
```

No internet at runtime. The only network use is the one-time setup download (whisper + LLM model).

## Install (on the phone, in Termux)

First make sure the **Termux:API app** is installed (F-Droid) — it's what lets Termux reach the mic and TTS.

Then paste this one line:

```sh
pkg install -y git && git clone https://github.com/Peterc3-dev/phos-voice && bash phos-voice/setup.sh
```

That installs ffmpeg/termux-api/python, downloads the whisper model (~57 MB) and the LLM (`qwen3:1.7b`, ~1.1 GB), and stages everything in `~/phos`.

## Talk to it

```sh
python ~/phos/phos_voice.py
```

Speak when it says "speak now"; it transcribes, thinks, and talks back. Say **"goodbye"** or hit Ctrl-C to quit.

Tunables: `VC_SECS` (record window, default 6), `VC_MODEL` (default `qwen3:1.7b`).

## Hardware note
Built for the Phone 2a (Dimensity 7200 Pro, 8 GB). `qwen3:1.7b` is the sweet spot (~8 t/s, stable); the whisper binary is a prebuilt aarch64 build of whisper.cpp (`ggml-base.en-q5_1`).
