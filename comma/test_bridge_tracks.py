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


def track(x, y, status=True, radar=True, vrel=0.0, model_prob=0.0):
    return Obj(dRel=x, yRel=y, status=status, radar=radar, vRel=vrel, modelProb=model_prob)


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

    def test_all_comma_selected_leads_are_forwarded(self):
        state = Obj(
            leadOne=track(28, 0.2),
            leadTwo=track(45, -0.3, radar=False, model_prob=0.1),
            leadLeft=track(18, 3.4, model_prob=0.0),
            leadRight=track(22, -3.6, model_prob=0.0),
        )
        cars = comma_ui_cars(state)
        self.assertEqual([c["source"] for c in cars],
                         ["commaLeadOne", "commaLeadTwo", "commaLeadLeft", "commaLeadRight"])
        self.assertEqual([round(c["y"],1) for c in cars], [-0.2,0.3,-3.4,3.6])
        self.assertTrue(all(c["p"] == 1.0 and c["type"] == "car" for c in cars))

    def test_raw_radar_lists_do_not_create_cars(self):
        state = Obj(
            leadOne=track(0,0,status=False), leadTwo=track(0,0,status=False),
            leadLeft=track(0,0,status=False), leadRight=track(0,0,status=False),
            leadsLeft=[track(15,3.5)], leadsRight=[track(16,-3.5)], leadsCenter=[track(20,0)],
        )
        self.assertEqual(comma_ui_cars(state), [])

    def test_no_app_side_confidence_or_motion_filter(self):
        state = Obj(leadOne=track(25,0,radar=False,model_prob=0.0,vrel=0.0),
                    leadTwo=None, leadLeft=None, leadRight=None)
        cars = comma_ui_cars(state)
        self.assertEqual(len(cars), 1)
        self.assertEqual(cars[0]["source"], "commaLeadOne")

    def test_invalid_selected_leads_are_rejected_for_render_safety(self):
        cases = [
            Obj(leadOne=track(float("nan"),0), leadTwo=None, leadLeft=None, leadRight=None),
            Obj(leadOne=track(20,float("inf")), leadTwo=None, leadLeft=None, leadRight=None),
            Obj(leadOne=track(0.5,0), leadTwo=None, leadLeft=None, leadRight=None),
            Obj(leadOne=track(151,0), leadTwo=None, leadLeft=None, leadRight=None),
            Obj(leadOne=track(20,9.0), leadTwo=None, leadLeft=None, leadRight=None),
            Obj(leadOne=track(20,0,status=False), leadTwo=None, leadLeft=None, leadRight=None),
        ]
        for state in cases:
            self.assertEqual(comma_ui_cars(state), [])

    def test_stale_radar_is_not_fresh(self):
        state = Obj(recv_time={"radarState":10}, seen={"radarState":True}, valid={"radarState":True})
        self.assertTrue(namespace["fresh"](state,"radarState",10.2))
        self.assertFalse(namespace["fresh"](state,"radarState",11))


if __name__ == "__main__":
    unittest.main()
