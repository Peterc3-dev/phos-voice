# Phos — local voice companion for the Nothing Phone 2a

A fully on-device, offline voice back-and-forth assistant that runs in **Termux**:

```
🎤 termux-microphone-record → ffmpeg → whisper-cli (STT)
   → llama-server / Qwen3-1.7B (brain) → termux-tts-speak (Android TTS) 🔊
```

The LLM is run by a **llama.cpp build tuned for this exact chip** (aarch64 + `i8mm`/`dotprod`),
not a generic runtime — faster and lighter than ollama on the MT6886. No internet at runtime;
the only network use is the one-time setup download (STT + LLM models).

## Install (on the phone, in Termux)

First make sure the **Termux:API app** is installed (F-Droid) — it's what lets Termux reach the mic and TTS.

Then paste this one line:

```sh
pkg install -y git && git clone https://github.com/Peterc3-dev/phos-voice && bash phos-voice/setup.sh
```

That installs ffmpeg/termux-api/python, and downloads the whisper model (~57 MB) and the LLM
(`Qwen3-1.7B-Q4_K_M`, ~1.1 GB), staging everything in `~/phos`.

## Talk to it

```sh
python ~/phos/phos_voice.py
```

Speak when it says "speak now"; it transcribes, replies, and talks back. Say **"goodbye"** or Ctrl-C to quit.
(First launch loads the model into RAM, ~30–60 s; replies are quick after that.)

Tunables: `VC_SECS` (record window, default 6), `VC_THREADS` (llama threads, default 4).

## Hardware note
Built for the Phone 2a (Dimensity 7200 Pro, 8 GB), CPU-only (the MediaTek APU/NPU isn't usable by
llama.cpp). Qwen3-1.7B is the sweet spot (~8 tok/s, stable on 8 GB); the STT binary is whisper.cpp
(`ggml-base.en-q5_1`), and `llama-server` is the i8mm-tuned llama.cpp build. Threads default to 4
(measured best for generation on this chip).
