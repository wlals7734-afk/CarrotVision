package kr.carrotvision.hud;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DriveFrame {
  static final class Car { float x, y, v, p; String source, type; }
  static final class ModelLead { float x, y, v, p; String source; }
  static final class RadarPoint { float x, y, v; boolean measured; String source; }
  static final class Line { float probability; List<float[]> points = new ArrayList<>(); }
  long time;
  int trafficState;
  float speed, steering;
  boolean brakeLights, enabled, leftBlinker, rightBlinker, leftBlindspot, rightBlindspot;
  List<Car> cars = new ArrayList<>();
  List<ModelLead> modelLeads = new ArrayList<>();
  List<RadarPoint> radarPoints = new ArrayList<>();
  List<Line> lanes = new ArrayList<>();
  List<float[]> path = new ArrayList<>();

  static DriveFrame parse(JSONObject j) {
    if (j.optInt("version") != 2 || !j.optBoolean("fresh")) throw new IllegalArgumentException("Bridge v2 with fresh data required");
    DriveFrame f = new DriveFrame();
    f.time = j.optLong("time", System.currentTimeMillis());
    int traffic = j.optInt("trafficState", 0);
    f.trafficState = traffic == 1 || traffic == 2 ? traffic : 0;
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
      Line l = new Line();
      l.probability = (float)o.optDouble("p", 0);
      readPoints(o.optJSONArray("pts"), l.points);
      f.lanes.add(l);
    }

    JSONArray cars = j.optJSONArray("cars");
    if (cars != null) for (int i=0; i<cars.length(); i++) {
      JSONObject o = cars.optJSONObject(i); if (o == null) continue;
      String type = o.optString("type", "").trim().toLowerCase(Locale.US);
      if (!("car".equals(type) || "truck".equals(type) || "bus".equals(type))) continue;
      Car c = new Car();
      c.x=(float)o.optDouble("x");
      c.y=(float)o.optDouble("y");
      c.v=(float)o.optDouble("v");
      c.p=(float)o.optDouble("p",1.0);
      c.source=o.optString("source", "");
      c.type=type;
      if (!finiteWorld(c.x,c.y) || c.p < 0f) continue;
      f.cars.add(c);
    }

    JSONArray modelLeads = j.optJSONArray("modelLeads");
    if (modelLeads != null) for (int i=0; i<modelLeads.length(); i++) {
      JSONObject o=modelLeads.optJSONObject(i); if(o==null)continue;
      ModelLead m=new ModelLead();
      m.x=(float)o.optDouble("x");m.y=(float)o.optDouble("y");m.v=(float)o.optDouble("v");
      m.p=(float)o.optDouble("p");m.source=o.optString("source","");
      if(!finiteWorld(m.x,m.y)||!Float.isFinite(m.p))continue;
      m.p=Math.max(0f,Math.min(1f,m.p));
      f.modelLeads.add(m);
    }

    JSONArray radar = j.optJSONArray("radarPoints");
    if (radar != null) for (int i=0; i<radar.length(); i++) {
      JSONObject o=radar.optJSONObject(i); if(o==null)continue;
      RadarPoint r=new RadarPoint();
      r.x=(float)o.optDouble("x");r.y=(float)o.optDouble("y");r.v=(float)o.optDouble("v");
      r.measured=o.optBoolean("measured",false);r.source=o.optString("source","");
      if(!Float.isFinite(r.x)||!Float.isFinite(r.y)||!Float.isFinite(r.v)||r.x<0f||r.x>150f||Math.abs(r.y)>12f)continue;
      f.radarPoints.add(r);
    }
    return f;
  }

  private static boolean finiteWorld(float x,float y){
    return Float.isFinite(x)&&Float.isFinite(y)&&x>=0f&&x<=150f&&Math.abs(y)<=10f;
  }

  private static void readPoints(JSONArray a, List<float[]> out) {
    if (a == null) return;
    for (int i=0; i<a.length(); i++) {
      JSONArray p=a.optJSONArray(i);
      if (p != null && p.length() >= 2) out.add(new float[]{(float)p.optDouble(0),(float)p.optDouble(1)});
    }
  }
}
