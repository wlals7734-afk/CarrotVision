package kr.carrotvision.hud;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Direct read-only Carrot compact-state client.
 *
 * The class name is kept for binary/source compatibility with MainActivity,
 * but v2.16 no longer receives bridge UDP packets. It discovers the comma on
 * the local /24, opens /ws/compact_state on port 7000, decodes the same compact
 * wire format used by Carrot Web, and renders only Comma-provided perception.
 */
final class UdpReceiver extends Thread {
  private static final int COMMA_WEB_PORT = 7000;
  private static final int CONNECT_TIMEOUT_MS = 180;
  private static final int SOCKET_TIMEOUT_MS = 1800;
  private static final long FRESH_MS = 1200L;
  private static final long DISCOVERY_RETRY_MS = 3000L;
  private static final float MODEL_CAR_MIN_PROB = 0.50f;
  private static final float RADAR_GROUP_MIN_MODEL_PROB = 0.12f;
  private static final float RADAR_GROUP_MIN_SCORE = 0.12f;
  private static final String SERVICES =
      "carState,controlsState,longitudinalPlan,modelV2,lateralPlan,radarState,liveTracks";
  private static final byte[] MAGIC = new byte[]{0x43,0x56,0x53,0x31}; // CVS1
  private static final byte[] BATCH_MAGIC = new byte[]{0x43,0x56,0x42,0x31}; // CVB1

  private final Consumer<DriveFrame> callback;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicReference<DriveFrame> latestFrame = new AtomicReference<>();
  private final AtomicBoolean deliveryScheduled = new AtomicBoolean(false);
  private final SecureRandom random = new SecureRandom();
  private volatile boolean running = true;
  private volatile Socket socket;

  private CarStateData carState;
  private ControlsData controlsState;
  private LongPlanData longPlan;
  private ModelData modelV2;
  private LateralData lateralPlan;
  private RadarData radarState;
  private TracksData liveTracks;
  private long carAt, controlsAt, longAt, modelAt, lateralAt, radarAt, tracksAt;

  private final Runnable deliverLatest = new Runnable() {
    @Override public void run() {
      DriveFrame frame = latestFrame.getAndSet(null);
      if (running && frame != null) callback.accept(frame);
      deliveryScheduled.set(false);
      if (running && latestFrame.get() != null) scheduleDelivery();
    }
  };

  UdpReceiver(int ignoredPort, Consumer<DriveFrame> callback) {
    this.callback = callback;
    setName("CarrotCompactWS");
  }

  @Override public void run() {
    while (running) {
      WsConnection connection = null;
      try {
        connection = discoverAndConnect();
        if (connection == null) {
          sleepQuiet(DISCOVERY_RETRY_MS);
          continue;
        }
        socket = connection.socket;
        android.util.Log.i("CarrotVision", "compact_state connected: " + connection.host);
        resetState();
        readLoop(connection);
      } catch (InterruptedException interrupted) {
        if (!running) break;
      } catch (Exception error) {
        if (running) android.util.Log.w("CarrotVision", "compact_state disconnected: " + error.getMessage());
      } finally {
        if (connection != null) connection.close();
        socket = null;
        clearPendingFrame();
      }
      if (running) sleepQuiet(DISCOVERY_RETRY_MS);
    }
  }

  private void readLoop(WsConnection ws) throws Exception {
    ByteArrayOutputStream fragmented = null;
    int fragmentedOpcode = 0;
    while (running && !ws.socket.isClosed()) {
      WsFrame frame;
      try {
        frame = readWsFrame(ws.in);
      } catch (SocketTimeoutException timeout) {
        continue;
      }
      if (frame == null) throw new EOFException("websocket closed");
      if (frame.opcode == 0x8) throw new EOFException("websocket close frame");
      if (frame.opcode == 0x9) {
        writeClientFrame(ws.out, 0xA, frame.payload);
        continue;
      }
      if (frame.opcode == 0xA) continue;

      if (frame.opcode == 0x2 || frame.opcode == 0x1) {
        if (frame.fin) {
          if (frame.opcode == 0x2) decodeCompactMessage(frame.payload);
        } else {
          fragmented = new ByteArrayOutputStream(Math.max(256, frame.payload.length * 2));
          fragmented.write(frame.payload);
          fragmentedOpcode = frame.opcode;
        }
      } else if (frame.opcode == 0x0 && fragmented != null) {
        fragmented.write(frame.payload);
        if (frame.fin) {
          if (fragmentedOpcode == 0x2) decodeCompactMessage(fragmented.toByteArray());
          fragmented = null;
          fragmentedOpcode = 0;
        }
      }
    }
  }

