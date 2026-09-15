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
    """Front vehicles mirrored from the fused leads used by CarrotPilot UI."""
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


def _side_track_to_car(track, source, blindspot_active):
    """Accept a side radar target only when it looks like a real vehicle.

    Raw radar side lists can contain road furniture. CarrotPilot's own radar UI
    distinguishes moving targets using vLeadK/vLat; stationary clutter normally
    has near-zero absolute speed and weak/no model association. OEM blind-spot
    state is allowed to keep a nearby slow vehicle visible at low road speed.
    """
    if track is None or not optional_field(track, "status", False):
        return None
    if not bool(optional_field(track, "radar", False)):
        return None

    try:
        x = float(optional_field(track, "dRel", 0.0))
        y = -float(optional_field(track, "yRel", 0.0))
        v_rel = float(optional_field(track, "vRel", 0.0))
        v_long = float(optional_field(track, "vLeadK", optional_field(track, "vLead", 0.0)))
        v_lat = float(optional_field(track, "vLat", 0.0))
        model_prob = float(optional_field(track, "modelProb", 0.0))
    except (TypeError, ValueError, OverflowError):
        return None

    if not all(math.isfinite(v) for v in (x, y, v_rel, v_long, v_lat, model_prob)):
        return None
    # Adjacent-lane / blind-spot geometry only. Do not reuse center radar clutter.
    if not (1.0 <= x <= 90.0 and 1.2 <= abs(y) <= 6.5):
        return None

    moving_vehicle = math.hypot(v_long, v_lat) > 3.0
    model_vehicle = model_prob >= 0.50
    oem_confirmed_nearby = blindspot_active and x <= 45.0
    if not (moving_vehicle or model_vehicle or oem_confirmed_nearby):
        return None

    return dict(x=x, y=y, v=v_rel, p=max(0.70, min(1.0, model_prob if model_prob > 0 else 1.0)),
                source=source, type="car")


def side_ui_cars(radar_state, left_blindspot=False, right_blindspot=False):
    """Filtered real side vehicles from leadsLeft/leadsRight only."""
    if radar_state is None:
        return []

    result = []
    groups = (
        ("leadsLeft", "commaSideLeft", left_blindspot),
        ("leadsRight", "commaSideRight", right_blindspot),
    )
    for field, source, blindspot_active in groups:
        candidates = []
        for track in optional_field(radar_state, field, ()):
            car = _side_track_to_car(track, source, blindspot_active)
            if car is not None:
                candidates.append(car)
        # Keep at most the two nearest genuine vehicles per side.
        candidates.sort(key=lambda c: c["x"])
        result.extend(candidates[:2])
    return result


def merge_vehicle_lists(front, side):
    """Merge front and side lists while suppressing duplicate fused tracks."""
    result = list(front)
    for car in side:
        duplicate = any(abs(car["x"] - other["x"]) < 2.5 and abs(car["y"] - other["y"]) < 0.8
                        for other in result)
        if not duplicate:
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
    print("CarrotVision bridge v2.7 started; front + filtered side vehicles + OEM BSM", flush=True)
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

        cars = []
        if fresh(sm, "radarState", now):
            radar_state = sm["radarState"]
            front = comma_ui_cars(radar_state)
            side = side_ui_cars(radar_state, left_blindspot, right_blindspot)
            cars = merge_vehicle_lists(front, side)

        lanes = [dict(p=float(prob), pts=points(line))
                 for line, prob in zip(model.laneLines, model.laneLineProbs)]
        packet = dict(version=2, fresh=True, time=int(time.time()*1000),
                      trafficState=traffic_state(sm, now),
                      speed=float(cs.vEgo)*3.6, steering=float(cs.steeringAngleDeg),
                      enabled=active_state(sm, now),
                      brakeLights=optional_bool(cs, "brakeLights"),
                      leftBlinker=bool(cs.leftBlinker), rightBlinker=bool(cs.rightBlinker),
                      leftBlindspot=left_blindspot,
                      rightBlindspot=right_blindspot,
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
