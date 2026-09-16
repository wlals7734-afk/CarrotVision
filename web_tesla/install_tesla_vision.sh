#!/usr/bin/env bash
set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/wlals7734-afk/CarrotVision/main/web_tesla"
WEB_ROOT="${OPENPILOT_ROOT:-/data/openpilot}/openpilot/selfdrive/carrot/web"
BACKUP_DIR="/data/carrot_tesla_vision_backup"
INDEX="$WEB_ROOT/index.html"
OVERLAY="$WEB_ROOT/js/realtime/tesla_vision_overlay.js"
TMP_B64="/tmp/tesla_vision_overlay.js.gz.b64"
TMP_GZ="/tmp/tesla_vision_overlay.js.gz"

if [ ! -f "$INDEX" ]; then
  echo "ERROR: Carrot Web not found: $INDEX" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
if [ ! -f "$BACKUP_DIR/index.html" ]; then
  cp -a "$INDEX" "$BACKUP_DIR/index.html"
fi
if [ -f "$OVERLAY" ] && [ ! -f "$BACKUP_DIR/tesla_vision_overlay.js.previous" ]; then
  cp -a "$OVERLAY" "$BACKUP_DIR/tesla_vision_overlay.js.previous"
fi

curl -fL "$REPO_RAW/tesla_vision_overlay.js.gz.b64" -o "$TMP_B64"
base64 -d "$TMP_B64" > "$TMP_GZ"
gzip -dc "$TMP_GZ" > "$OVERLAY"
chmod 0644 "$OVERLAY"
gzip -9 -c "$OVERLAY" > "$OVERLAY.gz"

python3 - "$INDEX" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
tag = '  <script src="/js/realtime/tesla_vision_overlay.js?v=1"></script>\n'
if 'tesla_vision_overlay.js' not in s:
    needle = '  <script src="/js/realtime/home_drive.js"></script>\n'
    if needle not in s:
        raise SystemExit('home_drive.js script tag not found')
    s = s.replace(needle, needle + tag, 1)
p.write_text(s, encoding='utf-8')
PY

sync
rm -f "$TMP_B64" "$TMP_GZ"
cat <<'MSG'
Tesla Vision v1 installed.
- Browser UI only. openpilot/CarrotPilot control and perception code were not changed.
- Refresh or Reconnect Carrot Web, then open Carrot Vision.
- Default mode: synthetic Tesla-style view.

Browser console options:
  carrotTeslaVision("camera")
  carrotTeslaVision("synthetic")
  carrotTeslaVisionDebug(true)
MSG
