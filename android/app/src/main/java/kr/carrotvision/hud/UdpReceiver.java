package kr.carrotvision.hud;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class UdpReceiver extends Thread {
  private final int port;
  private final Consumer<DriveFrame> callback;
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile boolean running = true;
  private DatagramSocket socket;

  UdpReceiver(int port, Consumer<DriveFrame> callback) { this.port = port; this.callback = callback; setName("CarrotUdp"); }

  @Override public void run() {
    byte[] buffer = new byte[65507];
    try {
      socket = new DatagramSocket(port);
      socket.setBroadcast(true);
      socket.setSoTimeout(1500);
      while (running) {
        try {
          DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
          socket.receive(packet);
          String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
          DriveFrame frame = DriveFrame.parse(new JSONObject(json));
          main.post(() -> callback.accept(frame));
        } catch (SocketTimeoutException ignored) { }
          catch (org.json.JSONException | IllegalArgumentException invalid) { android.util.Log.w("CarrotVision", "Invalid or stale packet"); }
      }
    } catch (Exception error) { android.util.Log.e("CarrotVision", "UDP receiver stopped", error); }
  }

  void close() { running = false; if (socket != null) socket.close(); }
}
