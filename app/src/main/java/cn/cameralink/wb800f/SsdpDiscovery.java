package cn.cameralink.wb800f;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Receives both search replies and unsolicited camera announcements throughout approval. */
public final class SsdpDiscovery implements AutoCloseable {
    private final DatagramSocket search, announcements;
    private final List<InetSocketAddress> destinations;
    private final String agent;
    private final Consumer<String> log;
    private final LinkedHashSet<String> locations = new LinkedHashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger received = new AtomicInteger();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "WB800F-search"); t.setDaemon(true); return t;
    });
    private int attempt;

    public SsdpDiscovery(DatagramSocket search, DatagramSocket announcements,
                         List<InetSocketAddress> destinations, String agent, Consumer<String> log) throws SocketException {
        this.search = search; this.announcements = announcements;
        this.destinations = new ArrayList<>(destinations); this.agent = agent; this.log = log;
        search.setSoTimeout(500);
        if (announcements != null) announcements.setSoTimeout(500);
    }
    public void start() {
        receiver(search, "reply");
        if (announcements != null) receiver(announcements, "announce");
        send();
        timer.scheduleWithFixedDelay(this::send, 2, 2, TimeUnit.SECONDS);
    }
    private void receiver(DatagramSocket socket, String channel) {
        Thread t = new Thread(() -> {
            byte[] bytes = new byte[16384];
            while (!closed.get()) {
                try {
                    DatagramPacket p = new DatagramPacket(bytes, bytes.length);
                    socket.receive(p);
                    accept(new String(bytes, 0, p.getLength(), StandardCharsets.UTF_8), p.getAddress(), channel);
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (!closed.get()) log.accept("SSDP " + channel + " 接收失败: " + e.getMessage());
                    break;
                }
            }
        }, "WB800F-SSDP-" + channel);
        t.setDaemon(true); t.start();
    }
    private void send() {
        if (closed.get()) return;
        String[] targets = {"ssdp:all", "upnp:rootdevice", "ssdp:all", "urn:schemas-upnp-org:device:MediaServer:1"};
        String st = targets[attempt++ % targets.length];
        byte[] bytes = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
            + "MX: 1\r\nST: " + st + "\r\nUSER-AGENT: " + agent + "\r\nACCESS-METHOD: manual\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        for (InetSocketAddress destination : destinations) {
            try {
                search.send(new DatagramPacket(bytes, bytes.length, destination));
                // Some firmware replies to the conventional control-point port only.
                if (announcements != null && destination.getAddress().isMulticastAddress())
                    announcements.send(new DatagramPacket(bytes, bytes.length, destination));
            } catch (Exception e) {
                if (!closed.get()) log.accept("SSDP 发送到 " + destination + " 失败: " + e.getMessage());
            }
        }
        if (attempt == 1 || attempt % 5 == 0)
            log.accept("SSDP 搜索 #" + attempt + "，临时端口=" + search.getLocalPort()
                + "，公告端口=" + (announcements == null ? "不可用" : announcements.getLocalPort()) + "，ST=" + st);
    }
    private void accept(String packet, InetAddress source, String channel) {
        if (!(source.isSiteLocalAddress() || source.isLinkLocalAddress() || source.isLoopbackAddress())) return;
        String first = packet.split("\r?\n", 2)[0];
        if (!(first.startsWith("HTTP/1.1 200") || first.startsWith("HTTP/1.0 200") || first.equals("NOTIFY * HTTP/1.1"))) return;
        Map<String, String> h = CameraProtocol.headers(packet);
        int count = received.incrementAndGet();
        String location = h.get("location"), nts = h.getOrDefault("nts", "");
        if (count <= 8) log.accept("SSDP " + channel + " from=" + source.getHostAddress() + " " + first
            + "，NTS=" + nts + "，ST/NT=" + h.getOrDefault("st", h.getOrDefault("nt", ""))
            + "，LOCATION=" + (location == null ? "无" : location));
        if (location == null || "ssdp:byebye".equalsIgnoreCase(nts)) return;
        try {
            URL u = new URL(location);
            if (!(u.getProtocol().equals("http") || u.getProtocol().equals("https")) || u.getUserInfo() != null) return;
            synchronized (locations) {
                if (locations.size() < 32 && locations.add(location)) log.accept("SSDP location: " + location);
            }
        } catch (MalformedURLException ignored) { }
    }
    public List<String> locations() { synchronized (locations) { return new ArrayList<>(locations); } }
    public int receivedCount() { return received.get(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        timer.shutdownNow(); search.close();
        if (announcements != null) announcements.close();
        log.accept("SSDP 结束：收到 " + received.get() + " 条响应/公告，设备地址 " + locations().size() + " 个");
    }
}
