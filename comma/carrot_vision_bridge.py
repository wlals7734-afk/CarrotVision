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
    """Convert one Comma-selected lead to HUD coordinates.

    This intentionally does not re-classify, score, or reject a valid selected
    lead based on speed/model probability. If Comma says status=True, CarrotVision
    mirrors it. Only malformed/out-of-display-range values are dropped.
    """
    if lead is None or not optional_field(lead, "status", False):
        return None
    try:
        x = float(optional_field(lead, "dRel", 0.0))
        # radarState yRel is positive LEFT; HUD lateral is positive RIGHT.
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
    """Mirror only the lead slots selected by Comma/CarrotPilot itself.

    Raw leadsLeft/leadsRight/leadsCenter target lists are intentionally ignored.
    leadOne/leadTwo/leadLeft/leadRight are copied as-is when their status is true.
    CarrotVision performs no vehicle-confidence or motion filtering here.
    """
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


def traffic_state(sm, now):
    if fresh(sm, "longitudinalPlan", now):
        state = optional_field(sm["longitudinalPlan"], "trafficState", 0)
        if state in (1, 2):
            return int(state)
    return 0


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
    print("CarrotVision bridge v2.8 started; Comma-selected leads + raw model lane confidence", flush=True)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    target = (os.environ.get("CARROT_VISION_HOST", "255.255.255.255"),
              int(os.environ.get("CARROT_VISION_PORT", "8855")))

    while True:
        sm.update(50)
        now = time.monotonic()
        if not (fresh(sm, "carState", now) and fresh(sm, "modelV2", now)):
            time.sleep(0.05)
            continue

        cs, model = sm["carState"], sm["modelV2"]
        left_blindspot = bool(getattr(cs, "leftBlindspot", False))
        right_blindspot = bool(getattr(cs, "rightBlindspot", False))

        cars = comma_ui_cars(sm["radarState"]) if fresh(sm, "radarState", now) else []

        # Preserve modelV2 laneLineProbs exactly. Android uses the probability as
        # visual opacity so low-confidence lane perception remains visible instead
        # of being hidden by an app-side threshold.
        lanes = [dict(p=float(prob), pts=points(line))
                 for line, prob in zip(model.laneLines, model.laneLineProbs)]

        packet = dict(version=2, fresh=True, time=int(time.time()*1000),
                      trafficState=traffic_state(sm, now),
                      speed=float(cs.vEgo)*3.6, steering=float(cs.steeringAngleDeg),
                      enabled=active_state(sm, now),
                      brakeLights=optional_bool(cs, "brakeLights"),
                      leftBlinker=bool(cs.leftBlinker), rightBlinker=bool(cs.rightBlinker),
                      leftBlindspot=left_blindspot, rightBlindspot=right_blindspot,
                      cars=cars, path=points(model.position), lanes=lanes)
        try:
            sock.sendto(json.dumps(packet, allow_nan=False, separators=(",", ":")).encode(), target)
            sent += 1
            if sent == 1:
                print("Sending fresh Comma perception data to %s:%s" % target, flush=True)
        except (OSError, ValueError) as error:
            print(error, flush=True)
        time.sleep(0.05)


if __name__ == "__main__":
    main()
