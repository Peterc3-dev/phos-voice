#!/data/data/com.termux/files/usr/bin/bash
# Phos voice companion — one-time on-phone install (Termux). Run from the cloned repo:
#   bash phos-voice/setup.sh
# Uses a llama.cpp build tuned for this exact chip (i8mm/dotprod) — no ollama. Fully offline
# at runtime; the only network use is this one-time download of the STT + LLM models.
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
DIR="$HOME/phos"; mkdir -p "$DIR"
WURL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"
LURL="https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
DURL="https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"

echo "[1/5] Termux packages (ffmpeg, termux-api, python, wget)…"
pkg install -y ffmpeg termux-api python wget >/dev/null

echo "[2/5] copy app files + binaries -> $DIR…"
cp "$SRC/phos_voice.py" "$SRC/phos-system-prompt.txt" "$SRC/whisper-cli" "$SRC/llama-server" "$SRC/beep.wav" "$DIR/"
chmod +x "$DIR/whisper-cli" "$DIR/llama-server"

echo "[3/5] whisper STT model (~57 MB, one-time)…"
[ -f "$DIR/ggml-base.en-q5_1.bin" ] || wget -q --show-progress -O "$DIR/ggml-base.en-q5_1.bin" "$WURL"

echo "[4/5] LLM model — Qwen3-1.7B Q4_K_M (~1.1 GB, one-time)…"
[ -f "$DIR/Qwen3-1.7B-Q4_K_M.gguf" ] || wget -q --show-progress -O "$DIR/Qwen3-1.7B-Q4_K_M.gguf" "$LURL"

echo "[5/5] speculative draft — Qwen3-0.6B Q4_K_M (~0.4 GB, one-time)…"
[ -f "$DIR/Qwen3-0.6B-Q4_K_M.gguf" ] || wget -q --show-progress -O "$DIR/Qwen3-0.6B-Q4_K_M.gguf" "$DURL"

echo
command -v termux-tts-speak >/dev/null && echo "termux-api CLI: ok" || echo "⚠ termux-api CLI missing"
echo "⚠ Also install the **Termux:API app** (F-Droid) — the pkg alone can't reach mic/TTS."
echo
echo "✅ Done. Talk to Phos:   python $DIR/phos_voice.py"
