#!/data/data/com.termux/files/usr/bin/env python3
"""Phos — fully-local voice companion ON the Nothing Phone 2a (Termux). No internet at runtime.

Pipeline (all on-device): termux-microphone-record -> ffmpeg 16k -> whisper-cli (STT) ->
llama-server (LLM, i8mm-tuned llama.cpp build for this exact chip) -> termux-tts-speak.

Setup once: bash setup.sh. Run: python phos_voice.py   (Ctrl-C or say "goodbye" to quit)
Env: VC_SECS=record window (default 6), VC_THREADS=llama threads (default 4).
"""
import os
import re
import json
import time
import subprocess
import urllib.request
import urllib.error

HOME = os.path.expanduser("~")
DIR = os.path.join(HOME, "phos")
WHISPER = os.path.join(DIR, "whisper-cli")
WMODEL = os.path.join(DIR, "ggml-base.en-q5_1.bin")
LLAMA_SERVER = os.path.join(DIR, "llama-server")
GGUF = os.environ.get("VC_MODEL", os.path.join(DIR, "Qwen3-1.7B-Q4_K_M.gguf"))   # main; override to run 0.6B alone
DRAFT = os.path.join(DIR, "Qwen3-0.6B-Q4_K_M.gguf")   # speculative-decoding draft (same Qwen3 vocab)
PERSONA_FILE = os.path.join(DIR, "phos-system-prompt.txt")
M4A, WAV = os.path.join(DIR, "in.m4a"), os.path.join(DIR, "in.wav")
HEALTH = "http://127.0.0.1:8080/health"
CHAT = "http://127.0.0.1:8080/v1/chat/completions"
SECS = int(os.environ.get("VC_SECS", "4"))           # listen window; VC_SECS=3 snappier, 8 if it clips you
THREADS = os.environ.get("VC_THREADS", "4")          # 4 = best generation on the MT6886 (measured)
# VC_BT=1 -> capture from the Bluetooth headset mic via the PhosRec app (writes /sdcard/phos/in.wav,
# 16 kHz mono, already whisper-ready). Needs the app installed + termux-setup-storage run once.
USE_BT = os.environ.get("VC_BT", "0") == "1"
BT_WAV = os.path.join(HOME, "storage", "shared", "phos", "in.wav")   # /sdcard/phos/in.wav via Termux storage
BT_OUT = "/sdcard/phos/in.wav"                                       # path as the PhosRec app writes it
C = dict(g="\033[32m", c="\033[36m", y="\033[33m", d="\033[2m", r="\033[0m")


def sh(args, **kw):
    return subprocess.run(args, **kw)


def _server_responding():
    try:
        urllib.request.urlopen(HEALTH, timeout=2)
        return True
    except urllib.error.HTTPError:                    # 503 while loading == it IS running
        return True
    except Exception:
        return False


def ensure_server():
    """Start the i8mm llama-server on the GGUF if not already up; wait until it's healthy (200)."""
    if not _server_responding():
        cmd = [LLAMA_SERVER, "-m", GGUF, "-t", THREADS, "-c", "2048",
               "--host", "127.0.0.1", "--port", "8080"]
        if os.environ.get("VC_NOOPT") != "1":          # flash-attn + q8_0 KV cache: less RAM, a bit faster
            cmd += ["-fa", "on", "-ctk", "q8_0", "-ctv", "q8_0"]   # set VC_NOOPT=1 if the server won't start
        use_draft = os.path.exists(DRAFT) and os.path.realpath(DRAFT) != os.path.realpath(GGUF)
        if use_draft:                                  # speculative decoding: 0.6B drafts, 1.7B verifies
            cmd += ["-md", DRAFT, "--draft-max", "8", "--draft-min", "1", "-cd", "1024"]
            print(f"{C['d']}starting llama-server + speculative draft (first load ~30-60s)…{C['r']}")
        else:
            print(f"{C['d']}starting llama-server (first load ~30-60s)…{C['r']}")
        subprocess.Popen(cmd, stdout=open(os.path.join(DIR, "llama-server.log"), "w"),
                         stderr=subprocess.STDOUT)
    for _ in range(90):                               # model load can be slow on the phone
        try:
            urllib.request.urlopen(HEALTH, timeout=2)
            return True
        except urllib.error.HTTPError:               # still loading
            time.sleep(1)
        except Exception:
            time.sleep(1)
    return False


def tts(text):
    sh(["termux-tts-speak", "-r", "1.0", text])


def _rm(*paths):
    for f in paths:
        try:
            os.remove(f)
        except OSError:
            pass


