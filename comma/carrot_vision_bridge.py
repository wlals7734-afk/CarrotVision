#!/usr/bin/env python3
"""Read-only bridge: visualize Comma camera vehicle perception without changing controls."""
import json
import math
import os
import socket
import sys
import time

root = os.environ.get("OPENPILOT_ROOT", "/data/openpilot")
sys.path.insert(0, root)
sys.path.insert(1, os.path.join(root, "openpilot"))
import cereal.messaging as messaging

DISCOVERY_PORT = 8856
DATA_PORT = int(os.environ.get("CARROT_VISION_PORT", "8855"))
DISCOVERY_MAGIC = b"CV_DISCOVER_V1"
CLIENT_TTL = 12.0
TX_HZ = 15.0
TX_INTERVAL = 1.0 / TX_HZ

# Display-layer filtering/tracking only. This does not modify openpilot/CarrotPilot perception or control.
CAMERA_MIN_PROB = 0.50
VEHICLE_MAX_LATERAL = 7.0
TRACK_MATCH_DX = 12.0
TRACK_MATCH_DY = 2.4
TRACK_HOLD_SEC = 0.45
TRACK_CONFIRM_FRAMES = 2
TRACK_SMOOTH_X = 0.68
TRACK_SMOOTH_Y = 0.58
RADAR_MATCH_DY = 1.8


def points(line):
    result = []
    for x, y in zip(line.x, line.y):
        x, y = float(x), float(y)
        if math.isfinite(x) and math.isfinite(y) and 0 <= x <= 150:
            result.append([x, y])
    return result[:33]


def fresh(sm, service, now):
    if service not in sm.recv_time:
        return False
    age = now - sm.recv_time[service]
    return sm.seen[service] and sm.valid[service] and 0 <= age < 0.7


def optional_bool(obj, field):
    try:
        return bool(getattr(obj, field))
    except AttributeError:
        return None


def active_state(sm, now):
    if fresh(sm, "controlsState", now):
        lateral = optional_bool(sm["controlsState"], "lateralActive")
        if lateral is not None:
            return lateral
    for service in ("selfdriveState", "controlsState"):
        if fresh(sm, service, now):
            enabled = optional_bool(sm[service], "enabled")
            if enabled is not None:
                return enabled
    return False


def optional_field(obj, name, default=None):
    try:
        return getattr(obj, name)
    except AttributeError:
        return default


def _lead_to_car(lead, source):
    """Convert one Comma-selected radar lead to HUD coordinates."""
    if lead is None or not optional_field(lead, "status", False):
        return None
    try:
        x = float(optional_field(lead, "dRel", 0.0))
        y = -float(optional_field(lead, "yRel", 0.0))
        v = float(optional_field(lead, "vRel", 0.0))
    except (TypeError, ValueError, OverflowError):
        return None
    if not (math.isfinite(x) and math.isfinite(y) and math.isfinite(v)):
        return None
    if not (1.0 <= x <= 150.0 and abs(y) <= 8.5):
        return None
    return dict(x=x, y=y, v=v, p=1.0, source=source, type="car")


def comma_ui_cars(radar_state):
    """Compatibility helper for the four Comma-selected lead slots."""
    if radar_state is None:
        return []
    result = []
    for field, source in (
        ("leadOne", "commaLeadOne"),
        ("leadTwo", "commaLeadTwo"),
        ("leadLeft", "commaLeadLeft"),
        ("leadRight", "commaLeadRight"),
    ):
        car = _lead_to_car(optional_field(radar_state, field), source)
        if car is not None:
            result.append(car)
    return result


def model_leads(model):
    """Read every camera lead hypothesis exposed by modelV2, preserving probability."""
    result = []
    if model is None:
        return result
    for index, lead in enumerate(optional_field(model, "leadsV3", ())):
        try:
            xs = optional_field(lead, "x", ())
            ys = optional_field(lead, "y", ())
            vs = optional_field(lead, "v", ())
            if len(xs) == 0 or len(ys) == 0:
                continue
            x = float(xs[0])
            y = float(ys[0])
            v = float(vs[0]) if len(vs) else 0.0
            p = float(optional_field(lead, "prob", 0.0))
        except (TypeError, ValueError, OverflowError, IndexError):
            continue
        if not all(math.isfinite(value) for value in (x, y, v, p)):
            continue
        if not (0.0 <= x <= 150.0 and abs(y) <= 10.0):
            continue
        result.append(dict(x=x, y=y, v=v, p=max(0.0, min(1.0, p)), source="modelLead%d" % index))
    return result


