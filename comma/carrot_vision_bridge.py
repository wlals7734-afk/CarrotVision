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
    # pycapnp raises AttributeError for fields absent from the installed schema.
    try:
        return bool(getattr(obj, field))
    except AttributeError:
        return None

def active_state(sm, now):
    # Prefer actual lateral activity when supplied by this fork.
    if fresh(sm, "controlsState", now):
        lateral = optional_bool(sm["controlsState"], "lateralActive")
        if lateral is not None:
            return lateral
    # Newer schemas moved overall engagement to selfdriveState.
    for service in ("selfdriveState", "controlsState"):
        if fresh(sm, service, now):
            enabled = optional_bool(sm[service], "enabled")
            if enabled is not None:
                return enabled
    return False

def main():
    services = ["carState", "modelV2", "controlsState"]
    try:
        from cereal.services import SERVICE_LIST
        if "selfdriveState" in SERVICE_LIST:
            services.append("selfdriveState")
    except ImportError:
        pass
    sm = messaging.SubMaster(services)
    sent = 0
    print("CarrotVision bridge v2.1 started; waiting for fresh vehicle/model data", flush=True)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    target = (os.environ.get("CARROT_VISION_HOST", "255.255.255.255"),
              int(os.environ.get("CARROT_VISION_PORT", "8855")))
    while True:
        sm.update(50)
        now = time.monotonic()
        # Never keep sending cached values with a newly generated timestamp.
        if not (fresh(sm, "carState", now) and fresh(sm, "modelV2", now)):
            time.sleep(0.05)
            continue
        cs, model = sm["carState"], sm["modelV2"]
        cars = []
        # leadsV3 entries can be the same lead at different prediction horizons.
        # Display only the present-time primary vision lead, not three fake cars.
        if len(model.leadsV3):
            lead = model.leadsV3[0]
            if lead.prob >= 0.5 and len(lead.x) and len(lead.y):
                x, y = float(lead.x[0]), float(lead.y[0])
                if math.isfinite(x) and math.isfinite(y) and 1 <= x <= 150:
                    cars.append(dict(x=x, y=y, p=float(lead.prob), source="vision"))
        lanes = [dict(p=float(prob), pts=points(line))
                 for line, prob in zip(model.laneLines, model.laneLineProbs)]
        packet = dict(version=2, fresh=True, time=int(time.time()*1000),
                      speed=float(cs.vEgo)*3.6, steering=float(cs.steeringAngleDeg),
                      enabled=active_state(sm, now),
                      leftBlinker=bool(cs.leftBlinker), rightBlinker=bool(cs.rightBlinker),
                      leftBlindspot=bool(getattr(cs,"leftBlindspot",False)),
                      rightBlindspot=bool(getattr(cs,"rightBlindspot",False)),
                      cars=cars, path=points(model.position), lanes=lanes)
        try:
            sock.sendto(json.dumps(packet, allow_nan=False, separators=(",",":")).encode(),target)
            sent += 1
            if sent == 1:
                print("Sending fresh data to %s:%s" % target, flush=True)
        except (OSError, ValueError) as error:
            print(error, flush=True)
        time.sleep(0.05)

if __name__ == "__main__":
    main()