def record_bt():
    """Capture via the PhosRec app (VAD auto-stop; BT headset mic if connected, else phone mic)."""
    cap = int(os.environ.get("VC_MAX", "12"))                # max cap; VAD ends the turn early on silence
    done = BT_WAV + ".done"
    _rm(BT_WAV, done)
    print(f"{C['d']}🎤 listening… (just talk; stops when you pause){C['r']}", flush=True)
    am = (f"am start -n io.cin.phosrec/.RecActivity -a io.cin.phosrec.RECORD "
          f"--ei secs {cap} --es out {BT_OUT}")
    # VC_AM_SU=1 launches via root (most reliable for starting a foreign activity from Termux);
    # default is plain `am`. If BT mode never captures, set VC_AM_SU=1 (the phone is rooted).
    cmd = ["su", "-c", am] if os.environ.get("VC_AM_SU") == "1" else am.split()
    try:
        sh(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=12)
    except Exception:
        pass
    deadline = time.time() + cap + 8                         # VAD ends early; cap + SCO/headroom
    while time.time() < deadline:
        if os.path.exists(done):
            break
        time.sleep(0.3)
    return BT_WAV if (os.path.exists(BT_WAV) and os.path.getsize(BT_WAV) > 2000) else None


def record_phone():
    """Capture from the phone's built-in mic via termux-microphone-record -> ~/phos/in.wav."""
    _rm(M4A, WAV)
    print(f"{C['d']}🎤 speak now ({SECS}s)…{C['r']}", flush=True)
    sh(["termux-microphone-record", "-f", M4A, "-l", str(SECS), "-e", "aac"],
       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(SECS + 0.4)
    sh(["termux-microphone-record", "-q"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not (os.path.exists(M4A) and os.path.getsize(M4A) > 1500):
        return None
    sh(["ffmpeg", "-y", "-i", M4A, "-ar", "16000", "-ac", "1", WAV],
       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return WAV if (os.path.exists(WAV) and os.path.getsize(WAV) > 2000) else None


def record():
    return record_bt() if USE_BT else record_phone()


def transcribe(wav):
    out = sh([WHISPER, "-m", WMODEL, "-f", wav, "-nt", "-np", "-l", "en", "-t", "6"],
             capture_output=True, text=True)
    return re.sub(r"\[[A-Z_ ]+\]", "", (out.stdout or "")).strip()


def llm(history):
    body = json.dumps({"messages": history, "temperature": 0.7, "max_tokens": 120,
                       "stream": False}).encode()
    req = urllib.request.Request(CHAT, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            data = json.loads(r.read())
    except Exception as e:
        return f"(brain hiccup: {type(e).__name__})"
    reply = data.get("choices", [{}])[0].get("message", {}).get("content", "")
    return re.sub(r"<think>.*?</think>", "", reply, flags=re.S).strip() or "…"


def main():
    for need, p in [("whisper-cli", WHISPER), ("whisper model", WMODEL),
                    ("llama-server", LLAMA_SERVER), ("LLM gguf", GGUF)]:
        if not os.path.exists(p):
            raise SystemExit(f"missing {need}: {p} — run setup.sh first")
    persona = open(PERSONA_FILE).read() if os.path.exists(PERSONA_FILE) else \
        "You are Phos, a warm, concise, offline voice companion. Plain spoken replies, no roleplay. /no_think"
    if not ensure_server():
        raise SystemExit("llama-server failed to come up — see ~/phos/llama-server.log\n"
                         "If it's low RAM (OOM): run the fast 0.6B alone with\n"
                         "  VC_MODEL=~/phos/Qwen3-0.6B-Q4_K_M.gguf python ~/phos/phos_voice.py")
    history = [{"role": "system", "content": persona}]
    print(f"{C['g']}Phos (on-phone, llama.cpp) — Qwen3-1.7B. Ctrl-C or say 'goodbye' to quit.{C['r']}")
    tts("Phos online. What's up?")
    try:
        while True:
            wav = record()
            if not wav:
                print(f"{C['d']}(no audio — try again){C['r']}")
                continue
            user = transcribe(wav)
            if not user:
                print(f"{C['d']}(couldn't make that out){C['r']}")
                continue
            print(f"{C['c']}you  ▸ {user}{C['r']}", flush=True)
            if re.search(r"\b(goodbye|good bye|quit|exit)\b", user, re.I):
                tts("Talk soon, Boo.")
                break
            history.append({"role": "user", "content": user})
            print(f"{C['d']}…thinking{C['r']}", flush=True)
            reply = llm(history)
            history.append({"role": "assistant", "content": reply})
            print(f"{C['y']}phos ▸ {reply}{C['r']}", flush=True)
            tts(reply)
    except KeyboardInterrupt:
        print("\n" + C['g'] + "bye." + C['r'])


if __name__ == "__main__":
    main()
