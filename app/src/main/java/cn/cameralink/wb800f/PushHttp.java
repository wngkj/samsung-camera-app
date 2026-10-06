package cn.cameralink.wb800f;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small bounded HTTP framing for Samsung's camera-to-phone transfer protocol. */
public final class PushHttp {
    public static final class Header {
        public String first;
        public final Map<String, String> values = new LinkedHashMap<>();
    }
    public static Header read(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int suffix = 0;
        while (suffix != 0x0d0a0d0a) {
            int b = in.read();
            if (b < 0) throw new EOFException("相机 HTTP 头未完整发送");
            bytes.write(b); suffix = (suffix << 8) | b;
            if (bytes.size() > 32768) throw new IOException("相机 HTTP 头过大");
        }
        String[] lines = bytes.toString("UTF-8").split("\r\n");
        Header h = new Header(); h.first = lines[0];
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 1) throw new IOException("相机 HTTP 字段无效");
            String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (h.values.containsKey(key)) throw new IOException("相机 HTTP 字段重复: " + key);
            h.values.put(key, value);
        }
        return h;
    }
    public static void write(OutputStream out, String message) throws IOException {
        out.write(message.getBytes(StandardCharsets.US_ASCII)); out.flush();
    }
    public static String response(int code, String reason, String error) {
        return "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nSub-ErrorCode: " + error + "\r\nConnection: close\r\n\r\n";
    }
    private PushHttp() { }
}
