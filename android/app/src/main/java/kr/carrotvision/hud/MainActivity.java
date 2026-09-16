package kr.carrotvision.hud;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;

public class MainActivity extends Activity {
  private UdpReceiver receiver;
  private WifiManager.MulticastLock multicastLock;
  private WifiManager.WifiLock wifiLock;
  private WebView visionWeb;
  private String commaHost;
  private boolean webReady;
  private long lastJsFrame;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    visionWeb = new WebView(this);
    visionWeb.setBackgroundColor(Color.BLACK);
    WebSettings ws = visionWeb.getSettings();
    ws.setJavaScriptEnabled(true);
    ws.setDomStorageEnabled(true);
    ws.setMediaPlaybackRequiresUserGesture(false);
    ws.setLoadsImagesAutomatically(true);
    ws.setAllowFileAccess(true);
    ws.setAllowContentAccess(true);
    ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    if (android.os.Build.VERSION.SDK_INT >= 16) {
      ws.setAllowFileAccessFromFileURLs(true);
      ws.setAllowUniversalAccessFromFileURLs(true);
    }
    visionWeb.setWebChromeClient(new WebChromeClient());
    visionWeb.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView view, String url) {
        webReady = true;
        if (commaHost != null) connectVisionHost(commaHost);
      }
    });
    root.addView(visionWeb, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    setContentView(root);
    visionWeb.loadUrl("file:///android_asset/vision.html");

    WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    multicastLock = wifi.createMulticastLock("carrot-vision-3");
    multicastLock.setReferenceCounted(false);
    multicastLock.acquire();

    try {
      wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "carrot-vision-3-high-perf");
      wifiLock.setReferenceCounted(false);
      wifiLock.acquire();
    } catch (Exception ignored) { }

    receiver = new UdpReceiver(8855, frame -> {
      long now = SystemClock.elapsedRealtime();
      if (!webReady || now - lastJsFrame < 28L) return;
      lastJsFrame = now;
      String payload = frameToJson(frame).toString();
      visionWeb.evaluateJavascript(
          "window.CarrotVision&&window.CarrotVision.updateState(" + payload + ")", null);
    }, host -> {
      commaHost = host;
      if (webReady) connectVisionHost(host);
    });
    receiver.start();
  }

  private void connectVisionHost(String host) {
    if (visionWeb == null || host == null || host.isEmpty()) return;
    visionWeb.evaluateJavascript(
        "window.CarrotVision&&window.CarrotVision.connectHost('" + host.replace("'", "") + "')", null);
  }

  private static JSONArray pointsJson(List<float[]> points, int maxPoints) throws JSONException {
    JSONArray out = new JSONArray();
    if (points == null || points.isEmpty()) return out;
    int step = Math.max(1, (int)Math.ceil(points.size() / (double)Math.max(1, maxPoints)));
    for (int i = 0; i < points.size(); i += step) {
      float[] q = points.get(i);
      if (q == null || q.length < 2 || !Float.isFinite(q[0]) || !Float.isFinite(q[1])) continue;
      JSONArray p = new JSONArray();
      p.put(q[0]); p.put(q[1]); out.put(p);
    }
    return out;
  }

  private static JSONObject frameToJson(DriveFrame f) {
    JSONObject j = new JSONObject();
    try {
      j.put("time", f.time);
      j.put("speed", f.speed);
      j.put("steering", f.steering);
      j.put("enabled", f.enabled);
      j.put("trafficState", f.trafficState);
      j.put("brakeLights", f.brakeLights);
      j.put("leftBlinker", f.leftBlinker);
      j.put("rightBlinker", f.rightBlinker);
      j.put("leftBlindspot", f.leftBlindspot);
      j.put("rightBlindspot", f.rightBlindspot);
      j.put("path", pointsJson(f.path, 54));

      JSONArray lanes = new JSONArray();
      for (DriveFrame.Line line : f.lanes) {
        JSONObject o = new JSONObject();
        o.put("p", line.probability);
        o.put("pts", pointsJson(line.points, 44));
        lanes.put(o);
      }
      j.put("lanes", lanes);

      JSONArray cars = new JSONArray();
      for (DriveFrame.Car car : f.cars) {
        JSONObject o = new JSONObject();
        o.put("x", car.x); o.put("y", car.y); o.put("v", car.v); o.put("p", car.p);
        o.put("source", car.source == null ? "" : car.source);
        o.put("type", car.type == null ? "car" : car.type);
        cars.put(o);
      }
      j.put("cars", cars);

      JSONArray radar = new JSONArray();
      for (DriveFrame.RadarPoint point : f.radarPoints) {
        JSONObject o = new JSONObject();
        o.put("x", point.x); o.put("y", point.y); o.put("v", point.v);
        o.put("measured", point.measured);
        o.put("source", point.source == null ? "" : point.source);
        radar.put(o);
      }
      j.put("radarPoints", radar);
      j.put("radarCount", f.radarPoints.size());
    } catch (Exception ignored) { }
    return j;
  }

  @Override protected void onDestroy() {
    if (receiver != null) receiver.close();
    if (visionWeb != null) {
      visionWeb.evaluateJavascript("window.CarrotVision&&window.CarrotVision.stop()", null);
      visionWeb.destroy();
    }
    if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    super.onDestroy();
  }
}
