#!/usr/bin/env sh
set -eu
install -m 755 ./carrot_vision_bridge.py /data/carrot_vision_bridge.py
echo "복사 완료. 정차 상태에서 기존 브리지를 종료하고 실행하세요:"
echo "cd /data/openpilot && python3 /data/carrot_vision_bridge.py"
