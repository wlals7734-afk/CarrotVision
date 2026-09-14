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

def track(x, y, ident=-1, status=True):
    return Obj(dRel=x, yRel=y, radarTrackId=ident, status=status)

class BridgeTracksTest(unittest.TestCase):
    def test_side_coordinates_and_duplicates(self):
        left, right = track(20, 3.5, 7), track(25, -3.5, 8)
        cars = radar_cars(Obj(leadsLeft=[left], leadsRight=[right], leadsCenter=[left]))
        self.assertEqual([(c["x"], c["y"]) for c in cars], [(20, -3.5), (25, 3.5)])

    def test_old_schema_fallback_and_invalid_tracks(self):
        self.assertEqual(radar_cars(Obj()), [])
        self.assertEqual(len(radar_cars(Obj(leadOne=track(30, 0)))), 1)
        bad = [track(float("nan"), 0), track(20, float("inf")),
               track(30, 0, status=False), track(-10, 2), track(151, 0)]
        self.assertEqual(radar_cars(Obj(leadsLeft=bad)), [])

    def test_vision_merge_keeps_adjacent_vehicle(self):
        radar = radar_cars(Obj(leadsLeft=[track(30, 3.5)], leadsCenter=[track(30, 0)]))
        vision = [{"x": 31, "y": .1, "p": .9}, {"x": 60, "y": 0, "p": .9}]
        self.assertEqual(len(merge_cars(vision, radar)), 3)

    def test_stale_radar_is_not_fresh(self):
        state = Obj(recv_time={"radarState": 10}, seen={"radarState": True}, valid={"radarState": True})
        self.assertTrue(namespace["fresh"](state, "radarState", 10.2))
        self.assertFalse(namespace["fresh"](state, "radarState", 11))

if __name__ == "__main__":
    unittest.main()
