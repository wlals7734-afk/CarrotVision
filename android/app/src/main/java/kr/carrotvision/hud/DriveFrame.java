package kr.carrotvision.hud;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class DriveFrame {
  static final class Car { float x, y, v, p; String source, type; }
  static final class Line { float probability; List<float[]> points = new ArrayList<>(); }
  long time;
  float speed, steering;
  boolean brakeLights, enabled, leftBlinker, rightBlinker, leftBlindspot, rightBlindspot;
  List<Car> cars = new ArrayList<>();
  List<Line> lanes = new ArrayList<>();
  List<float[]> path = new ArrayList<>();

  static DriveFrame parse(JSONObject j) {
    if (j.optInt("version") != 2 || !j.optBoolean("fresh")) throw new IllegalArgumentException("Bridge v2 with fresh data required");
    DriveFrame f = new DriveFrame();
    f.time = j.optLong("time", System.currentTimeMillis());
    f.speed = (float) j.optDouble("speed", 0);
    f.steering = (float) j.optDouble("steering", 0);
    f.enabled = j.optBoolean("enabled");
    f.brakeLights = j.optBoolean("brakeLights", false);
    f.leftBlinker = j.optBoolean("leftBlinker");
    f.rightBlinker = j.optBoolean("rightBlinker");
    f.leftBlindspot = j.optBoolean("leftBlindspot");
    f.rightBlindspot = j.optBoolean("rightBlindspot");
    readPoints(j.optJSONArray("path"), f.path);
    JSONArray lanes = j.optJSONArray("lanes");
    if (lanes != null) for (int i=0; i<lanes.length(); i++) {
      JSONObject o = lanes.optJSONObject(i); if (o == null) continue;
      Line l = new Line(); l.probability = (float)o.optDouble("p", 0); readPoints(o.optJSONArray("pts"), l.points); f.lanes.add(l);
    }
    JSONArray cars = j.optJSONArray("cars");
    if (cars != null) for (int i=0; i<cars.length(); i++) {
      JSONObject o = cars.optJSONObject(i); if (o == null) continue;
      Car c = new Car(); c.x=(float)o.optDouble("x"); c.y=(float)o.optDouble("y"); c.v=(float)o.optDouble("v"); c.p=(float)o.optDouble("p"); c.source=o.optString("source"); c.type=o.optString("type","car"); f.cars.add(c);
    }
    return f;
  }

  private static void readPoints(JSONArray a, List<float[]> out) {
    if (a == null) return;
    for (int i=0; i<a.length(); i++) { JSONArray p=a.optJSONArray(i); if (p != null && p.length() >= 2) out.add(new float[]{(float)p.optDouble(0),(float)p.optDouble(1)}); }
  }
}
