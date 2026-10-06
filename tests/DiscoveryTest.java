import cn.cameralink.wb800f.SsdpDiscovery;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Actual UDP regression tests: no camera or Android dependency. */
public final class DiscoveryTest {
    static int passed;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void waitFor(BooleanSupplier test) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!test.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(test.getAsBoolean(), "UDP receive deadline exceeded");
    }
    static DatagramSocket socket() throws Exception { return new DatagramSocket(new InetSocketAddress("127.0.0.1", 0)); }
    static void send(DatagramSocket socket, int port, String message) throws Exception {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), port));
    }
    public static void main(String[] args) throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        try (DatagramSocket camera = socket(); DatagramSocket search = socket(); DatagramSocket announcement = socket();
             SsdpDiscovery discovery = new SsdpDiscovery(search, announcement,
                 Arrays.asList(new InetSocketAddress("127.0.0.1", camera.getLocalPort())), "SEC_DSC_02:11:22:33:44:55", logs::add)) {
            camera.setSoTimeout(4000); discovery.start();
            byte[] bytes = new byte[4096]; DatagramPacket p = new DatagramPacket(bytes, bytes.length); camera.receive(p);
            String request = new String(bytes, 0, p.getLength(), StandardCharsets.US_ASCII);
            check(request.contains("M-SEARCH * HTTP/1.1\r\n"), "search missing");
            check(request.contains("USER-AGENT: SEC_DSC_02:11:22:33:44:55\r\n") && request.contains("ACCESS-METHOD: manual\r\n"), "Samsung headers missing");
            String url = "http://127.0.0.1:8765/smp_6_";
            send(camera, p.getPort(), "HTTP/1.1 200 OK\r\nLoCaTiOn: " + url + "\r\nST: upnp:rootdevice\r\n\r\n");
            waitFor(() -> discovery.locations().contains(url));
            passed++; System.out.println("PASS Samsung M-SEARCH and ephemeral-port unicast reply");

            // Approval arrives after the first response, on the separate announcement socket.
            String late = "http://127.0.0.1:8765/after-approval";
            send(camera, announcement.getLocalPort(), "NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\nLOCATION: " + late + "\r\nNT: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n");
            waitFor(() -> discovery.locations().contains(late));
            passed++; System.out.println("PASS late approval NOTIFY is discovered on announcement socket");

            int previous = discovery.receivedCount();
            send(camera, announcement.getLocalPort(), "NOTIFY * HTTP/1.1\r\nNTS: ssdp:byebye\r\nLOCATION: http://127.0.0.1:8765/gone\r\n\r\n");
            send(camera, search.getLocalPort(), "HTTP/1.1 200 OK\r\nST: ssdp:all\r\n\r\n");
            waitFor(() -> discovery.receivedCount() >= previous + 2);
            check(!discovery.locations().contains("http://127.0.0.1:8765/gone"), "byebye offered as available");
            check(logs.stream().anyMatch(s -> s.contains("LOCATION=无")), "location-less reply not diagnosed");
            passed++; System.out.println("PASS byebye excluded and location-less reply diagnosed");

            send(camera, search.getLocalPort(), request);
            send(camera, announcement.getLocalPort(), "NOTIFY * HTTP/1.1\r\nLOCATION: file:///tmp/private\r\n\r\n");
            waitFor(() -> discovery.receivedCount() >= previous + 3);
            check(discovery.locations().size() == 2, "self-search or invalid URL accepted");
            passed++; System.out.println("PASS self-search ignored and non-HTTP LOCATION rejected");

            camera.receive(new DatagramPacket(bytes, bytes.length)); // Search continues after the initial LOCATION.
            check(!search.isClosed() && !announcement.isClosed(), "discovery stopped before approval completed");
            discovery.close();
            check(search.isClosed() && announcement.isClosed(), "discovery socket leak after cancellation");
            discovery.close();
            passed++; System.out.println("PASS discovery continues through approval and closes both sockets");
        }
        try (DatagramSocket camera = socket(); DatagramSocket search = socket();
             SsdpDiscovery discovery = new SsdpDiscovery(search, null,
                 Arrays.asList(new InetSocketAddress("127.0.0.1", camera.getLocalPort())), "SEC_DSC_test", logs::add)) {
            discovery.start();
            send(camera, search.getLocalPort(), "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:8765/fallback\r\n\r\n");
            waitFor(() -> !discovery.locations().isEmpty());
            passed++; System.out.println("PASS unavailable announcement listener retains unicast discovery");
        }
        System.out.println(passed + " discovery tests passed");
    }
}