def radar_points(live_tracks):
    """Compatibility helper; raw radar points are not rendered in the release UI."""
    result = []
    if live_tracks is None:
        return result
    for point in optional_field(live_tracks, "points", ()):
        try:
            x = float(optional_field(point, "dRel", 0.0))
            y = -float(optional_field(point, "yRel", 0.0))
            v = float(optional_field(point, "vRel", 0.0))
            measured = bool(optional_field(point, "measured", False))
            source = str(optional_field(point, "radarSource", "unknown"))
        except (TypeError, ValueError, OverflowError):
            continue
        if not all(math.isfinite(value) for value in (x, y, v)):
            continue
        if not (0.0 <= x <= 150.0 and abs(y) <= 12.0):
            continue
        result.append(dict(x=x, y=y, v=v, measured=measured, source=source))
    return result


def _radar_track_to_car(lead):
    if lead is None or not optional_field(lead, "status", False):
        return None
    try:
        x = float(optional_field(lead, "dRel", 0.0))
        y = -float(optional_field(lead, "yRel", 0.0))
        v = float(optional_field(lead, "vRel", 0.0))
        track_id = int(optional_field(lead, "radarTrackId", -1))
    except (TypeError, ValueError, OverflowError):
        return None
    if not all(math.isfinite(value) for value in (x, y, v)):
        return None
    if not (1.0 <= x <= 150.0 and abs(y) <= VEHICLE_MAX_LATERAL + 1.5):
        return None
    return dict(x=x, y=y, v=v, track_id=track_id)


def all_radar_lane_tracks(radar_state):
    """Collect all lane-grouped radar tracks only as position support, never as vehicles by themselves."""
    if radar_state is None:
        return []
    result = []
    seen_ids = set()
    for field in ("leadsCenter", "leadsLeft", "leadsRight"):
        for lead in optional_field(radar_state, field, ()) or ():
            item = _radar_track_to_car(lead)
            if item is None:
                continue
            rid = item["track_id"]
            if rid >= 0 and rid in seen_ids:
                continue
            if rid >= 0:
                seen_ids.add(rid)
            result.append(item)
    # Some forks/vehicles may not populate the lists. Add selected slots as fallback support.
    for field in ("leadOne", "leadTwo", "leadLeft", "leadRight"):
        item = _radar_track_to_car(optional_field(radar_state, field))
        if item is None:
            continue
        rid = item["track_id"]
        if rid >= 0 and rid in seen_ids:
            continue
        if rid >= 0:
            seen_ids.add(rid)
        result.append(item)
    return result


def camera_supported_candidates(model_lead_list, radar_state, ego_speed):
    """Create vehicle display candidates only from camera car-lead hypotheses.

    Radar is used only to refine position when a nearby track agrees. Radar-only objects are never promoted
    to vehicles, preventing dividers/guardrails from appearing solely because of a radar return.
    """
    radar_tracks = all_radar_lane_tracks(radar_state)
    candidates = []
    for lead in model_lead_list:
        p = float(lead.get("p", 0.0))
        x = float(lead.get("x", 0.0))
        y = float(lead.get("y", 0.0))
        if p < CAMERA_MIN_PROB or x < 1.0 or x > 150.0 or abs(y) > VEHICLE_MAX_LATERAL:
            continue

        best = None
        best_score = float("inf")
        dx_limit = max(5.0, x * 0.35)
        for radar in radar_tracks:
            dx = abs(radar["x"] - x)
            dy = abs(radar["y"] - y)
            if dx > dx_limit or dy > RADAR_MATCH_DY:
                continue
            score = dx + dy * 4.0
            if score < best_score:
                best_score = score
                best = radar

        if best is not None:
            cx, cy, cv = best["x"], best["y"], best["v"]
            support = "radar%d" % best["track_id"] if best["track_id"] >= 0 else "radar"
        else:
            cx, cy = x, y
            cv = float(lead.get("v", 0.0)) - float(ego_speed)
            support = "vision"

        candidates.append(dict(x=cx, y=cy, v=cv, p=p, source=support, type="car"))
    return candidates


def stabilize_vehicle_tracks(candidates, tracks, now, next_track_id):
    """Stable display tracking: no hard vehicle-count limit, short dropout hold, smooth position updates."""
    available_ids = set(tracks.keys())
    assignments = []

    # Match closest existing display track first. This avoids jumps if model lead indices reorder.
    for candidate in sorted(candidates, key=lambda c: -float(c.get("p", 0.0))):
        best_id = None
        best_score = float("inf")
        for track_id in available_ids:
            state = tracks[track_id]
            dx = abs(state["x"] - candidate["x"])
            dy = abs(state["y"] - candidate["y"])
            if dx > TRACK_MATCH_DX or dy > TRACK_MATCH_DY:
                continue
            score = dx + dy * 5.0
            if score < best_score:
                best_score = score
                best_id = track_id
        if best_id is None:
            best_id = next_track_id[0]
            next_track_id[0] += 1
            tracks[best_id] = dict(x=candidate["x"], y=candidate["y"], v=candidate["v"], p=candidate["p"],
                                   hits=0, visible=False, last_seen=now)
        else:
            available_ids.remove(best_id)
        assignments.append((best_id, candidate))

    touched = set()
    for track_id, candidate in assignments:
        state = tracks[track_id]
        if state["hits"] == 0:
            state["x"] = candidate["x"]
            state["y"] = candidate["y"]
        else:
            state["x"] += (candidate["x"] - state["x"]) * TRACK_SMOOTH_X
            state["y"] += (candidate["y"] - state["y"]) * TRACK_SMOOTH_Y
        state["v"] = candidate["v"]
        state["p"] = candidate["p"]
        state["hits"] += 1
        state["last_seen"] = now
        if state["hits"] >= TRACK_CONFIRM_FRAMES:
            state["visible"] = True
        touched.add(track_id)

    output = []
    expired = []
    for track_id, state in tracks.items():
        age = now - state["last_seen"]
        if track_id not in touched and age > TRACK_HOLD_SEC:
            expired.append(track_id)
            continue
        if state["visible"] and age <= TRACK_HOLD_SEC:
            output.append(dict(x=state["x"], y=state["y"], v=state["v"], p=state["p"],
                               source="cameraTrack%d" % track_id, type="car"))
    for track_id in expired:
        tracks.pop(track_id, None)
    return output


