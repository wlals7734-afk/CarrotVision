#!/usr/bin/env python3
"""Read-only bridge; does not publish controls or change driving settings."""
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
    """Mirror the vehicles CarrotPilot's main road overlay treats as lead cars.

    The CarrotPilot web road overlay draws its primary vehicle box from leadOne.
    It draws leadTwo only when it is a radar lead and is more than 3 m behind
    leadOne in range. Raw leadsLeft/leadsRight/leadsCenter are intentionally not
    forwarded here because those are radar target lists and can contain roadside
    clutter that should not become vehicle sprites in CarrotVision.
    """
    if radar_state is None:
        return []

    result = []
    lead_one = optional_field(radar_state, "leadOne")
    lead_two = optional_field(radar_state, "leadTwo")

    first = _lead_to_car(lead_one, "commaLeadOne")
    if first is not None:
        result.append(first)

    lead_one_dist = float(optional_field(lead_one, "dRel", 0.0) or 0.0) if lead_one is not None else 0.0
    lead_two_valid = (
        lead_two is not None
        and bool(optional_field(lead_two, "status", False))
        and bool(optional_field(lead_two, "radar", False))
    )
    if lead_two_valid:
        try:
            lead_two_dist = float(optional_field(lead_two, "dRel", 0.0))
        except (TypeError, ValueError, OverflowError):
            lead_two_dist = 0.0
        if math.isfinite(lead_two_dist) and lead_two_dist > lead_one_dist + 3.0:
            second = _lead_to_car(lead_two, "commaLeadTwo")
            if second is not None:
                result.append(second)

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
    print("CarrotVision bridge v2.6 started; mirroring CarrotPilot leadOne/leadTwo", flush=True)
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

        # Vehicle sprites now come only from the same fused lead objects used by
        # CarrotPilot's main road overlay. No raw side/center radar target lists.
        cars = comma_ui_cars(sm["radarState"]) if fresh(sm, "radarState", now) else []

        lanes = [dict(p=float(prob), pts=points(line))
                 for line, prob in zip(model.laneLines, model.laneLineProbs)]
        packet = dict(version=2, fresh=True, time=int(time.time()*1000),
                      trafficState=traffic_state(sm, now),
                      speed=float(cs.vEgo)*3.6, steering=float(cs.steeringAngleDeg),
                      enabled=active_state(sm, now),
                      brakeLights=optional_bool(cs, "brakeLights"),
                      leftBlinker=bool(cs.leftBlinker), rightBlinker=bool(cs.rightBlinker),
                      leftBlindspot=bool(getattr(cs,"leftBlindspot",False)),
                      rightBlindspot=bool(getattr(cs,"rightBlindspot",False)),
                      cars=cars, path=points(model.position), lanes=lanes)
        try:
            sock.sendto(json.dumps(packet, allow_nan=False, separators=(",",":")).encode(), target)
            sent += 1
            if sent == 1:
                print("Sending fresh data to %s:%s" % target, flush=True)
        except (OSError, ValueError) as error:
            print(error, flush=True)
        time.sleep(0.05)

if __name__ == "__main__":
    main()
