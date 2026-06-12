#!/data/data/com.termux/files/usr/bin/bash
# One-time: make the phone reliably reachable for live SSH debugging via a reverse tunnel
# to the always-on hub (thinkhub). Run in Termux:  bash ssh-access/setup-ssh.sh
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"

echo "[1/4] packages (openssh, autossh, termux-api)…"
pkg install -y openssh autossh termux-api >/dev/null

echo "[2/4] ssh key (this phone -> hub)…"
[ -f "$HOME/.ssh/id_ed25519" ] || ssh-keygen -t ed25519 -N "" -f "$HOME/.ssh/id_ed25519" >/dev/null

echo "[3/4] install boot keepalive…"
mkdir -p "$HOME/.termux/boot"
cp "$SRC/keepalive.sh" "$HOME/.termux/boot/10-phos-keepalive.sh"
chmod +x "$HOME/.termux/boot/10-phos-keepalive.sh"

echo "[4/4] start it now…"
sh "$HOME/.termux/boot/10-phos-keepalive.sh"

echo
echo "=============================================================="
echo "ADD THIS PHONE'S KEY TO THINKHUB so the tunnel can connect."
echo "On thinkhub (or from the laptop) run:"
echo
echo "  echo '$(cat "$HOME/.ssh/id_ed25519.pub")' >> ~/.ssh/authorized_keys"
echo
echo "Also do once: install the **Termux:Boot** app (F-Droid) and open it,"
echo "and disable battery optimization for Termux + Termux:Boot."
echo "Make sure Tailscale is ON with Exit Node = None."
echo "=============================================================="
