# Reliable SSH access to the phone (for live debugging)

Makes the phone *dial home* to the always-on hub (thinkhub) over a reverse SSH tunnel, so
it's reachable without chasing its flaky inbound Tailscale — and it self-heals after reboots,
OOM kills, and network drops.

```
laptop ──(stable LAN/tailnet)──► thinkhub ──(reverse tunnel:8023→8022)──► phone sshd
```

## On the phone (Termux) — one time
1. Install the **Termux:Boot** app (F-Droid) and **open it once** (Android requires that before
   boot scripts run). In Android Settings, **disable battery optimization** for Termux + Termux:Boot.
2. Make sure **Tailscale is ON with Exit Node = None** (the exit-node-to-a-dead-node bug is what
   killed the phone's internet before).
3. Run:
   ```sh
   cd ~/phos-voice && git pull && bash ssh-access/setup-ssh.sh
   ```
4. It prints an `echo … >> ~/.ssh/authorized_keys` line — **add that key to thinkhub** (run it on
   thinkhub, or paste the key to me and I'll add it). The tunnel can't connect until this is done.

After that the tunnel comes up on boot automatically.

## From the laptop (already configured)
```sh
ssh phone-hub
```
(That host is wired in `~/.ssh/config` to hop through thinkhub into the reverse tunnel.)

## Why this is robust
- **autossh** re-dials the tunnel forever — transient mobile/Tailscale drops self-heal.
- **Termux:Boot + wake-lock** keeps it alive across reboots and stops Android freezing Termux.
- **sshd watchdog** restarts sshd within ~60 s if the memory-killer reaps it.
- Outbound (phone→hub) traverses mobile NAT far better than inbound.

Prereq that still holds: the phone needs Tailscale up (exit node off) so it can reach the hub.