  private void decodeCompactMessage(byte[] bytes) {
    try {
      if (startsWith(bytes, MAGIC)) {
        decodeSingle(bytes, 0, bytes.length);
      } else if (startsWith(bytes, BATCH_MAGIC)) {
        Cursor c = new Cursor(bytes, 4, bytes.length);
        int count = c.u16();
        for (int i = 0; i < count; i++) {
          long rawLength = c.u32();
          if (rawLength < 8 || rawLength > Integer.MAX_VALUE) throw new IllegalArgumentException("bad compact frame length");
          int length = (int) rawLength;
          int start = c.pos;
          c.ensure(length);
          decodeSingle(bytes, start, length);
          c.pos += length;
        }
      }
    } catch (RuntimeException invalid) {
      android.util.Log.w("CarrotVision", "compact decode skipped: " + invalid.getMessage());
    }
  }

  private void decodeSingle(byte[] bytes, int start, int length) {
    if (length < 8 || !matchesAt(bytes, start, MAGIC)) throw new IllegalArgumentException("invalid CVS1 frame");
    Cursor c = new Cursor(bytes, start + 4, start + length);
    int serviceId = c.u8();
    c.u8();
    c.u16();
    long now = System.currentTimeMillis();
    switch (serviceId) {
      case 1: carState = parseCarState(c); carAt = now; break;
      case 2: controlsState = parseControlsState(c); controlsAt = now; break;
      case 8: longPlan = parseLongPlan(c); longAt = now; break;
      case 9: modelV2 = parseModel(c); modelAt = now; break;
      case 12: lateralPlan = parseLateral(c); lateralAt = now; break;
      case 13: radarState = parseRadar(c); radarAt = now; break;
      case 18: liveTracks = parseTracks(c); tracksAt = now; break;
      default: return;
    }
    DriveFrame built = buildFrame(now);
    if (built != null) {
      latestFrame.set(built);
      scheduleDelivery();
    }
  }

  private CarStateData parseCarState(Cursor c) {
    CarStateData s = new CarStateData();
    s.vEgo = c.f32();
    c.f32(); c.f32(); c.f32();
    s.steeringAngleDeg = c.f32();
    c.bool(); c.i16(); c.i16(); c.i16(); c.f32();
    s.brakeLights = c.bool();
    s.leftBlindspot = c.bool();
    s.rightBlindspot = c.bool();
    c.i16(); c.i16(); c.u8();
    s.leftBlinker = c.bool();
    s.rightBlinker = c.bool();
    return s;
  }

  private ControlsData parseControlsState(Cursor c) {
    ControlsData s = new ControlsData();
    s.enabled = c.bool();
    return s;
  }

  private LongPlanData parseLongPlan(Cursor c) {
    LongPlanData s = new LongPlanData();
    c.f32List(); c.f32List(); c.f32List();
    c.f32(); c.f32(); c.i32(); c.i32();
    s.trafficState = c.i32();
    return s;
  }

  private ModelData parseModel(Cursor c) {
    ModelData m = new ModelData();
    c.u32(); c.u32();
    m.position = readXyz(c);
    readVelocity(c);
    int laneCount = c.u8();
    for (int i = 0; i < laneCount; i++) m.laneLines.add(readXyz(c));
    m.laneLineProbs = c.f32List();
    int edgeCount = c.u8();
    for (int i = 0; i < edgeCount; i++) readXyz(c);
    c.f32List();
    int leadCount = c.u8();
    for (int i = 0; i < leadCount; i++) {
      ModelLeadData lead = new ModelLeadData();
      lead.prob = c.f32();
      lead.x = c.u16CmList();
      lead.y = c.f32List();
      lead.v = c.f32List();
      m.leads.add(lead);
    }
    return m;
  }

