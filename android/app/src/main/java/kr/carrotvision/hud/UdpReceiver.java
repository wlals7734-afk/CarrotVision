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
  private static final long HEARTBEAT_INTERVAL_MS = 4000L;
  private static final long LOST_AFTER_MS = 2500L;
  private static final long BROADCAST_RETRY_MS = 1500L;
  private static final long SUBNET_SCAN_RETRY_MS = 5000L;
  private static final byte[] DISCOVERY = "CV_DISCOVER_V1".getBytes(StandardCharsets.US_ASCII);

  private final int port;
  private final Consumer<DriveFrame> callback;
  private final Consumer<String> hostCallback;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicReference<DriveFrame> latestFrame = new AtomicReference<>();
  private final AtomicBoolean deliveryScheduled = new AtomicBoolean(false);
  private volatile boolean running = true;
  private DatagramSocket socket;
  private InetAddress lastSender;
  private String lastHostNotified;
  private long lastPacketAt;
  private long lastHeartbeatAt;
  private long lastBroadcastAt;
  private long lastSubnetScanAt;

  private final Runnable deliverLatest = new Runnable() {
    @Override public void run() {
      DriveFrame frame = latestFrame.getAndSet(null);
      if (running && frame != null) callback.accept(frame);
      deliveryScheduled.set(false);
      if (running && latestFrame.get() != null) scheduleDelivery();
    }
  };

  UdpReceiver(int port, Consumer<DriveFrame> callback) {
    this(port, callback, null);
  }

  UdpReceiver(int port, Consumer<DriveFrame> callback, Consumer<String> hostCallback) {
    this.port = port;
    this.callback = callback;
    this.hostCallback = hostCallback;
    setName("CarrotUdp");
  }

  @Override public void run() {
    byte[] buffer = new byte[65507];
    try {
      socket = new DatagramSocket(port);
      socket.setBroadcast(true);
      socket.setSoTimeout(250);
      try { socket.setReceiveBufferSize(1024 * 1024); } catch (Exception ignored) { }
      aggressiveDiscovery();

      while (running) {
        try {
          DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
          socket.receive(packet);
          String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
          DriveFrame parsed = DriveFrame.parse(new JSONObject(json));
          lastSender = packet.getAddress();
          lastPacketAt = System.currentTimeMillis();
          notifyHost(lastSender);
          latestFrame.set(parsed);
          scheduleDelivery();
        } catch (SocketTimeoutException ignored) { }
          catch (org.json.JSONException | IllegalArgumentException invalid) {
            android.util.Log.w("CarrotVision", "Invalid or stale packet");
          }

        long now = System.currentTimeMillis();
        boolean connected = lastPacketAt > 0 && now - lastPacketAt < LOST_AFTER_MS;
        if (connected) {
          if (lastSender != null && now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
            sendDiscovery(lastSender);
            lastHeartbeatAt = now;
          }
        } else {
          if (now - lastBroadcastAt >= BROADCAST_RETRY_MS) {
            sendGlobalBroadcast();
            lastBroadcastAt = now;
          }
          if (now - lastSubnetScanAt >= SUBNET_SCAN_RETRY_MS) {
            scanLocalSubnet();
            lastSubnetScanAt = now;
          }
        }
      }
    } catch (Exception error) {
      if (running) android.util.Log.e("CarrotVision", "UDP receiver stopped", error);
    }
  }

  private void notifyHost(InetAddress sender) {
    if (sender == null || hostCallback == null) return;
    final String host = sender.getHostAddress();
    if (host == null || host.equals(lastHostNotified)) return;
    lastHostNotified = host;
    main.post(() -> { if (running) hostCallback.accept(host); });
  }

  private void aggressiveDiscovery() {
    long now = System.currentTimeMillis();
    sendGlobalBroadcast();
    scanLocalSubnet();
    lastBroadcastAt = now;
    lastSubnetScanAt = now;
  }

  private void sendGlobalBroadcast() {
    try {
      sendDiscovery(InetAddress.getByName("255.255.255.255"));
    } catch (Exception ignored) { }
  }

  private void sendDiscovery(InetAddress address) {
    if (socket == null || socket.isClosed() || address == null) return;
    try {
      DatagramPacket packet = new DatagramPacket(DISCOVERY, DISCOVERY.length, address, DISCOVERY_PORT);
      socket.send(packet);
    } catch (Exception ignored) { }
  }

  private void scanLocalSubnet() {
    String prefix = privateIpv4Prefix();
    if (prefix == null) return;
    for (int i = 1; i <= 254 && running; i++) {
      try { sendDiscovery(InetAddress.getByName(prefix + i)); }
      catch (Exception ignored) { }
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
