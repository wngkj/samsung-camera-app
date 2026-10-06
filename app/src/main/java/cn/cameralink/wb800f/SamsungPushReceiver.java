package cn.cameralink.wb800f;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Receives original JPEG bodies; final success is sent only after storage commits. */
public final class SamsungPushReceiver implements AutoCloseable {
    public interface Transaction extends AutoCloseable {
        OutputStream output();
        boolean commit() throws Exception; // false means an identical, already saved photo.
        @Override void close();
    }
    public interface Storage { Transaction begin(String name, long size) throws Exception; }
    public interface Listener {
        void progress(String name, long bytes, long total);
        void saved(String name, boolean added);
        void ended(String reason);
        default void failed(String reason) { }
    }
    private final ServerSocket server;
    private final InetAddress camera;
    private final Storage storage;
    private final Listener listener;
    private final Consumer<String> log;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Socket active;

    public SamsungPushReceiver(ServerSocket server, InetAddress camera, Storage storage, Listener listener, Consumer<String> log) {
        this.server = server; this.camera = camera; this.storage = storage; this.listener = listener; this.log = log;
    }
    public int port() { return server.getLocalPort(); }
    public void start() {
        Thread t = new Thread(() -> {
            while (!closed.get()) {
                try {
                    Socket socket = server.accept();
                    if (closed.get()) { socket.close(); break; }
                    active = socket;
                    if (closed.get()) { socket.close(); active = null; break; }
                    try (Socket s = socket) {
                        if (!camera.equals(s.getInetAddress())) { log.accept("忽略非相机的接收连接"); continue; }
                        s.setSoTimeout(30000); receive(s);
                    } finally { active = null; }
                } catch (Exception e) { if (!closed.get()) log.accept("相机发送接收失败: " + e.getMessage()); }
            }
        }, "WB800F-camera-push");
        t.setDaemon(true); t.start();
    }
    private void check() throws IOException { if (closed.get()) throw new IOException("接收已取消"); }
    private void receive(Socket socket) throws Exception {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream reply = socket.getOutputStream();
        Transaction tx = null;
        try {
            PushHttp.Header h = PushHttp.read(in);
            log.accept("相机发送请求: " + h.first);
            String command = h.values.getOrDefault("command", "");
            String request = h.values.getOrDefault("request", "").toLowerCase(Locale.ROOT);
            if (command.equalsIgnoreCase("Getout") || request.startsWith("byebye")) {
                PushHttp.write(reply, PushHttp.response(200, "OK", "0"));
                listener.ended("相机已结束发送"); return;
            }
            String[] first = h.first.split(" ", 3);
            if (first.length != 3 || !(first[0].equals("POST") || first[0].equals("PUT")))
                throw new IOException("不支持的照片发送请求");
            if (h.values.containsKey("transfer-encoding")) throw new IOException("相机发送需提供固定文件大小");
            String length = h.values.getOrDefault("content-length", "");
            if (!length.matches("[0-9]{1,12}")) throw new IOException("照片大小无效");
            long size = Long.parseLong(length);
            if (size < 3 || size > 256L * 1024 * 1024) throw new IOException("照片大小超出接收范围");
            String path = first[1].split("[?#]", 2)[0];
            String name = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1).replace("+", "%2B"), StandardCharsets.UTF_8.name());
            if (!name.toLowerCase(Locale.ROOT).matches(".+\\.jpe?g")) {
                PushHttp.write(reply, PushHttp.response(415, "Unsupported Media Type", "2001"));
                log.accept("仅接收 JPEG 照片: " + name); return;
            }
            CameraProtocol.Photo photo = new CameraProtocol.Photo(); photo.title = name; photo.mime = "image/jpeg";
            name = CameraProtocol.filename(photo); check();
            tx = storage.begin(name, size);
            // Samsung waits for this interim response before sending its file body.
            PushHttp.write(reply, "HTTP/1.1 100 Continue\r\n\r\n");
            byte[] signature = new byte[3]; readFully(in, signature);
            if ((signature[0] & 255) != 255 || (signature[1] & 255) != 216 || (signature[2] & 255) != 255)
                throw new IOException("收到的文件不是 JPEG 照片");
            OutputStream out = tx.output(); out.write(signature); long done = 3;
            byte[] bytes = new byte[65536];
            while (done < size) {
                check(); int n = in.read(bytes, 0, (int) Math.min(bytes.length, size - done));
                if (n < 0) throw new EOFException("照片传输中断: " + done + "/" + size);
                out.write(bytes, 0, n); done += n; listener.progress(name, done, size);
            }
            out.flush(); check();
            boolean added = tx.commit();
            listener.saved(name, added);
            PushHttp.write(reply, PushHttp.response(200, "OK", "0"));
            log.accept("相机发送完成: " + name + "，" + size + " bytes，" + (added ? "已保存" : "重复跳过"));
        } catch (Exception e) {
            if (!closed.get()) try { PushHttp.write(reply, PushHttp.response(500, "Internal Server Error", "2001")); } catch (IOException ignored) { }
            if (!closed.get()) listener.failed(e.getMessage());
            throw e;
        } finally { if (tx != null) tx.close(); }
    }
    private static void readFully(InputStream in, byte[] bytes) throws IOException {
        for (int offset = 0; offset < bytes.length;) {
            int n = in.read(bytes, offset, bytes.length - offset);
            if (n < 0) throw new EOFException("照片传输中断"); offset += n;
        }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { server.close(); } catch (IOException ignored) { }
        Socket socket = active; if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
