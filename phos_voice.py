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
GGUF = os.path.join(DIR, "Qwen3-1.7B-Q4_K_M.gguf")
PERSONA_FILE = os.path.join(DIR, "phos-system-prompt.txt")
M4A, WAV = os.path.join(DIR, "in.m4a"), os.path.join(DIR, "in.wav")
HEALTH = "http://127.0.0.1:8080/health"
CHAT = "http://127.0.0.1:8080/v1/chat/completions"
SECS = int(os.environ.get("VC_SECS", "6"))
THREADS = os.environ.get("VC_THREADS", "4")          # 4 = best generation on the MT6886 (measured)
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
        print(f"{C['d']}starting llama-server (loading model, first time ~30-60s)…{C['r']}")
        subprocess.Popen([LLAMA_SERVER, "-m", GGUF, "-t", THREADS, "-c", "4096",
                          "--host", "127.0.0.1", "--port", "8080"],
                         stdout=open(os.path.join(DIR, "llama-server.log"), "w"),
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
        raise SystemExit("llama-server failed to come up — see ~/phos/llama-server.log")
    history = [{"role": "system", "content": persona}]
    print(f"{C['g']}Phos (on-phone, llama.cpp) — Qwen3-1.7B. Ctrl-C or say 'goodbye' to quit.{C['r']}")
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
