import cn.cameralink.wb800f.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Real TCP integration tests for camera-selected transfers and storage transactions. */
public final class PushTest {
    static int passed;
    static byte[] jpeg = new byte[128000];
    static { for (int i = 0; i < jpeg.length; i++) jpeg[i] = (byte) (i * 17); jpeg[0] = (byte)255; jpeg[1] = (byte)216; jpeg[2] = (byte)255; }
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static void pass(String name) { passed++; System.out.println("PASS " + name); }
    static void waitFor(BooleanSupplier check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!check.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(check.getAsBoolean(), "receiver deadline exceeded");
    }
    static ServerSocket server() throws IOException { return new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")); }
    static class Store implements SamsungPushReceiver.Storage {
        AtomicInteger begins = new AtomicInteger(), added = new AtomicInteger(), rolledBack = new AtomicInteger();
        final Set<String> hashes = ConcurrentHashMap.newKeySet();
        volatile byte[] saved;
        volatile boolean failCommit;
        public SamsungPushReceiver.Transaction begin(String name, long size) {
            begins.incrementAndGet(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            return new SamsungPushReceiver.Transaction() {
                boolean done;
                public OutputStream output() { return bytes; }
                public boolean commit() throws Exception {
                    if (failCommit) throw new IOException("simulated disk error");
                    check(bytes.size() == size, "committed wrong length");
                    String hash = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
                    boolean fresh = hashes.add(hash); if (fresh) { saved = bytes.toByteArray(); added.incrementAndGet(); }
                    done = true; return fresh;
                }
                public void close() { if (!done) { done = true; rolledBack.incrementAndGet(); } }
            };
        }
    }
    static class Observer implements SamsungPushReceiver.Listener {
        AtomicInteger completed = new AtomicInteger(), ends = new AtomicInteger();
        public void progress(String name, long bytes, long total) { check(bytes <= total, "progress overflow"); }
        public void saved(String name, boolean added) { completed.incrementAndGet(); }
        public void ended(String reason) { ends.incrementAndGet(); }
    }
    static Socket camera(int port) throws IOException { Socket s = new Socket("127.0.0.1", port); s.setSoTimeout(4000); return s; }
    static String request(String name, int size) { return "POST /DCIM/100PHOTO/" + name + " HTTP/1.1\r\nContent-Length : " + size + "\r\nExpect: 100-continue\r\n\r\n"; }
    static String sendPhoto(int port, byte[] body, boolean fragmented, int size, String name) throws Exception {
        try (Socket socket = camera(port)) {
            byte[] header = request(name, size).getBytes(StandardCharsets.US_ASCII);
            if (fragmented) for (byte b : header) { socket.getOutputStream().write(b); socket.getOutputStream().flush(); }
            else PushHttp.write(socket.getOutputStream(), new String(header, StandardCharsets.US_ASCII));
            String interim = PushHttp.read(socket.getInputStream()).first;
            if (!interim.contains("100 Continue")) return interim;
            socket.getOutputStream().write(body); socket.getOutputStream().flush();
            if (body.length < size) socket.shutdownOutput();
            return PushHttp.read(socket.getInputStream()).first;
        }
    }
    public static void main(String[] args) throws Exception {
        Store store = new Store(); Observer observer = new Observer();
        try (SamsungPushReceiver receiver = new SamsungPushReceiver(server(), InetAddress.getByName("127.0.0.1"), store, observer, line -> {})) {
            receiver.start(); int port = receiver.port();
            check(sendPhoto(port, jpeg, true, jpeg.length, "SAM_0001.JPG").contains("200 OK"), "fragmented upload failed");
            check(store.added.get() == 1 && Arrays.equals(store.saved, jpeg), "ack before commit / bytes changed");
            pass("fragmented Samsung headers, 100 Continue and unchanged bytes committed before 200");
            check(sendPhoto(port, jpeg, false, jpeg.length, "SAM_0001.JPG").contains("200 OK"), "duplicate rejected");
            check(store.added.get() == 1 && observer.completed.get() == 2, "duplicate stored twice");
            pass("repeated photo acknowledged without adding another saved file");
            try (Socket socket = camera(port)) {
                socket.getOutputStream().write(request("SAM_0002.JPG", jpeg.length).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(jpeg); socket.getOutputStream().flush();
                check(PushHttp.read(socket.getInputStream()).first.contains("100 Continue"), "interim missing");
                check(PushHttp.read(socket.getInputStream()).first.contains("200 OK"), "coalesced request failed");
            }
            pass("header and body arriving together preserve framing");
            int rollback = store.rolledBack.get();
            check(sendPhoto(port, Arrays.copyOf(jpeg, 20), false, jpeg.length, "short.JPG").contains("500"), "truncation accepted");
            waitFor(() -> store.rolledBack.get() > rollback); check(store.added.get() == 1, "partial file published");
            pass("truncated body rejected and pending storage rolled back");
            check(sendPhoto(port, new byte[30], false, 30, "not-jpeg.JPG").contains("500"), "invalid JPEG accepted");
            pass("non-JPEG body is not published as a photo");
            store.failCommit = true;
            check(sendPhoto(port, jpeg, false, jpeg.length, "disk-error.JPG").contains("500"), "disk failure acknowledged as success");
            store.failCommit = false;
            pass("storage commit failure produces error rather than success acknowledgement");
            int starts = store.begins.get();
            check(sendPhoto(port, jpeg, false, jpeg.length, "video.MP4").contains("415"), "video accepted as JPEG");
            check(store.begins.get() == starts, "unsupported file created storage");
            pass("non-photo transfer rejected before storage begins");
            try (Socket socket = camera(port)) {
                PushHttp.write(socket.getOutputStream(), "POST /DCIM/a.JPG HTTP/1.1\r\nContent-Length: 10\r\nContent-Length: 10\r\n\r\n");
                check(PushHttp.read(socket.getInputStream()).first.contains("500"), "duplicate length accepted");
            }
            pass("ambiguous duplicate Content-Length is rejected");
            try (Socket socket = camera(port)) {
                PushHttp.write(socket.getOutputStream(), "POST /control HTTP/1.1\r\nCommand: Getout\r\nContent-Length: 0\r\n\r\n");
                check(PushHttp.read(socket.getInputStream()).first.contains("200"), "end command unacknowledged");
            }
            waitFor(() -> observer.ends.get() == 1); pass("camera end command is acknowledged and reported");
            int beforeCancel = store.rolledBack.get();
            try (Socket socket = camera(port)) {
                PushHttp.write(socket.getOutputStream(), request("cancel.JPG", jpeg.length));
                PushHttp.read(socket.getInputStream()); socket.getOutputStream().write(jpeg, 0, 20); socket.getOutputStream().flush();
                receiver.close();
                waitFor(() -> store.rolledBack.get() > beforeCancel);
                check(store.added.get() == 1, "cancellation published a partial photo");
            }
            pass("cancellation interrupts the active socket and rolls back pending storage");
        }
        Store rejected = new Store();
        try (SamsungPushReceiver receiver = new SamsungPushReceiver(server(), InetAddress.getByName("127.0.0.2"), rejected, new Observer(), line -> {})) {
            receiver.start();
            try (Socket socket = camera(receiver.port())) { check(socket.getInputStream().read() == -1, "unexpected peer admitted"); }
            check(rejected.begins.get() == 0, "unexpected peer created photo");
            pass("only the selected camera peer may send photos");
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket control = server(); SamsungPushReceiver receiver = new SamsungPushReceiver(server(), InetAddress.getByName("127.0.0.1"), new Store(), new Observer(), line -> {})) {
            Future<PushHttp.Header> request = executor.submit(() -> {
                try (Socket socket = control.accept()) {
                    PushHttp.Header h = PushHttp.read(socket.getInputStream());
                    PushHttp.write(socket.getOutputStream(), "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"); return h;
                }
            });
            List<Integer> attempts = new CopyOnWriteArrayList<>();
            try (SamsungPushClient client = new SamsungPushClient((host, port, timeout) -> {
                attempts.add(port); if (port == 8100) throw new ConnectException("fixture port unavailable");
                return camera(control.getLocalPort());
            }, receiver, "127.0.0.1", "127.0.0.1", "02:11:22:33:44:55", line -> {})) {
                client.connect(); PushHttp.Header h = request.get(4, TimeUnit.SECONDS);
                check(h.first.equals("HEAD /sp/control HTTP/1.1"), "wrong SP request");
                check(h.values.get("user-agent").equals("SEC_SP_02:11:22:33:44:55"), "wrong sender agent");
                check(h.values.get("data-server").equals("127.0.0.1:" + receiver.port()), "wrong callback");
                check(h.values.get("data-port").equals(String.valueOf(receiver.port())), "wrong data port");
                check(h.values.get("nts").equals("alive") && h.values.get("access-method").equals("manual"), "handshake metadata missing");
                check(attempts.subList(0, 2).equals(Arrays.asList(8100, 8101)), "port fallback missing");
                pass("SP HEAD handshake advertises real receiver and falls back across camera ports");
            }
        } finally { executor.shutdownNow(); }
        System.out.println(passed + " push tests passed");
    }
}