  private LateralData parseLateral(Cursor c) {
    LateralData l = new LateralData();
    l.useLaneLines = c.bool();
    c.text();
    l.position = readXyz(c);
    return l;
  }

  private RadarData parseRadar(Cursor c) {
    RadarData r = new RadarData();
    r.leadOne = readRadarLead(c);
    r.leadTwo = readRadarLead(c);
    r.leadRight = readRadarLead(c);
    r.leadLeft = readRadarLead(c);
    r.leadsLeft = readRadarLeadList(c);
    r.leadsCenter = readRadarLeadList(c);
    r.leadsRight = readRadarLeadList(c);
    return r;
  }

  private TracksData parseTracks(Cursor c) {
    TracksData t = new TracksData();
    int count = c.u8();
    for (int i = 0; i < count; i++) {
      RadarPointData p = new RadarPointData();
      p.trackId = c.u32();
      p.dRel = c.f32();
      p.yRel = c.f32();
      p.vRel = c.f32();
      p.measured = c.bool();
      p.source = radarSourceName(c.u8());
      t.points.add(p);
    }
    return t;
  }

  private static XyzData readXyz(Cursor c) {
    XyzData xyz = new XyzData();
    xyz.x = c.u16CmList();
    xyz.y = c.i16MmList();
    xyz.z = c.i16MmList();
    return xyz;
  }

  private static void readVelocity(Cursor c) { c.i16CmList(); }

  private static RadarLeadData readRadarLead(Cursor c) {
    RadarLeadData r = new RadarLeadData();
    r.dRel = c.f32(); r.yRel = c.f32(); r.vRel = c.f32(); r.aRel = c.f32();
    r.vLead = c.f32(); r.aLead = c.f32(); r.dPath = c.f32(); r.vLat = c.f32();
    r.vLeadK = c.f32(); r.aLeadK = c.f32();
    r.fcw = c.bool(); r.status = c.bool();
    r.aLeadTau = c.f32(); r.modelProb = c.f32(); r.radar = c.bool();
    r.radarTrackId = c.i32(); r.jLead = c.f32(); r.score = c.f32();
    return r;
  }

