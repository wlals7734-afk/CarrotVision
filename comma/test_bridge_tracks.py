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
radar_cars = namespace["radar_cars"]
merge_cars = namespace["merge_cars"]

def track(x, y, ident=-1, status=True, v=8.0, vlat=0.0, model_prob=0.0):
    return Obj(dRel=x, yRel=y, radarTrackId=ident, status=status,
               vLeadK=v, vLat=vlat, modelProb=model_prob)

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

    def test_side_coordinates_and_duplicates(self):
        left, right = track(20, 3.5, 7), track(25, -3.5, 8)
        cars = radar_cars(Obj(leadsLeft=[left], leadsRight=[right], leadsCenter=[left]))
        self.assertEqual([(c["x"], c["y"]) for c in cars], [(20, -3.5), (25, 3.5)])
        self.assertTrue(all(c["type"] == "car" for c in cars))

    def test_stationary_clutter_is_filtered(self):
        clutter = [
            track(18, 2.5, v=0.0, model_prob=0.0),
            track(40, -3.0, v=0.2, model_prob=0.1),
            track(70, 0.5, v=1.0, model_prob=0.3),
        ]
        self.assertEqual(radar_cars(Obj(leadsLeft=clutter)), [])

    def test_stationary_vehicle_can_survive_with_model_support(self):
        stopped_car = track(25, 0.2, v=0.0, model_prob=0.9)
        cars = radar_cars(Obj(leadsCenter=[stopped_car]))
        self.assertEqual(len(cars), 1)

    def test_old_schema_fallback_and_invalid_tracks(self):
        self.assertEqual(radar_cars(Obj()), [])
        self.assertEqual(len(radar_cars(Obj(leadOne=track(30, 0)))), 1)
        bad = [track(float("nan"), 0), track(20, float("inf")),
               track(30, 0, status=False), track(-10, 2), track(151, 0), track(30, 9.5)]
        self.assertEqual(radar_cars(Obj(leadsLeft=bad)), [])

    def test_vision_merge_keeps_adjacent_vehicle(self):
        radar = radar_cars(Obj(leadsLeft=[track(30, 3.5)], leadsCenter=[track(30, 0)]))
        vision = [{"x": 31, "y": .1, "p": .9, "type": "car"},
                  {"x": 60, "y": 0, "p": .9, "type": "car"}]
        self.assertEqual(len(merge_cars(vision, radar)), 3)

    def test_stale_radar_is_not_fresh(self):
        state = Obj(recv_time={"radarState": 10}, seen={"radarState": True}, valid={"radarState": True})
        self.assertTrue(namespace["fresh"](state, "radarState", 10.2))
        self.assertFalse(namespace["fresh"](state, "radarState", 11))

if __name__ == "__main__":
    unittest.main()
