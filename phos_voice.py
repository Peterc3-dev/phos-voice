#!/data/data/com.termux/files/usr/bin/env python3
"""Phos — fully-local voice companion ON the Nothing Phone 2a (Termux). No internet at runtime.

Pipeline (all on-device): termux-microphone-record -> ffmpeg 16k -> whisper-cli (STT) ->
ollama qwen3:1.7b (LLM) -> termux-tts-speak (Android native TTS).

Setup once: run setup.sh (installs ffmpeg + termux-api, pulls the model, ensures ollama).
Run: python phos_voice.py     (Ctrl-C or say "goodbye" to quit)
Env: VC_SECS=record window (default 6), VC_MODEL=ollama model (default qwen3:1.7b)
"""
import os
import re
import json
import time
import subprocess
import urllib.request

HOME = os.path.expanduser("~")
DIR = os.path.join(HOME, "phos")
WHISPER = os.path.join(DIR, "whisper-cli")
WMODEL = os.path.join(DIR, "ggml-base.en-q5_1.bin")
PERSONA_FILE = os.path.join(DIR, "phos-system-prompt.txt")
M4A, WAV = os.path.join(DIR, "in.m4a"), os.path.join(DIR, "in.wav")
OLLAMA = "http://127.0.0.1:11434/api/chat"
MODEL = os.environ.get("VC_MODEL", "qwen3:1.7b")
SECS = int(os.environ.get("VC_SECS", "6"))
C = dict(g="\033[32m", c="\033[36m", y="\033[33m", d="\033[2m", r="\033[0m")


def sh(args, **kw):
    return subprocess.run(args, **kw)


def ensure_ollama():
    try:
        urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=3)
        return
    except Exception:
        pass
    print(f"{C['d']}starting ollama…{C['r']}")
    subprocess.Popen(["ollama", "serve"], stdout=open(os.path.join(DIR, "ollama.log"), "w"),
                     stderr=subprocess.STDOUT)
    for _ in range(20):
        time.sleep(1)
        try:
            urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=2)
            return
        except Exception:
            continue


def tts(text):
    sh(["termux-tts-speak", "-r", "1.0", text])


def record():
    print(f"{C['d']}🎤 speak now ({SECS}s)…{C['r']}", flush=True)
    for f in (M4A, WAV):
        try:
            os.remove(f)
        except OSError:
            pass
    sh(["termux-microphone-record", "-f", M4A, "-l", str(SECS), "-e", "aac"],
       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(SECS + 0.4)
    sh(["termux-microphone-record", "-q"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not (os.path.exists(M4A) and os.path.getsize(M4A) > 1500):
        return False
    sh(["ffmpeg", "-y", "-i", M4A, "-ar", "16000", "-ac", "1", WAV],
       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return os.path.exists(WAV) and os.path.getsize(WAV) > 2000


def transcribe():
    out = sh([WHISPER, "-m", WMODEL, "-f", WAV, "-nt", "-np", "-l", "en", "-t", "6"],
             capture_output=True, text=True)
    text = re.sub(r"\[[A-Z_ ]+\]", "", (out.stdout or "")).strip()
    return text


def llm(history):
    body = json.dumps({"model": MODEL, "messages": history, "stream": False,
                       "think": False,                       # qwen3: skip the slow hidden reasoning
                       "options": {"temperature": 0.7, "num_predict": 120}}).encode()
    req = urllib.request.Request(OLLAMA, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            data = json.loads(r.read())
    except Exception as e:
        return f"(brain hiccup: {type(e).__name__})"
    reply = data.get("message", {}).get("content", "")
    return re.sub(r"<think>.*?</think>", "", reply, flags=re.S).strip() or "…"


def main():
    for need, p in [("whisper-cli", WHISPER), ("whisper model", WMODEL)]:
        if not os.path.exists(p):
            raise SystemExit(f"missing {need}: {p} — run setup.sh first")
    persona = open(PERSONA_FILE).read() if os.path.exists(PERSONA_FILE) else \
        "You are Phos, a warm, concise, offline voice companion on the phone. Keep replies short and natural. /no_think"
    ensure_ollama()
    history = [{"role": "system", "content": persona}]
    print(f"{C['g']}Phos (on-phone) — model {MODEL}. Ctrl-C or say 'goodbye' to quit.{C['r']}")
    tts("Phos online. What's up?")
    try:
        while True:
            if not record():
                print(f"{C['d']}(no audio — try again){C['r']}")
                continue
            user = transcribe()
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
