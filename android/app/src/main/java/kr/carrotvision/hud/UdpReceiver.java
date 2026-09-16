package kr.carrotvision.hud;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class UdpReceiver extends Thread {
  private static final int DISCOVERY_PORT = 8856;
  private static final long DISCOVERY_INTERVAL_MS = 2000L;
  private static final byte[] DISCOVERY = "CV_DISCOVER_V1".getBytes(StandardCharsets.US_ASCII);

  private final int port;
  private final Consumer<DriveFrame> callback;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicReference<DriveFrame> latestFrame = new AtomicReference<>();
  private final AtomicBoolean deliveryScheduled = new AtomicBoolean(false);
  private volatile boolean running = true;
  private DatagramSocket socket;
  private long lastDiscovery;

  private final Runnable deliverLatest = new Runnable() {
    @Override public void run() {
      DriveFrame frame = latestFrame.getAndSet(null);
      if (running && frame != null) callback.accept(frame);
      deliveryScheduled.set(false);
      if (running && latestFrame.get() != null) scheduleDelivery();
    }
  };

  UdpReceiver(int port, Consumer<DriveFrame> callback) {
    this.port = port;
    this.callback = callback;
    setName("CarrotUdp");
  }

  @Override public void run() {
    byte[] buffer = new byte[65507];
    try {
      socket = new DatagramSocket(port);
      socket.setBroadcast(true);
      socket.setSoTimeout(500);
      sendDiscovery();
      while (running) {
        try {
          DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
          socket.receive(packet);
          String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
          latestFrame.set(DriveFrame.parse(new JSONObject(json)));
          scheduleDelivery();
        } catch (SocketTimeoutException ignored) { }
          catch (org.json.JSONException | IllegalArgumentException invalid) {
            android.util.Log.w("CarrotVision", "Invalid or stale packet");
          }
        if (System.currentTimeMillis() - lastDiscovery >= DISCOVERY_INTERVAL_MS) sendDiscovery();
      }
    } catch (Exception error) {
      if (running) android.util.Log.e("CarrotVision", "UDP receiver stopped", error);
    }
  }

  private void sendDiscovery() {
    if (socket == null || socket.isClosed()) return;
    lastDiscovery = System.currentTimeMillis();
    try {
      // Legacy/global broadcast first.
      DatagramPacket global = new DatagramPacket(
          DISCOVERY, DISCOVERY.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
      socket.send(global);
    } catch (Exception ignored) { }

    // Android hotspots can block client-to-client broadcast. Probe the local /24 with
    // tiny unicast discovery packets so a running Comma bridge can answer directly.
    String prefix = privateIpv4Prefix();
    if (prefix == null) return;
    for (int i = 1; i <= 254 && running; i++) {
      try {
        InetAddress address = InetAddress.getByName(prefix + i);
        DatagramPacket packet = new DatagramPacket(DISCOVERY, DISCOVERY.length, address, DISCOVERY_PORT);
        socket.send(packet);
      } catch (Exception ignored) { }
    }
  }

  private static String privateIpv4Prefix() {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        NetworkInterface network = interfaces.nextElement();
        if (!network.isUp() || network.isLoopback()) continue;
        Enumeration<InetAddress> addresses = network.getInetAddresses();
        while (addresses.hasMoreElements()) {
          InetAddress address = addresses.nextElement();
          byte[] raw = address.getAddress();
          if (raw.length != 4 || address.isLoopbackAddress()) continue;
          int a = raw[0] & 0xff, b = raw[1] & 0xff, c = raw[2] & 0xff;
          boolean privateRange = a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
          if (privateRange) return a + "." + b + "." + c + ".";
        }
      }
    } catch (Exception ignored) { }
    return null;
  }

  private void scheduleDelivery() {
    if (deliveryScheduled.compareAndSet(false, true)) {
      main.post(deliverLatest);
    }
  }

  void close() {
    running = false;
    latestFrame.set(null);
    main.removeCallbacks(deliverLatest);
    if (socket != null) socket.close();
  }
}
