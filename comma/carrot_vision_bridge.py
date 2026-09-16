#!/usr/bin/env python3
"""Read-only bridge: mirror Comma/CarrotPilot perception state without changing controls."""
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
SIDE_CONFIRM_FRAMES = 2
SIDE_HOLD_SEC = 0.55
SIDE_MATCH_DX = 12.0
SIDE_MATCH_DY = 2.0
SIDE_MIN_LATERAL = 1.25
SIDE_MAX_LATERAL = 5.8
SIDE_MOVING_EGO_MPS = 5.0
SIDE_MIN_WORLD_SPEED_MPS = 2.0
MODEL_MATCH_DX = 8.0
MODEL_MATCH_DY = 2.2
MODEL_MATCH_MIN_P = 0.45


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
    """Convert one Comma-selected lead to HUD coordinates without re-classifying it."""
    if lead is None or not optional_field(lead, "status", False):
        return None
    try:
        x = float(optional_field(lead, "dRel", 0.0))
        # radarState yRel is positive LEFT; HUD/model lateral is positive RIGHT.
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
    """Read the lead slots selected by Comma/CarrotPilot itself."""
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
    """Mirror raw modelV2.leadsV3 candidates, preserving their probability."""
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
    """Mirror raw liveTracks radar returns. These are radar points, not vehicle classifications."""
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


def model_matches_car(car, leads):
    """Use a model lead only as supporting evidence for a side object, never as a car by itself."""
    for lead in leads:
        if float(lead.get("p", 0.0)) < MODEL_MATCH_MIN_P:
            continue
        if abs(float(lead.get("x", 999.0)) - car["x"]) <= MODEL_MATCH_DX and \
           abs(float(lead.get("y", 999.0)) - car["y"]) <= MODEL_MATCH_DY:
            return True
    return False


def side_candidate_plausible(car, ego_speed, model_lead_list, blindspot_active):
    """Reject common stationary divider/guardrail echoes while preserving real side traffic."""
    lateral = abs(float(car["y"]))
    if lateral < SIDE_MIN_LATERAL or lateral > SIDE_MAX_LATERAL:
        return False

    source = car.get("source", "")
    if source == "commaLeadLeft" and car["y"] >= 0:
        return False
    if source == "commaLeadRight" and car["y"] <= 0:
        return False

    # BSM is strong side-vehicle evidence on supported Hyundai/Kia cars.
    if blindspot_active:
        return True

    # A stationary divider/guardrail seen from a moving car has vRel ~= -vEgo.
    # Preserve slow/stopped real vehicles if the vision model also supports the object.
    if ego_speed >= SIDE_MOVING_EGO_MPS:
        estimated_world_speed = ego_speed + float(car.get("v", 0.0))
        if abs(estimated_world_speed) < SIDE_MIN_WORLD_SPEED_MPS and not model_matches_car(car, model_lead_list):
            return False
    return True


def _same_side_object(previous, current):
    if previous is None or current is None:
        return False
    return abs(previous["x"] - current["x"]) <= SIDE_MATCH_DX and \
           abs(previous["y"] - current["y"]) <= SIDE_MATCH_DY


def stabilize_side_cars(raw_cars, side_tracks, now, ego_speed, model_lead_list,
                        left_blindspot=False, right_blindspot=False):
    """Debounce side leads: 2 hits to appear, 0.55 s dropout hold to avoid flicker."""
    output = [c for c in raw_cars if c.get("source") not in ("commaLeadLeft", "commaLeadRight")]
    by_source = {c.get("source"): c for c in raw_cars}

    for source, blindspot in (("commaLeadLeft", left_blindspot), ("commaLeadRight", right_blindspot)):
        state = side_tracks.setdefault(source, {"car": None, "hits": 0, "visible": False, "last_seen": 0.0})
        candidate = by_source.get(source)
        if candidate is not None and not side_candidate_plausible(candidate, ego_speed, model_lead_list, blindspot):
            candidate = None

        if candidate is not None:
            if _same_side_object(state["car"], candidate):
                state["hits"] += 1
            else:
                state["hits"] = 1
            state["car"] = candidate
            state["last_seen"] = now
            if state["hits"] >= SIDE_CONFIRM_FRAMES or blindspot:
                state["visible"] = True
        elif state["visible"] and state["car"] is not None and now - state["last_seen"] <= SIDE_HOLD_SEC:
            pass
        else:
            state["car"] = None
            state["hits"] = 0
            state["visible"] = False

        if state["visible"] and state["car"] is not None:
            output.append(state["car"])

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
        if "liveTracks" in SERVICE_LIST:
            services.append("liveTracks")
    except ImportError:
        pass

    sm = messaging.SubMaster(services)
    sent = 0
    print("CarrotVision bridge v2.14 side-stable started; 15 Hz TX + side debounce + stationary-echo filter", flush=True)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    discovery = open_discovery_socket()
    clients = {}
    side_tracks = {}

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
        raw_radar_points = radar_points(sm["liveTracks"]) if "liveTracks" in services and fresh(sm, "liveTracks", now) else []

        raw_cars = comma_ui_cars(sm["radarState"]) if fresh(sm, "radarState", now) else []
        cars = stabilize_side_cars(raw_cars, side_tracks, now, float(cs.vEgo), raw_model_leads,
                                   left_blindspot, right_blindspot)

        lanes = [dict(p=float(prob), pts=points(line))
                 for line, prob in zip(model.laneLines, model.laneLineProbs)]

        packet = dict(version=2, fresh=True, time=int(time.time()*1000),
                      trafficState=traffic_state(sm, now),
                      speed=float(cs.vEgo)*3.6, steering=float(cs.steeringAngleDeg),
                      enabled=active_state(sm, now),
                      brakeLights=optional_bool(cs, "brakeLights"),
                      leftBlinker=bool(cs.leftBlinker), rightBlinker=bool(cs.rightBlinker),
                      leftBlindspot=left_blindspot, rightBlindspot=right_blindspot,
                      cars=cars, modelLeads=raw_model_leads, radarPoints=raw_radar_points,
                      path=points(model.position), lanes=lanes)
        try:
            data = json.dumps(packet, allow_nan=False, separators=(",", ":")).encode()
            active_targets = targets(clients)
            for target in active_targets:
                sock.sendto(data, target)
            sent += 1
            if sent == 1:
                print("Sending fresh Comma perception data at %.0f Hz to %s" % (TX_HZ, active_targets), flush=True)
        except (OSError, ValueError) as error:
            print(error, flush=True)
        time.sleep(TX_INTERVAL)


if __name__ == "__main__":
    main()
