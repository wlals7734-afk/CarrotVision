# CarrotVision 1.3 preview

Original Android UI; not copied FrogPilot UI. Generated white EV6 bitmap
sprite, transparent background, used as the ego and gray-tinted traffic icon.
Layout follows the supplied concept, not a pixel-identical reproduction.

This is an experimental read-only display, not a driving safety instrument.
No steering/braking settings are changed. Test parked with a passenger before use.
No vehicle/device testing has been performed.

Requires the included v2 bridge. The APK deliberately rejects v1 packets.
Show only one present-time vision lead; no invented adjacent vehicles.
Blind-spot warnings are labels, not distance/position tracks.
No liveTracks support in this preview: raw radar targets are not reliably
identified vehicles. No actual car make/model classification.

The UI requests redraw every 33 ms, but actual FPS depends on the device.
Projection is stylized and is not a calibrated spatial measurement.
If source carState/modelV2 validity or freshness fails, bridge stops sending;
APK removes traffic/path and blanks speed after 1.2 s.
Controls status is controlsState.enabled, not a verified lateral-control flag.
Newer forks using selfdriveState may need an adapter.

## Parked setup
1. Build APK in GitHub Actions, download the artifact ZIP, extract app-debug.apk.
2. Install on Android 8+ phone/head unit (not directly on a comma).
3. Copy comma/carrot_vision_bridge.py to /data/carrot_vision_bridge.py on comma.
4. Run from an openpilot Python environment:
   cd /data/openpilot
   python3 /data/carrot_vision_bridge.py
5. Connect devices to the same trusted Wi-Fi. Stop any older bridge first.
   If broadcasts are filtered, set CARROT_VISION_HOST to the Android IP.
   No automatic boot registration is added. Do not expose UDP 8855 to the internet.

Debug builds may use different signing keys across builds: upgrades may require
uninstalling the previous test app. APK signing does not validate vehicle behavior.
No private credentials, recordings, or location logs are included.
