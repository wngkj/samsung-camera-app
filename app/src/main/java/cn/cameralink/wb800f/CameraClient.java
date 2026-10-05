package cn.cameralink.wb800f;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** HTTP and paginated ContentDirectory logic, injectable transport for protocol tests. */
public final class CameraClient {
    public interface Transport { HttpURLConnection open(String url) throws Exception; }
    public interface Progress { void bytes(long count, long total) throws Exception; }
    private final Transport transport;
    private final Consumer<String> log;
    private final String agent;
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    public volatile boolean cancelled;
    public CameraClient(Transport transport, String agent, Consumer<String> log) {
        this.transport = transport; this.agent = agent; this.log = log;
    }
    public void check() throws IOException { if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("操作已取消"); }
    public void cancel() { cancelled = true; for (HttpURLConnection c : active) c.disconnect(); }
    private HttpURLConnection open(String url, int timeout) throws Exception {
        check();
        HttpURLConnection c = transport.open(url);
        c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
        c.setInstanceFollowRedirects(false); // A camera must not redirect traffic off the Wi-Fi network.
        c.setRequestProperty("User-Agent", agent);
        c.setRequestProperty("Access-Method", "manual");
        c.setRequestProperty("Connection", "close");
        c.setRequestProperty("Accept-Encoding", "identity");
        active.add(c);
        return c;
    }
    public String request(String url, String body, String action, int timeout) throws Exception {
        HttpURLConnection c = open(url, timeout);
        try {
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                c.setRequestProperty("SOAPAction", "\"" + action + "\"");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int status = c.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            String text = input == null ? "" : new String(read(input, 8 * 1024 * 1024), StandardCharsets.UTF_8);
            log.accept((body == null ? "GET " : "SOAP Browse ") + url + " → HTTP " + status + " (" + text.length() + ")");
            if (status < 200 || status >= 300) {
                String detail = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
                throw new IOException("HTTP " + status + ": " + detail.substring(0, Math.min(220, detail.length())));
            }
            return text;
        } finally { active.remove(c); c.disconnect(); }
    }
    private byte[] read(InputStream in, int limit) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[32768]; int n;
            while ((n = input.read(b)) != -1) {
                check(); if (out.size() + n > limit) throw new IOException("相机响应超过大小限制");
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }
    public CameraProtocol.Device device(String url, int timeout) throws Exception {
        return CameraProtocol.device(request(url, null, null, timeout), url);
    }
    public List<CameraProtocol.Photo> browse(CameraProtocol.Device d, Consumer<Integer> count) throws Exception {
        LinkedHashMap<String, CameraProtocol.Photo> files = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>(); queue.add("0");
        Set<String> visited = new HashSet<>();
        boolean rootFailed = false;
        while (!queue.isEmpty()) {
            check(); String object = queue.removeFirst();
            if (!visited.add(object)) continue;
            if (visited.size() > 2048) throw new IOException("相机目录过多，请分批传输");
            int start = 0;
            Set<String> signatures = new HashSet<>();
            for (int page = 0; page < 10000; page++) {
                check(); String response; CameraProtocol.Page p;
                try {
                    response = request(d.control, CameraProtocol.browseBody(d.type, object, start, 100), d.type + "#Browse", 18000);
                    p = CameraProtocol.page(response, d.location);
                } catch (Exception e) {
                    if (object.equals("0") && start == 0 && !rootFailed) {
                        rootFailed = true; log.accept("根目录读取失败，尝试三星照片目录 ObjectID=1: " + e.getMessage()); queue.add("1"); break;
                    }
                    throw e;
                }
                String signature = CameraProtocol.hash(response);
                if (p.returned == 0) break;
                if (!signatures.add(signature)) throw new IOException("相机重复返回同一页，无法保证照片列表完整");
                for (CameraProtocol.Photo photo : p.photos) files.putIfAbsent(photo.key(d), photo);
                for (String container : p.containers) if (!visited.contains(container)) queue.addLast(container);
                count.accept(files.size());
                if (files.size() > 100000) throw new IOException("照片超过 100000 张，请分批处理");
                start += p.returned;
                if (start >= p.total) break;
                if (page == 9999) throw new IOException("分页超过安全限制");
            }
        }
        // A few Samsung firmwares expose the image container only at ObjectID 1.
        if (files.isEmpty() && !visited.contains("1")) {
            log.accept("空根目录，尝试照片目录 ObjectID=1");
            int start = 0;
            Set<String> signatures = new HashSet<>();
            for (int page = 0; page < 10000; page++) {
                String response = request(d.control, CameraProtocol.browseBody(d.type, "1", start, 100), d.type + "#Browse", 18000);
                CameraProtocol.Page p = CameraProtocol.page(response, d.location);
                if (p.returned > 0 && !signatures.add(CameraProtocol.hash(response))) throw new IOException("相机重复返回同一页，无法保证照片列表完整");
                for (CameraProtocol.Photo photo : p.photos) files.putIfAbsent(photo.key(d), photo);
                count.accept(files.size());
                if (p.returned == 0 || (start += p.returned) >= p.total) break;
                if (page == 9999) throw new IOException("分页超过安全限制");
            }
        }
        List<CameraProtocol.Photo> out = new ArrayList<>(files.values());
        out.sort((a, b) -> { int c = b.date.compareTo(a.date); return c == 0 ? a.title.compareTo(b.title) : c; });
        return out;
    }
    public byte[] thumbnail(String url) throws Exception {
        HttpURLConnection c = open(url, 5000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("缩略图不可用");
            return read(c.getInputStream(), 2 * 1024 * 1024);
        } finally { active.remove(c); c.disconnect(); }
    }
    /** Stream bytes unchanged, verify advertised lengths before allowing MediaStore commit. */
    public long download(CameraProtocol.Photo p, OutputStream out, Progress progress) throws Exception {
        HttpURLConnection c = open(p.url, 25000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("下载返回 HTTP " + c.getResponseCode());
            String contentType = c.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/")) throw new IOException("相机返回文本，未收到照片");
            long httpSize = c.getContentLengthLong(), total = p.size > 0 ? p.size : httpSize;
            if (httpSize > 0 && p.size > 0 && httpSize != p.size) throw new IOException("照片大小与目录不一致，请刷新列表");
            long downloaded = 0; byte[] buffer = new byte[65536]; int n;
            try (InputStream in = c.getInputStream()) {
                while ((n = in.read(buffer)) != -1) {
                    check(); out.write(buffer, 0, n); downloaded += n; progress.bytes(downloaded, total);
                }
            }
            check(); out.flush();
            if (downloaded == 0 || (total > 0 && downloaded != total)) throw new IOException("照片不完整 (" + downloaded + "/" + total + ")，请重试");
            return downloaded;
        } finally { active.remove(c); c.disconnect(); }
    }
}