def traffic_state(sm, now):
    if fresh(sm, "longitudinalPlan", now):
        state = optional_field(sm["longitudinalPlan"], "trafficState", 0)
        if state in (1, 2):
            return int(state)
    return 0


def open_discovery_socket():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.bind(("", DISCOVERY_PORT))
    s.setblocking(False)
    return s


def update_clients(discovery, clients, now):
    while True:
        try:
            payload, addr = discovery.recvfrom(256)
        except BlockingIOError:
            break
        except OSError:
            break
        if payload.startswith(DISCOVERY_MAGIC):
            ip = addr[0]
            first = ip not in clients
            clients[ip] = now
            if first:
                print("CarrotVision receiver discovered at %s:%d" % (ip, DATA_PORT), flush=True)
    expired = [ip for ip, seen in clients.items() if now - seen > CLIENT_TTL]
    for ip in expired:
        clients.pop(ip, None)


def targets(clients):
    if clients:
        return [(ip, DATA_PORT) for ip in sorted(clients)]
    host = os.environ.get("CARROT_VISION_HOST", "255.255.255.255")
    return [(host, DATA_PORT)]


def main():
    services = ["carState", "modelV2", "controlsState", "radarState", "longitudinalPlan"]
    try:
        from cereal.services import SERVICE_LIST
        if "selfdriveState" in SERVICE_LIST:
            services.append("selfdriveState")
    except ImportError:
        pass

    sm = messaging.SubMaster(services)
    sent = 0
    print("CarrotVision bridge v2.15 all-vehicles-stable started; 15 Hz TX + camera-gated vehicles + no UI count cap", flush=True)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    discovery = open_discovery_socket()
    clients = {}
    display_tracks = {}
    next_track_id = [1]

    while True:
        sm.update(50)
        now = time.monotonic()
        update_clients(discovery, clients, now)
        if not (fresh(sm, "carState", now) and fresh(sm, "modelV2", now)):
            time.sleep(0.05)
            continue

        cs, model = sm["carState"], sm["modelV2"]
        left_blindspot = bool(getattr(cs, "leftBlindspot", False))
        right_blindspot = bool(getattr(cs, "rightBlindspot", False))
        raw_model_leads = model_leads(model)
        radar_state = sm["radarState"] if fresh(sm, "radarState", now) else None
        candidates = camera_supported_candidates(raw_model_leads, radar_state, float(cs.vEgo))
        cars = stabilize_vehicle_tracks(candidates, display_tracks, now, next_track_id)

        lanes = [dict(p=float(prob), pts=points(line))
                 for line, prob in zip(model.laneLines, model.laneLineProbs)]

        packet = dict(version=2, fresh=True, time=int(time.time()*1000),
                      trafficState=traffic_state(sm, now),
                      speed=float(cs.vEgo)*3.6, steering=float(cs.steeringAngleDeg),
                      enabled=active_state(sm, now),
                      brakeLights=optional_bool(cs, "brakeLights"),
                      leftBlinker=bool(cs.leftBlinker), rightBlinker=bool(cs.rightBlinker),
                      leftBlindspot=left_blindspot, rightBlindspot=right_blindspot,
                      cars=cars, modelLeads=raw_model_leads, radarPoints=[],
                      path=points(model.position), lanes=lanes)
        try:
            data = json.dumps(packet, allow_nan=False, separators=(",", ":")).encode()
            active_targets = targets(clients)
            for target in active_targets:
                sock.sendto(data, target)
            sent += 1
            if sent == 1:
                print("Sending camera-confirmed vehicle display data at %.0f Hz to %s" % (TX_HZ, active_targets), flush=True)
        except (OSError, ValueError) as error:
            print(error, flush=True)
        time.sleep(TX_INTERVAL)


if __name__ == "__main__":
    main()
