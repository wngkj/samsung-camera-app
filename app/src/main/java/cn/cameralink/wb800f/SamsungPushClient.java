package cn.cameralink.wb800f;

import java.net.*;
import java.util.function.Consumer;

/** Camera-selected-photo handshake, distinct from DLNA phone-selected browsing. */
public final class SamsungPushClient implements AutoCloseable {
    public interface Transport { Socket connect(String host, int port, int timeout) throws Exception; }
    private final Transport transport;
    private final SamsungPushReceiver receiver;
    private final Consumer<String> log;
    private final String host, localIp, mac;
    private final int dataPort;
    private volatile boolean closed;
    private volatile Socket pending;
    private volatile int cameraPort;
    public SamsungPushClient(Transport transport, SamsungPushReceiver receiver, String host, String localIp, String mac, Consumer<String> log) {
        this.transport = transport; this.receiver = receiver; this.host = host; this.localIp = localIp; this.mac = mac; this.log = log;
        dataPort = receiver.port();
    }
    public void connect() throws Exception {
        receiver.start(); Exception last = null;
        for (int port = 8100; port <= 8103; port++) {
            if (closed) throw new java.io.IOException("接收连接已取消");
            try {
                PushHttp.Header h = exchange(port, "alive", 2500);
                log.accept("SP HEAD " + host + ":" + port + " → " + h.first + " / " + h.values);
                if (!h.first.matches("HTTP/1\\.[01] 2[0-9][0-9].*"))
                    throw new java.io.IOException("相机选片握手被拒绝: " + h.first + " / " + h.values);
                if (closed) throw new java.io.IOException("接收连接已取消");
                cameraPort = port; return;
            } catch (Exception e) {
                last = e; log.accept("SP 端口 " + port + " 不可用: " + e.getMessage());
                if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("503"))) break;
            }
        }
        receiver.close();
        throw new java.io.IOException("无法连接相机选片发送服务（8100–8103）" + (last == null ? "" : ": " + last.getMessage()), last);
    }
    private PushHttp.Header exchange(int port, String nts, int timeout) throws Exception {
        try (Socket socket = transport.connect(host, port, timeout)) {
            pending = socket;
            if (closed && nts.equals("alive")) throw new java.io.IOException("接收连接已取消");
            socket.setSoTimeout(timeout);
            String request = "HEAD /sp/control HTTP/1.1\r\nHost: " + host + ":" + port
                + "\r\nUser-Agent: SEC_SP_" + mac + "\r\nData-Server: " + localIp + ":" + dataPort
                + "\r\nData-Port: " + dataPort + "\r\nNTS: " + nts
                + "\r\nHOST-PNumber: none\r\nAccess-Method: manual\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            PushHttp.write(socket.getOutputStream(), request);
            return PushHttp.read(socket.getInputStream());
        } finally { pending = null; }
    }
    @Override public void close() {
        if (closed) return; closed = true;
        receiver.close(); Socket socket = pending;
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        int port = cameraPort;
        if (port != 0) {
            Thread goodbye = new Thread(() -> {
                try { exchange(port, "byebye", 700); }
                catch (Exception e) { log.accept("SP 结束通知: " + e.getMessage()); }
            }, "WB800F-push-end");
            goodbye.setDaemon(true); goodbye.start();
        }
    }
}
