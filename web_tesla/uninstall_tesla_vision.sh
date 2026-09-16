#!/usr/bin/env bash
set -euo pipefail
WEB_ROOT="${OPENPILOT_ROOT:-/data/openpilot}/openpilot/selfdrive/carrot/web"
BACKUP_DIR="/data/carrot_tesla_vision_backup"
INDEX="$WEB_ROOT/index.html"
OVERLAY="$WEB_ROOT/js/realtime/tesla_vision_overlay.js"

if [ -f "$BACKUP_DIR/index.html" ]; then
  cp -a "$BACKUP_DIR/index.html" "$INDEX"
elif [ -f "$INDEX" ]; then
  python3 - "$INDEX" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')
s='\n'.join(line for line in s.splitlines() if 'tesla_vision_overlay.js' not in line)+'\n'
p.write_text(s,encoding='utf-8')
PY
fi
rm -f "$OVERLAY" "$OVERLAY.gz" "$OVERLAY.br"
sync
echo "Tesla Vision removed. Refresh/Reconnect Carrot Web."
