package cn.cameralink.wb800f;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Keeps every socket on the selected Wi-Fi, even when Android prefers cellular Internet. */
public final class WifiConnection implements CameraClient.Transport, AutoCloseable {
    private final ConnectivityManager cm;
    private final Context context;
    private final Consumer<String> log;
    private final String mac, agent;
    private volatile Network network;
    private volatile Inet4Address address;
    private volatile String gateway;
    private volatile boolean closed;
    private ConnectivityManager.NetworkCallback callback;
    private WifiManager.MulticastLock multicast;
    private ServerSocket eventServer;
    private ScheduledExecutorService heartbeat;
    private volatile CameraProtocol.Device device;
    private volatile String sid;
    private volatile SsdpDiscovery discovery;
    public WifiConnection(Context context, String clientMac, Consumer<String> logger) {
        this.context = context.getApplicationContext();
        cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        log = logger; mac = clientMac; agent = "SEC_DSC_" + mac;
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicast = wifi.createMulticastLock("WB800F-discovery"); multicast.setReferenceCounted(false);
    }
    public String agent() { return agent; }
    public String localIp() { return address == null ? "" : address.getHostAddress(); }
    public void connect() throws Exception {
        boolean allowed = Build.VERSION.SDK_INT < 37
            || context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED;
        log.accept("局域网权限=" + (allowed ? "允许" : "未允许") + "，INTERNET="
            + (context.checkSelfPermission("android.permission.INTERNET") == PackageManager.PERMISSION_GRANTED));
        if (!allowed) throw new IOException("请在应用权限中允许本地网络 / 附近设备，再连接相机");
        CountDownLatch ready = new CountDownLatch(1);
        callback = new ConnectivityManager.NetworkCallback() {
            private void update(Network n, LinkProperties props) {
                if (closed || props == null) return;
                for (LinkAddress link : props.getLinkAddresses()) if (link.getAddress() instanceof Inet4Address) {
                    network = n; address = (Inet4Address) link.getAddress();
                    for (RouteInfo r : props.getRoutes()) if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address)
                        gateway = r.getGateway().getHostAddress();
                    ready.countDown(); break;
                }
            }
            @Override public void onAvailable(Network n) { update(n, cm.getLinkProperties(n)); }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties p) { update(n, p); }
            @Override public void onLost(Network n) {
                if (n.equals(network)) { network = null; address = null; log.accept("相机 Wi-Fi 已断开，请重新连接"); }
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
        cm.requestNetwork(request, callback);
        if (!ready.await(8, TimeUnit.SECONDS)) throw new IOException("手机尚未连接相机 Wi-Fi，请打开 Wi-Fi 设置连接 AP_SSC_WB800F…");
        multicast.acquire();
        log.accept("Wi-Fi IPv4=" + localIp() + ", gateway=" + gateway + ", app client=" + mac);
        LinkProperties links = cm.getLinkProperties(requireNetwork());
        NetworkCapabilities caps = cm.getNetworkCapabilities(requireNetwork());
        log.accept("Wi-Fi network=" + network + "，interface=" + (links == null ? "unknown" : links.getInterfaceName())
            + "，validated=" + (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            + "，默认网络=" + cm.getActiveNetwork());
    }
    private Network requireNetwork() throws IOException {
        Network n = network;
        if (closed || n == null || address == null) throw new IOException("相机 Wi-Fi 连接已断开");
        return n;
    }
    private void localUrl(URL url) throws Exception {
        if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https")) || url.getUserInfo() != null)
            throw new IOException("不支持的相机地址");
        InetAddress dest = requireNetwork().getByName(url.getHost());
        if (!(dest.isSiteLocalAddress() || dest.isLinkLocalAddress())) throw new IOException("仅允许访问局域网中的相机");
    }
    @Override public HttpURLConnection open(String target) throws Exception {
        URL url = new URL(target); localUrl(url);
        return (HttpURLConnection) requireNetwork().openConnection(url, java.net.Proxy.NO_PROXY);
    }
    public List<String> candidates(String manual) throws Exception {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (manual != null && !manual.trim().isEmpty()) {
            String s = manual.trim();
            if (s.startsWith("http://") || s.startsWith("https://")) {
                URL u = new URL(s); localUrl(u); urls.add(s);
                if (u.getHost().equals(localIp())) throw new IOException("该地址是手机自身，请填写相机地址或 Wi-Fi 网关");
                if (u.getPath().isEmpty() || u.getPath().equals("/")) addCandidates(urls, u.getHost(), u.getPort());
            } else {
                if (!s.matches("[0-9.]+(:[0-9]{1,5})?")) throw new IOException("请输入相机 IPv4 地址，或完整的设备描述 URL");
                String[] a = s.split(":"); addCandidates(urls, a[0], a.length > 1 ? Integer.parseInt(a[1]) : -1);
                if (a[0].equals(localIp())) throw new IOException("该地址是手机自身，请填写相机地址或 Wi-Fi 网关");
            }
        }
        if (gateway != null) addCandidates(urls, gateway, -1);
        // Only try conventional Samsung addresses that belong to the connected /24.
        String ip = localIp();
        for (String host : Arrays.asList("192.168.107.1", "192.168.101.1", "192.168.104.1", "192.168.0.1"))
            if (ip.substring(0, ip.lastIndexOf('.')).equals(host.substring(0, host.lastIndexOf('.')))) addCandidates(urls, host, -1);
        return new ArrayList<>(urls);
    }
    /** Leave both sockets open until the camera's photo service becomes available. */
    public void beginDiscovery() {
        MulticastSocket search = null, announcements = null;
        try {
            Network n = requireNetwork();
            NetworkInterface iface = NetworkInterface.getByInetAddress(address);
            search = new MulticastSocket(null); search.setReuseAddress(true);
            n.bindSocket(search); search.bind(new InetSocketAddress(address, 0));
            search.setNetworkInterface(iface); search.setTimeToLive(2);
            try {
                announcements = new MulticastSocket(null); announcements.setReuseAddress(true);
                n.bindSocket(announcements); announcements.bind(new InetSocketAddress(1900));
                announcements.setNetworkInterface(iface); announcements.setTimeToLive(2);
                announcements.joinGroup(new InetSocketAddress("239.255.255.250", 1900), iface);
                log.accept("SSDP 公告监听已开启：UDP 1900，interface=" + iface.getName());
            } catch (Exception e) {
                if (announcements != null) announcements.close(); announcements = null;
                log.accept("SSDP 公告监听不可用，继续单播回复: " + e.getMessage());
            }
            List<InetSocketAddress> destinations = new ArrayList<>();
            destinations.add(new InetSocketAddress("239.255.255.250", 1900));
            if (gateway != null && !gateway.equals(localIp())) destinations.add(new InetSocketAddress(n.getByName(gateway), 1900));
            discovery = new SsdpDiscovery(search, announcements, destinations, agent, log);
            discovery.start();
        } catch (Exception e) {
            if (search != null) search.close(); if (announcements != null) announcements.close();
            log.accept("SSDP 启动失败: " + e.getMessage());
        }
    }
    public List<String> discovered() { SsdpDiscovery d = discovery; return d == null ? Collections.emptyList() : d.locations(); }
    public int discoveryResponses() { SsdpDiscovery d = discovery; return d == null ? 0 : d.receivedCount(); }
    public void endDiscovery() { SsdpDiscovery d = discovery; if (d != null) d.close(); }
    private static void addCandidates(Set<String> urls, String host, int port) {
        int p = port > 0 ? port : 7676;
        urls.add("http://" + host + ":" + p + "/smp_6_");
        urls.add("http://" + host + ":" + p + "/smp_2_");
        urls.add("http://" + host + ":" + p + "/description.xml");
        if (port <= 0) { urls.add("http://" + host + "/description.xml"); urls.add("http://" + host + ":49152/description.xml"); }
    }
    /** Optional mode handshake for firmware exposing port 7788. Camera approval remains manual. */
    public void pair(String host) {
        if (host == null || host.isEmpty()) return;
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", "SEC_MODE_" + mac); h.put("Access-Method", "manual"); h.put("NTS", "alive");
            h.put("HOST-Mac", mac); h.put("HOST-Address", localIp()); h.put("HOST-port", "7788"); h.put("HOST-PNumber", "none");
            raw("GET", "http://" + host + ":7788/mode/control", h, 1500);
        } catch (Exception e) { log.accept("可选 mode/control 配对: " + e.getMessage()); }
    }
    public String gateway() { return gateway; }
    public boolean cameraSelectedMode() { return gateway != null && gateway.startsWith("192.168.104."); }
    public ServerSocket pushServer() throws Exception {
        requireNetwork();
        ServerSocket server = new ServerSocket(); server.setReuseAddress(true);
        try { server.bind(new InetSocketAddress(address, 18100)); }
        catch (Exception e) { server.close(); throw e; }
        log.accept("相机选片接收监听: " + localIp() + ":18100"); return server;
    }
    public InetAddress cameraAddress(String host) throws Exception {
        localUrl(new URL("http://" + host + "/")); return requireNetwork().getByName(host);
    }
    public Socket pushSocket(String host, int port, int timeout) throws Exception {
        InetAddress target = cameraAddress(host);
        Socket socket = requireNetwork().getSocketFactory().createSocket();
        try { socket.connect(new InetSocketAddress(target, port), timeout); return socket; }
        catch (Exception e) { socket.close(); throw e; }
    }
    public void startEvents(CameraProtocol.Device d) {
        device = d;
        if (d.event.isEmpty()) return;
        try {
            eventServer = new ServerSocket(); eventServer.setReuseAddress(true);
            eventServer.bind(new InetSocketAddress(address, 0));
            Thread thread = new Thread(this::events, "WB800F-events"); thread.setDaemon(true); thread.start();
            subscribe();
            heartbeat = Executors.newSingleThreadScheduledExecutor();
            heartbeat.scheduleWithFixedDelay(() -> {
                if (closed) return;
                try { subscribe(); } catch (Exception e) { sid = null; log.accept("会话保活: " + e.getMessage()); }
            }, 40, 40, TimeUnit.SECONDS);
        } catch (Exception e) { log.accept("事件订阅不可用，继续读取照片: " + e.getMessage()); }
    }
    private void subscribe() throws Exception {
        if (closed || device == null || eventServer == null) return;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", agent); headers.put("TIMEOUT", "Second-300");
        if (sid != null) headers.put("SID", sid);
        else { headers.put("NT", "upnp:event"); headers.put("CALLBACK", "<http://" + localIp() + ":" + eventServer.getLocalPort() + "/event>"); }
        Map<String, String> response = raw("SUBSCRIBE", device.event, headers, 4000);
        if (response.containsKey("sid")) sid = response.get("sid");
        log.accept("GENA 会话已" + (sid == null ? "请求" : "建立/续期"));
    }
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1;) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("HTTP header too large");
        }
        return b.toString("UTF-8");
    }
    private Map<String, String> raw(String method, String target, Map<String, String> headers, int timeout) throws Exception {
        URL u = new URL(target); localUrl(u);
        if (!u.getProtocol().equals("http")) throw new IOException("GENA expects HTTP");
        int port = u.getPort() > 0 ? u.getPort() : 80;
        try (Socket socket = requireNetwork().getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(requireNetwork().getByName(u.getHost()), port), timeout);
            socket.setSoTimeout(timeout);
            StringBuilder request = new StringBuilder(method + " " + (u.getFile().isEmpty() ? "/" : u.getFile()) + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port + "\r\n");
            for (Map.Entry<String, String> h : headers.entrySet()) request.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            request.append("Content-Length: 0\r\nConnection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            String status = readLine(in); StringBuilder response = new StringBuilder();
            for (int i = 0; i < 100; i++) { String line = readLine(in); if (line.isEmpty()) break; response.append(line).append('\n'); }
            log.accept(method + " " + target + " → " + status);
            if (!status.matches("HTTP/1\\.[01] 2[0-9][0-9].*")) throw new IOException(status.isEmpty() ? "相机未响应" : status);
            return CameraProtocol.headers(response.toString());
        }
    }
    private void events() {
        while (!closed && eventServer != null && !eventServer.isClosed()) {
            try (Socket s = eventServer.accept()) {
                s.setSoTimeout(3000); InputStream in = s.getInputStream(); String first = readLine(in);
                int length = 0;
                for (int i = 0; i < 100; i++) {
                    String line = readLine(in); if (line.isEmpty()) break;
                    Map<String, String> h = CameraProtocol.headers(line);
                    if (h.containsKey("content-length")) length = (int) Math.min(65536, CameraProtocol.number(h.get("content-length")));
                }
                for (int i = 0; i < length; i++) if (in.read() == -1) break;
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                log.accept("GENA event: " + first);
            } catch (Exception e) { if (!closed) log.accept("GENA callback: " + e.getMessage()); }
        }
    }
    @Override public void close() {
        closed = true;
        endDiscovery();
        if (heartbeat != null) heartbeat.shutdownNow();
        if (eventServer != null) try { eventServer.close(); } catch (Exception ignored) { }
        if (multicast != null && multicast.isHeld()) multicast.release();
        if (callback != null) { try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) { } callback = null; }
        network = null;
    }
}
