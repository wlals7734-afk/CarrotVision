"""Host-side bridge tests without requiring comma's cereal runtime."""
import ast
import math
import pathlib
import unittest
from types import SimpleNamespace as Obj

tree = ast.parse(pathlib.Path(__file__).with_name("carrot_vision_bridge.py").read_text())
functions = ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef)], type_ignores=[])
namespace = {"math": math}
exec(compile(functions, "bridge", "exec"), namespace)
comma_ui_cars = namespace["comma_ui_cars"]

def track(x, y, status=True, radar=True, vrel=0.0):
    return Obj(dRel=x, yRel=y, status=status, radar=radar, vRel=vrel)

class BridgeTracksTest(unittest.TestCase):
    def test_traffic_state(self):
        class State:
            recv_time = {"longitudinalPlan": 10}
            seen = {"longitudinalPlan": True}
            valid = {"longitudinalPlan": True}
            plan = Obj(trafficState=0)
            def __getitem__(self, key):
                return self.plan
        sm = State()
        for value, expected in ((0,0),(1,1),(2,2),(3,0),(1001,0)):
            sm.plan.trafficState = value
            self.assertEqual(namespace["traffic_state"](sm,10.1),expected)
        sm.plan.trafficState = 1
        self.assertEqual(namespace["traffic_state"](sm,11),0)
        sm.plan = Obj()
        self.assertEqual(namespace["traffic_state"](sm,10.1),0)

    def test_primary_lead_is_forwarded(self):
        cars = comma_ui_cars(Obj(leadOne=track(28, 1.4), leadTwo=None,
                                 leadsLeft=[track(10, 4)], leadsRight=[track(12, -4)]))
        self.assertEqual(len(cars), 1)
        self.assertEqual(cars[0]["source"], "commaLeadOne")
        self.assertEqual((cars[0]["x"], cars[0]["y"]), (28.0, -1.4))
        self.assertEqual(cars[0]["type"], "car")

    def test_raw_radar_lists_are_ignored(self):
        state = Obj(leadOne=track(0, 0, status=False), leadTwo=track(0, 0, status=False),
                    leadsLeft=[track(20, 3)], leadsRight=[track(25, -3)], leadsCenter=[track(30, 0)])
        self.assertEqual(comma_ui_cars(state), [])

    def test_second_lead_matches_carrotpilot_rule(self):
        state = Obj(leadOne=track(20, 0), leadTwo=track(30, .2, radar=True))
        cars = comma_ui_cars(state)
        self.assertEqual([c["source"] for c in cars], ["commaLeadOne", "commaLeadTwo"])

        too_close = Obj(leadOne=track(20, 0), leadTwo=track(22, .2, radar=True))
        self.assertEqual(len(comma_ui_cars(too_close)), 1)

        vision_only_second = Obj(leadOne=track(20, 0), leadTwo=track(40, .2, radar=False))
        self.assertEqual(len(comma_ui_cars(vision_only_second)), 1)

    def test_invalid_leads_are_rejected(self):
        cases = [
            Obj(leadOne=track(float("nan"), 0), leadTwo=None),
            Obj(leadOne=track(20, float("inf")), leadTwo=None),
            Obj(leadOne=track(0.5, 0), leadTwo=None),
            Obj(leadOne=track(151, 0), leadTwo=None),
            Obj(leadOne=track(20, 9.0), leadTwo=None),
            Obj(leadOne=track(20, 0, status=False), leadTwo=None),
        ]
        for state in cases:
            self.assertEqual(comma_ui_cars(state), [])

    def test_stale_radar_is_not_fresh(self):
        state = Obj(recv_time={"radarState": 10}, seen={"radarState": True}, valid={"radarState": True})
        self.assertTrue(namespace["fresh"](state, "radarState", 10.2))
        self.assertFalse(namespace["fresh"](state, "radarState", 11))

if __name__ == "__main__":
    unittest.main()
