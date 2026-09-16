#!/usr/bin/env sh
set -eu

install -m 755 ./carrot_vision_bridge.py /data/carrot_vision_bridge.py

# Stop an older CarrotVision bridge instance, then start the new read-only mirror bridge.
pkill -f '/data/carrot_vision_bridge.py' 2>/dev/null || true
cd /data/openpilot
nohup python3 /data/carrot_vision_bridge.py >/data/carrot_vision_bridge.log 2>&1 &
echo $! >/data/carrot_vision_bridge.pid
sleep 1

echo "CarrotVision mirror bridge started."
echo "PID: $(cat /data/carrot_vision_bridge.pid)"
echo "LOG: /data/carrot_vision_bridge.log"
echo "The Android app will auto-discover this Comma device on UDP 8856 and receive mirror data on UDP 8855."
