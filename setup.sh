#!/data/data/com.termux/files/usr/bin/bash
# Phos voice companion — one-time on-phone install (Termux). Run from the cloned repo:
#   bash phos-voice/setup.sh
# Everything runs locally on the phone after this; the only network use is this one-time
# download of the whisper model + the ollama model.
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"          # cloned repo dir
DIR="$HOME/phos"; mkdir -p "$DIR"
MODEL="${VC_MODEL:-qwen3:1.7b}"
WURL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"

echo "[1/5] Termux packages (ffmpeg, termux-api, python, wget, ollama)…"
pkg install -y ffmpeg termux-api python wget ollama >/dev/null

echo "[2/5] copy app files -> $DIR…"
cp "$SRC/phos_voice.py" "$SRC/phos-system-prompt.txt" "$SRC/whisper-cli" "$DIR/"
chmod +x "$DIR/whisper-cli"

echo "[3/5] whisper STT model (~57 MB, one-time)…"
[ -f "$DIR/ggml-base.en-q5_1.bin" ] || wget -q --show-progress -O "$DIR/ggml-base.en-q5_1.bin" "$WURL"

echo "[4/5] ollama brain — pull $MODEL (~1.1 GB, one-time)…"
pgrep -f "ollama serve" >/dev/null || { nohup ollama serve >"$DIR/ollama.log" 2>&1 & sleep 6; }
ollama pull "$MODEL"

echo "[5/5] checks…"
command -v termux-tts-speak >/dev/null && echo "  termux-api CLI: ok" || echo "  ⚠ termux-api CLI missing"
echo "  ⚠ Also install the **Termux:API app** (F-Droid) — the pkg alone can't reach mic/TTS."
echo
echo "✅ Done. Talk to Phos:   python $DIR/phos_voice.py"
