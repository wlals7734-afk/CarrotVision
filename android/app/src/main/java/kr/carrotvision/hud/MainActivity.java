package kr.carrotvision.hud;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

public class MainActivity extends Activity {
  private UdpReceiver receiver;
  private WifiManager.MulticastLock multicastLock;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    HudView hud = new HudView(this);
    setContentView(hud);
    WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    multicastLock = wifi.createMulticastLock("carrot-vision");
    multicastLock.setReferenceCounted(false);
    multicastLock.acquire();
    receiver = new UdpReceiver(8855, hud::setFrame);
    receiver.start();
  }

  @Override protected void onDestroy() {
    if (receiver != null) receiver.close();
    if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    super.onDestroy();
  }
}

