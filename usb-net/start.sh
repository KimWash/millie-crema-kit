#!/usr/bin/env bash
# usage: usb-net/start.sh
# Shares this Mac's internet with the CREMA over the USB (adb) cable: PPP over `adb shell`,
# NAT on the Mac. Ctrl-C stops it and puts both sides back.
#
# Android 4.2 apps only count Wi-Fi as "online", so keep the CREMA's Wi-Fi joined to some
# network (even one without internet). Traffic still goes over USB because the default
# route and DNS are moved to ppp0.
#
# Needs: rooted CREMA (su), adb, the Mac password (sudo for pppd / pf / ip forwarding).
set -euo pipefail

HOST_IP=10.77.0.1
DEV_IP=10.77.0.2
DNS1=8.8.8.8
DNS2=1.1.1.1
ANCHOR=com.apple/250.CremaUsbNat

ADB=$(command -v adb) || { echo "adb not found"; exit 1; }
SERIAL=${ANDROID_SERIAL:-$("$ADB" get-serialno 2>/dev/null | tr -d '\r')}
[ -n "$SERIAL" ] && [ "$SERIAL" != unknown ] || { echo "no device"; exit 1; }
A() { "$ADB" -s "$SERIAL" "$@"; }
A shell "su -c id" | grep -q 'uid=0' || { echo "root (su) required on the device"; exit 1; }

UP=$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')
[ -n "$UP" ] || { echo "this Mac has no internet route"; exit 1; }
echo "Mac uplink: $UP   device: $SERIAL"

sudo -v
FWD_OLD=$(sysctl -n net.inet.ip.forwarding)
PF_TOKEN=""
PPPD_PID=""

restore_device() {
  # default route + DNS back to Wi-Fi (gateway from the Wi-Fi DHCP lease)
  A shell "su -c 'for p in \$(ps | grep /system/bin/pppd | grep -v grep | while read U P R; do echo \$P; done); do kill \$p; done
    while ip route del default dev ppp0 2>/dev/null; do :; done
    G=\$(getprop dhcp.wlan0.gateway); [ -n \"\$G\" ] && ip route add default via \$G dev wlan0 2>/dev/null
    ndc resolver setdefaultif wlan0 >/dev/null
    D=\$(getprop dhcp.wlan0.dns1); [ -n \"\$D\" ] && setprop net.dns1 \$D'" >/dev/null 2>&1 || true
}

cleanup() {
  trap - EXIT INT TERM
  echo; echo "stopping"
  [ -n "$PPPD_PID" ] && sudo kill "$PPPD_PID" 2>/dev/null || true
  [ -n "${SUDO_KEEP:-}" ] && kill "$SUDO_KEEP" 2>/dev/null || true
  restore_device
  sudo pfctl -a "$ANCHOR" -F all 2>/dev/null || true
  [ -n "$PF_TOKEN" ] && sudo pfctl -X "$PF_TOKEN" 2>/dev/null || true
  sudo sysctl -w net.inet.ip.forwarding="$FWD_OLD" >/dev/null
  echo "done"
}
trap cleanup EXIT INT TERM

echo "[1/3] Mac: forwarding + NAT ($DEV_IP -> $UP)"
sudo sysctl -w net.inet.ip.forwarding=1 >/dev/null
echo "nat on $UP inet from $DEV_IP to any -> ($UP)" | sudo pfctl -a "$ANCHOR" -f - 2>/dev/null
PF_TOKEN=$(sudo pfctl -E 2>&1 | awk '/Token/{print $3}')

# keep the sudo ticket alive so reconnects don't ask for the password again
( while sleep 60; do sudo -n -v 2>/dev/null || exit; done ) &
SUDO_KEEP=$!

# device side: raw pty, root pppd on its own tty, takes the address the Mac gives it
DEV="busybox stty raw -echo; exec su -c '/system/bin/pppd /dev/tty nodetach noauth local noipdefault ipcp-accept-local ipcp-accept-remote nodefaultroute noccp novj nopcomp noaccomp asyncmap 0'"

connect() {
  echo "[2/3] PPP over adb"
  # lcp-echo: notice within ~15 s when the adb link is gone (cable, sleep, adb restart)
  sudo /usr/sbin/pppd pty "'$ADB' -s '$SERIAL' shell \"$DEV\"" \
    nodetach noauth local noccp novj nopcomp noaccomp asyncmap 0 lcp-echo-interval 5 lcp-echo-failure 3 \
    "$HOST_IP:$DEV_IP" ms-dns "$DNS1" > /tmp/crema-usbnet-pppd.log 2>&1 &
  PPPD_PID=$!
  for i in $(seq 1 30); do
    A shell "ip addr show ppp0 2>/dev/null" | grep -q "$DEV_IP" && break
    kill -0 "$PPPD_PID" 2>/dev/null || { echo "pppd exited:"; tail -5 /tmp/crema-usbnet-pppd.log; return 1; }
    sleep 1
  done
  A shell "ip addr show ppp0 2>/dev/null" | grep -q "$DEV_IP" || { echo "ppp0 did not come up"; sudo kill "$PPPD_PID" 2>/dev/null; return 1; }

  echo "[3/3] device: default route + DNS -> ppp0"
  A shell "su -c 'while ip route del default 2>/dev/null; do :; done
    ip route add default dev ppp0
    ndc resolver setifdns ppp0 $DNS1 $DNS2 >/dev/null
    ndc resolver setdefaultif ppp0 >/dev/null
    ndc resolver flushdefaultif >/dev/null 2>&1
    setprop net.dns1 $DNS1; setprop net.dns2 $DNS2'" | tr -d '\r'

  if A shell "ping -c 2 -W 3 $DNS1" | grep -q " 0% packet loss\| 50% packet loss"; then
    echo "online over USB ($(date +%H:%M)). Ctrl-C to stop."
  else
    echo "link is up but ping failed; see /tmp/crema-usbnet-pppd.log. Ctrl-C to stop."
  fi
  wait "$PPPD_PID" 2>/dev/null
  PPPD_PID=""
}

# reconnect whenever the link drops; Ctrl-C ends it (cleanup puts both sides back)
while true; do
  connect || true
  echo "link down ($(date +%H:%M)); waiting for the device..."
  "$ADB" -s "$SERIAL" wait-for-device
  sleep 3
done
