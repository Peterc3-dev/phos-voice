#!/data/data/com.termux/files/usr/bin/sh
# Phos SSH keepalive — runs at boot via Termux:Boot (place in ~/.termux/boot/).
# Keeps the phone reachable for live debugging without you babysitting it:
#  - wake-lock so Termux isn't frozen/killed
#  - local sshd (for same-wifi or Tailscale-direct access)
#  - a reverse SSH tunnel OUT to the always-on hub (thinkhub), so inbound flakiness
#    doesn't matter — autossh re-dials forever and the hub holds the endpoint
#  - sshd watchdog (restarts it if Android's memory-killer reaps it)
HUB="raz@100.123.55.83"        # thinkhub Tailscale IP (always-on)
RPORT=8023                     # hub-side port that maps to this phone's sshd (8022)

# already set up this boot? (avoid duplicate tunnel/watchdog loops on a manual re-run)
pgrep -f "autossh.*-R ${RPORT}:127.0.0.1:8022" >/dev/null 2>&1 && exit 0

termux-wake-lock

pgrep -x sshd >/dev/null 2>&1 || sshd

# reverse tunnel: hub:127.0.0.1:RPORT -> phone:127.0.0.1:8022 ; autossh keeps it up
( while true; do
    autossh -M 0 -N \
      -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
      -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=accept-new \
      -R ${RPORT}:127.0.0.1:8022 "$HUB"
    sleep 10
  done ) >/dev/null 2>&1 &

# sshd watchdog (recover from OOM kills)
( while true; do pgrep -x sshd >/dev/null 2>&1 || sshd; sleep 60; done ) >/dev/null 2>&1 &