  private static List<RadarLeadData> readRadarLeadList(Cursor c) {
    int count = c.u8();
    List<RadarLeadData> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) out.add(readRadarLead(c));
    return out;
  }

  private DriveFrame buildFrame(long now) {
    if (carState == null || modelV2 == null) return null;
    if (now - carAt > FRESH_MS || now - modelAt > FRESH_MS) return null;

    DriveFrame f = new DriveFrame();
    f.time = System.currentTimeMillis();
    f.speed = finite(carState.vEgo) ? carState.vEgo : 0f;
    f.steering = finite(carState.steeringAngleDeg) ? carState.steeringAngleDeg : 0f;
    f.brakeLights = carState.brakeLights;
    f.leftBlinker = carState.leftBlinker;
    f.rightBlinker = carState.rightBlinker;
    f.leftBlindspot = carState.leftBlindspot;
    f.rightBlindspot = carState.rightBlindspot;
    f.enabled = controlsState != null && now - controlsAt <= FRESH_MS && controlsState.enabled;
    if (longPlan != null && now - longAt <= FRESH_MS && (longPlan.trafficState == 1 || longPlan.trafficState == 2)) {
      f.trafficState = longPlan.trafficState;
    }

    XyzData path = (lateralPlan != null && now - lateralAt <= FRESH_MS && lateralPlan.position != null)
        ? lateralPlan.position : modelV2.position;
    appendPoints(path, f.path);

    for (int i = 0; i < modelV2.laneLines.size(); i++) {
      XyzData lane = modelV2.laneLines.get(i);
      DriveFrame.Line line = new DriveFrame.Line();
      line.probability = i < modelV2.laneLineProbs.length ? clamp01(modelV2.laneLineProbs[i]) : 0f;
      appendPoints(lane, line.points);
      f.lanes.add(line);
    }

    for (int i = 0; i < modelV2.leads.size(); i++) {
      ModelLeadData src = modelV2.leads.get(i);
      if (src.x.length == 0 || src.y.length == 0) continue;
      float x = src.x[0], y = src.y[0], v = src.v.length > 0 ? src.v[0] : 0f;
      if (!validWorld(x, y) || !finite(v) || !finite(src.prob)) continue;
      DriveFrame.ModelLead lead = new DriveFrame.ModelLead();
      lead.x = x; lead.y = y; lead.v = v; lead.p = clamp01(src.prob); lead.source = "modelV2-" + i;
      f.modelLeads.add(lead);
    }

    if (liveTracks != null && now - tracksAt <= FRESH_MS) {
      for (RadarPointData src : liveTracks.points) {
        float x = src.dRel, y = -src.yRel;
        if (!validWorldLoose(x, y) || !finite(src.vRel)) continue;
        DriveFrame.RadarPoint p = new DriveFrame.RadarPoint();
        p.x = x; p.y = y; p.v = src.vRel; p.measured = src.measured; p.source = src.source;
        f.radarPoints.add(p);
      }
    }

    if (radarState != null && now - radarAt <= FRESH_MS) {
      addRadarCar(f, radarState.leadOne, "radarState.leadOne", true);
      addRadarCar(f, radarState.leadTwo, "radarState.leadTwo", true);
      addRadarCar(f, radarState.leadLeft, "radarState.leadLeft", true);
      addRadarCar(f, radarState.leadRight, "radarState.leadRight", true);
      addRadarGroup(f, radarState.leadsLeft, "radarState.leadsLeft");
      addRadarGroup(f, radarState.leadsCenter, "radarState.leadsCenter");
      addRadarGroup(f, radarState.leadsRight, "radarState.leadsRight");
    }

    for (DriveFrame.ModelLead lead : f.modelLeads) {
      if (lead.p < MODEL_CAR_MIN_PROB) continue;
      addCarDedup(f, lead.x, lead.y, lead.v - f.speed, lead.p, lead.source, "car");
    }
    return f;
  }

  private static void addRadarGroup(DriveFrame f, List<RadarLeadData> leads, String source) {
    if (leads == null) return;
    for (RadarLeadData lead : leads) {
      boolean trusted = lead != null && lead.status &&
          (lead.modelProb >= RADAR_GROUP_MIN_MODEL_PROB || lead.score >= RADAR_GROUP_MIN_SCORE);
      if (trusted) addRadarCar(f, lead, source, false);
    }
  }

  private static void addRadarCar(DriveFrame f, RadarLeadData lead, String source, boolean selected) {
    if (lead == null || !lead.status) return;
    float x = lead.dRel, y = -lead.yRel, v = lead.vRel;
    if (!validWorldLoose(x, y) || !finite(v)) return;
    float p = lead.modelProb > 0f ? clamp01(lead.modelProb) : (lead.score > 0f ? clamp01(lead.score) : (selected ? 1f : 0.5f));
    addCarDedup(f, x, y, v, p, source, "car");
  }

  private static void addCarDedup(DriveFrame f, float x, float y, float v, float p, String source, String type) {
    if (!validWorldLoose(x, y)) return;
    for (DriveFrame.Car existing : f.cars) {
      if (Math.abs(existing.x - x) < 4.5f && Math.abs(existing.y - y) < 1.4f) {
        if (p > existing.p) {
          existing.x = x; existing.y = y; existing.v = v; existing.p = p; existing.source = source; existing.type = type;
        }
        return;
      }
    }
    DriveFrame.Car c = new DriveFrame.Car();
    c.x = x; c.y = y; c.v = v; c.p = clamp01(p); c.source = source; c.type = type;
    f.cars.add(c);
  }

  private static void appendPoints(XyzData xyz, List<float[]> out) {
    if (xyz == null) return;
    int n = Math.min(xyz.x.length, xyz.y.length);
    n = Math.min(n, 33);
    for (int i = 0; i < n; i++) {
      float x = xyz.x[i], y = xyz.y[i];
      if (finite(x) && finite(y) && x >= 0f && x <= 150f) out.add(new float[]{x, y});
    }
  }

  private WsConnection discoverAndConnect() throws InterruptedException {
    List<String> hosts = candidateHosts();
    if (hosts.isEmpty()) return null;
    ExecutorService pool = Executors.newFixedThreadPool(Math.min(32, hosts.size()));
    CompletionService<WsConnection> completion = new ExecutorCompletionService<>(pool);
    List<Future<WsConnection>> futures = new ArrayList<>(hosts.size());
    for (String host : hosts) futures.add(completion.submit(() -> tryConnect(host)));
    long deadline = System.currentTimeMillis() + 4200L;
    WsConnection winner = null;
    try {
      for (int i = 0; i < hosts.size() && running; i++) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) break;
        Future<WsConnection> future = completion.poll(remaining, TimeUnit.MILLISECONDS);
        if (future == null) break;
        try { winner = future.get(); } catch (Exception ignored) { }
        if (winner != null) break;
      }
    } finally {
      for (Future<WsConnection> f : futures) f.cancel(true);
      pool.shutdownNow();
    }
    return winner;
  }

  private List<String> candidateHosts() {
    Set<String> hosts = new LinkedHashSet<>();
    Collections.addAll(hosts, "192.168.43.1", "172.20.10.1", "172.20.10.2", "10.0.0.1", "10.0.0.2");
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
          int a = raw[0] & 0xff, b = raw[1] & 0xff, c = raw[2] & 0xff, self = raw[3] & 0xff;
          boolean privateRange = a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
          if (!privateRange) continue;
          String prefix = a + "." + b + "." + c + ".";
          for (int i = 1; i <= 254; i++) if (i != self) hosts.add(prefix + i);
        }
      }
    } catch (Exception ignored) { }
    return new ArrayList<>(hosts);
  }

  private WsConnection tryConnect(String host) {
    if (!running) return null;
    Socket candidate = new Socket();
    try {
      candidate.connect(new InetSocketAddress(host, COMMA_WEB_PORT), CONNECT_TIMEOUT_MS);
      candidate.setTcpNoDelay(true);
      candidate.setSoTimeout(SOCKET_TIMEOUT_MS);
      InputStream in = candidate.getInputStream();
      OutputStream out = candidate.getOutputStream();
      String key = randomWebSocketKey();
      String path = "/ws/compact_state?services=" + SERVICES;
      String request = "GET " + path + " HTTP/1.1\r\n" +
          "Host: " + host + ":" + COMMA_WEB_PORT + "\r\n" +
          "Upgrade: websocket\r\n" +
          "Connection: Upgrade\r\n" +
          "Sec-WebSocket-Key: " + key + "\r\n" +
          "Sec-WebSocket-Version: 13\r\n" +
          "Origin: http://" + host + ":" + COMMA_WEB_PORT + "\r\n\r\n";
      out.write(request.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      String header = readHttpHeader(in);
      if (!header.startsWith("HTTP/1.1 101") && !header.startsWith("HTTP/1.0 101")) throw new IllegalStateException("not websocket");
      String expected = websocketAccept(key);
      if (!header.toLowerCase().contains("sec-websocket-accept: " + expected.toLowerCase())) {
        throw new IllegalStateException("bad websocket accept");
      }
      return new WsConnection(host, candidate, in, out);
    } catch (Exception failed) {
      try { candidate.close(); } catch (Exception ignored) { }
      return null;
    }
  }

  private String randomWebSocketKey() {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static String websocketAccept(String key) throws Exception {
    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
    byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
    return Base64.getEncoder().encodeToString(digest);
  }

  private static String readHttpHeader(InputStream in) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int matched = 0;
    while (out.size() < 16384) {
      int value = in.read();
      if (value < 0) throw new EOFException("handshake EOF");
      out.write(value);
      if ((matched == 0 || matched == 2) && value == '\r') matched++;
      else if ((matched == 1 || matched == 3) && value == '\n') matched++;
      else matched = value == '\r' ? 1 : 0;
      if (matched == 4) break;
    }
    return out.toString("US-ASCII");
  }

  private WsFrame readWsFrame(InputStream in) throws Exception {
    int b0 = in.read();
    if (b0 < 0) return null;
    int b1 = readByte(in);
    boolean fin = (b0 & 0x80) != 0;
    int opcode = b0 & 0x0f;
    boolean masked = (b1 & 0x80) != 0;
    long length = b1 & 0x7f;
    if (length == 126) length = ((long)readByte(in) << 8) | readByte(in);
    else if (length == 127) {
      length = 0;
      for (int i = 0; i < 8; i++) length = (length << 8) | readByte(in);
    }
    if (length < 0 || length > 4L * 1024L * 1024L) throw new IllegalArgumentException("websocket frame too large");
    byte[] mask = masked ? readExact(in, 4) : null;
    byte[] payload = readExact(in, (int)length);
    if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
    return new WsFrame(fin, opcode, payload);
  }

  private void writeClientFrame(OutputStream out, int opcode, byte[] payload) throws Exception {
    int length = payload == null ? 0 : payload.length;
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    header.write(0x80 | (opcode & 0x0f));
    if (length <= 125) header.write(0x80 | length);
    else if (length <= 65535) {
      header.write(0x80 | 126); header.write((length >>> 8) & 0xff); header.write(length & 0xff);
    } else throw new IllegalArgumentException("control payload too large");
    byte[] mask = new byte[4]; random.nextBytes(mask);
    header.write(mask);
    out.write(header.toByteArray());
    if (length > 0) {
      byte[] masked = payload.clone();
      for (int i = 0; i < masked.length; i++) masked[i] ^= mask[i & 3];
      out.write(masked);
    }
    out.flush();
  }

  private static int readByte(InputStream in) throws Exception {
    int value = in.read();
    if (value < 0) throw new EOFException("websocket EOF");
    return value;
  }

  private static byte[] readExact(InputStream in, int length) throws Exception {
    byte[] out = new byte[length];
    int offset = 0;
    while (offset < length) {
      int read = in.read(out, offset, length - offset);
      if (read < 0) throw new EOFException("websocket payload EOF");
      offset += read;
    }
    return out;
  }

  private void scheduleDelivery() {
    if (deliveryScheduled.compareAndSet(false, true)) main.post(deliverLatest);
  }

  private void clearPendingFrame() {
    latestFrame.set(null);
    main.removeCallbacks(deliverLatest);
    deliveryScheduled.set(false);
  }

  private void resetState() {
    carState = null; controlsState = null; longPlan = null; modelV2 = null; lateralPlan = null; radarState = null; liveTracks = null;
    carAt = controlsAt = longAt = modelAt = lateralAt = radarAt = tracksAt = 0L;
  }

  void close() {
    running = false;
    interrupt();
    clearPendingFrame();
    Socket s = socket;
    if (s != null) try { s.close(); } catch (Exception ignored) { }
  }

  private static void sleepQuiet(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
  }

  private static boolean startsWith(byte[] data, byte[] prefix) { return matchesAt(data, 0, prefix); }
  private static boolean matchesAt(byte[] data, int offset, byte[] prefix) {
    if (data == null || offset < 0 || offset + prefix.length > data.length) return false;
    for (int i = 0; i < prefix.length; i++) if (data[offset + i] != prefix[i]) return false;
    return true;
  }
  private static boolean finite(float v) { return !Float.isNaN(v) && !Float.isInfinite(v); }
  private static boolean validWorld(float x, float y) { return finite(x) && finite(y) && x >= 0f && x <= 150f && Math.abs(y) <= 10f; }
  private static boolean validWorldLoose(float x, float y) { return finite(x) && finite(y) && x >= 0f && x <= 150f && Math.abs(y) <= 9f; }
  private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
  private static String radarSourceName(int value) {
    switch (value) {
      case 0: return "frontRadar";
      case 1: return "scc";
      case 2: return "corner235";
      case 3: return "corner180";
      case 4: return "corner430";
      default: return "unknown";
    }
  }

  private static final class WsConnection {
    final String host; final Socket socket; final InputStream in; final OutputStream out;
    WsConnection(String host, Socket socket, InputStream in, OutputStream out) { this.host=host; this.socket=socket; this.in=in; this.out=out; }
    void close() { try { socket.close(); } catch (Exception ignored) { } }
  }
  private static final class WsFrame {
    final boolean fin; final int opcode; final byte[] payload;
    WsFrame(boolean fin, int opcode, byte[] payload) { this.fin=fin; this.opcode=opcode; this.payload=payload; }
  }
  private static final class CarStateData { float vEgo, steeringAngleDeg; boolean brakeLights,leftBlindspot,rightBlindspot,leftBlinker,rightBlinker; }
  private static final class ControlsData { boolean enabled; }
  private static final class LongPlanData { int trafficState; }
  private static final class XyzData { float[] x=new float[0], y=new float[0], z=new float[0]; }
  private static final class ModelLeadData { float prob; float[] x=new float[0], y=new float[0], v=new float[0]; }
  private static final class ModelData { XyzData position; final List<XyzData> laneLines=new ArrayList<>(); float[] laneLineProbs=new float[0]; final List<ModelLeadData> leads=new ArrayList<>(); }
  private static final class LateralData { boolean useLaneLines; XyzData position; }
  private static final class RadarLeadData {
    float dRel,yRel,vRel,aRel,vLead,aLead,dPath,vLat,vLeadK,aLeadK,aLeadTau,modelProb,jLead,score;
    boolean fcw,status,radar; int radarTrackId;
  }
  private static final class RadarData {
    RadarLeadData leadOne,leadTwo,leadRight,leadLeft;
    List<RadarLeadData> leadsLeft=new ArrayList<>(),leadsCenter=new ArrayList<>(),leadsRight=new ArrayList<>();
  }
  private static final class RadarPointData { long trackId; float dRel,yRel,vRel; boolean measured; String source; }
  private static final class TracksData { final List<RadarPointData> points=new ArrayList<>(); }

  private static final class Cursor {
    final byte[] data; final int end; int pos;
    Cursor(byte[] data, int start, int end) { this.data=data; this.pos=start; this.end=Math.min(end,data.length); }
    void ensure(int n) { if (n < 0 || pos + n > end) throw new IllegalArgumentException("compact frame truncated"); }
    int u8(){ ensure(1); return data[pos++] & 0xff; }
    int i8(){ ensure(1); return data[pos++]; }
    int u16(){ ensure(2); int v=(data[pos]&255)|((data[pos+1]&255)<<8); pos+=2; return v; }
    int i16(){ int v=u16(); return v>=0x8000?v-0x10000:v; }
    int i32(){ long v=u32(); return (int)v; }
    long u32(){ ensure(4); long v=(data[pos]&255L)|((data[pos+1]&255L)<<8)|((data[pos+2]&255L)<<16)|((data[pos+3]&255L)<<24); pos+=4; return v; }
    long u64(){ ensure(8); long v=0; for(int i=0;i<8;i++) v|=(data[pos+i]&255L)<<(8*i); pos+=8; return v; }
    float f32(){ return Float.intBitsToFloat((int)u32()); }
    double f64(){ return Double.longBitsToDouble(u64()); }
    boolean bool(){ return u8()!=0; }
    String text(){ int n=u16(); ensure(n); String s=new String(data,pos,n,StandardCharsets.UTF_8); pos+=n; return s; }
    float[] f32List(){ int n=u16(); float[] a=new float[n]; for(int i=0;i<n;i++)a[i]=f32(); return a; }
    float[] u16CmList(){ int n=u16(); float[] a=new float[n]; for(int i=0;i<n;i++)a[i]=u16()/100f; return a; }
    float[] i16CmList(){ int n=u16(); float[] a=new float[n]; for(int i=0;i<n;i++)a[i]=i16()/100f; return a; }
    float[] i16MmList(){ int n=u16(); float[] a=new float[n]; for(int i=0;i<n;i++)a[i]=i16()/1000f; return a; }
  }
}
