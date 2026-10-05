import cn.cameralink.wb800f.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline contract tests using real HTTP responses, not Android stubs. */
public class ProtocolTest {
    static int passed;
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    interface Test { void run() throws Exception; }
    static void test(String name, Test body) throws Exception { body.run(); passed++; System.out.println("PASS " + name); }
    static String type = CameraProtocol.CONTENT;
    static String device(String base) {
        return "<root xmlns='urn:schemas-upnp-org:device-1-0'><URLBase>" + base + "/nested/</URLBase><device><friendlyName>[Camera]WB800F</friendlyName>"
            + "<manufacturer>Samsung Electronics</manufacturer><UDN>uuid:wb800f-test</UDN><serviceList><service><serviceType>" + type + "</serviceType>"
            + "<controlURL>../control</controlURL><eventSubURL>/events</eventSubURL></service></serviceList></device></root>";
    }
    static String res(String url, String profile, long size, String resolution) {
        return "<res protocolInfo='http-get:*:image/jpeg:DLNA.ORG_PN=" + profile + "' size='" + size + "' resolution='" + resolution + "'>" + CameraProtocol.xml(url) + "</res>";
    }
    static String photo(int id, String base) {
        return "<item id='" + id + "'><dc:title>A &amp; B " + id + ".jpg</dc:title><dc:date>2026-10-05T10:00:00</dc:date>"
            + res(base + "/thumb?x=1&y=2", "JPEG_TN", 30, "160x120")
            + res(base + "/medium", "JPEG_MED", 900, "800x600")
            + res(base + "/photo?id=" + id, "JPEG_LRG", 32, "4608x3456") + "</item>";
    }
    static String didl(String inner) { return "<DIDL-Lite xmlns='urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/' xmlns:dc='http://purl.org/dc/elements/1.1/'>" + inner + "</DIDL-Lite>"; }
    static String response(String inner, int returned, int total) {
        return "<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'><s:Body><u:BrowseResponse xmlns:u='" + type + "'><Result>"
            + CameraProtocol.xml(didl(inner)) + "</Result><NumberReturned>" + returned + "</NumberReturned><TotalMatches>" + total + "</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>";
    }
    static CameraClient client() { return new CameraClient(url -> (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY), "SEC_DSC_02:00:00:00:00:01", line -> {}); }
    static CameraProtocol.Photo p(String url, long size) { CameraProtocol.Photo p = new CameraProtocol.Photo(); p.url = url; p.size = size; p.mime = "image/jpeg"; return p; }
    public static void main(String[] args) throws Exception {
        test("namespace + URLBase-relative service URL", () -> {
            CameraProtocol.Device d = CameraProtocol.device(device("http://192.168.107.1:7676"), "http://192.168.107.1:7676/smp_6_");
            check(d.control.equals("http://192.168.107.1:7676/control"), d.control); check(d.event.endsWith("/events"), "event");
        });
        test("escaped DIDL retains ampersands, prefers original over larger preview", () -> {
            CameraProtocol.Page page = CameraProtocol.page(response(photo(1, "http://192.168.107.1:7676"), 1, 1), "http://192.168.107.1:7676/smp_6_");
            check(page.photos.size() == 1, "photo count"); CameraProtocol.Photo p = page.photos.get(0);
            check(p.title.equals("A & B 1.jpg"), p.title); check(p.url.endsWith("/photo?id=1"), p.url); check(p.thumb.endsWith("x=1&y=2"), p.thumb);
        });
        test("thumbnail-only items are never passed off as originals", () -> {
            String item = "<item id='thumb'><dc:title>small.jpg</dc:title>" + res("/thumb", "JPEG_TN", 30, "160x120") + "</item>";
            check(CameraProtocol.page(response(item, 1, 1), "http://192.168.107.1/x").photos.isEmpty(), "thumbnail incorrectly imported");
        });
        test("nested unescaped Result XML", () -> {
            String body = "<Envelope><Result>" + didl(photo(2, "http://192.168.107.1")) + "</Result><NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches></Envelope>";
            check(CameraProtocol.page(body, "http://192.168.107.1/x").photos.size() == 1, "nested Result");
        });
        test("DTD and external entity rejection", () -> {
            boolean rejected = false;
            try { CameraProtocol.parse("<!DOCTYPE foo [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><foo>&x;</foo>"); }
            catch (Exception e) { rejected = true; } check(rejected, "DTD accepted");
        });
        test("SSDP case-insensitive headers", () -> check(CameraProtocol.headers("HTTP/1.1 200 OK\r\nLoCaTiOn: http://192.168.107.1:7676/smp_6_\r\n").containsKey("location"), "header case"));
        test("SOAP escapes object identifiers", () -> {
            String body = CameraProtocol.browseBody(type, "a<&b", 100, 100); CameraProtocol.parse(body);
            check(body.contains("a&lt;&amp;b"), "XML injection");
        });
        test("stable import key across camera IP changes", () -> {
            CameraProtocol.Device a = CameraProtocol.device(device("http://192.168.107.1:7676"), "http://192.168.107.1:7676/smp_6_");
            CameraProtocol.Photo p = CameraProtocol.page(response(photo(1, "http://192.168.107.1:7676"), 1, 1), a.location).photos.get(0);
            String key = p.key(a); p.url = p.url.replace("192.168.107.1", "192.168.101.1"); check(p.key(a).equals(key), "unstable key");
        });
        test("safe filenames", () -> {
            CameraProtocol.Photo p = p("http://camera/file", 32); p.title = "../A:B.jpg";
            String name = CameraProtocol.filename(p); check(!name.contains("/") && !name.contains(":"), name);
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort(); AtomicInteger requests = new AtomicInteger();
        byte[] data = new byte[32]; for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7);
        server.createContext("/desc", ex -> { byte[] b = device(base).getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        server.createContext("/control", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); requests.incrementAndGet();
            String r;
            if (body.contains("<ObjectID>0</ObjectID>")) r = response("<container id='1'/><container id='0'/>", 2, 2);
            else {
                int offset = body.contains("<StartingIndex>2</StartingIndex>") ? 2 : body.contains("<StartingIndex>1</StartingIndex>") ? 1 : 0;
                r = response(photo(offset + 1, base), 1, 3);
            }
            check(ex.getRequestHeaders().getFirst("SOAPAction").equals("\"" + type + "#Browse\""), "SOAPAction");
            byte[] b = r.getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        server.createContext("/photo", ex -> { ex.getResponseHeaders().set("Content-Type", "image/jpeg"); ex.sendResponseHeaders(200, data.length); ex.getResponseBody().write(data); ex.close(); });
        server.createContext("/chunked", ex -> { ex.sendResponseHeaders(200, 0); ex.getResponseBody().write(data, 0, 8); ex.close(); });
        server.createContext("/text", ex -> { ex.getResponseHeaders().set("Content-Type", "text/html"); ex.sendResponseHeaders(200, 5); ex.getResponseBody().write("error".getBytes(StandardCharsets.UTF_8)); ex.close(); });
        server.createContext("/redirect", ex -> { ex.getResponseHeaders().set("Location", base + "/photo"); ex.sendResponseHeaders(302, -1); ex.close(); });
        server.createContext("/repeat", ex -> { byte[] b = response(photo(1, base), 1, 300).getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        server.createContext("/fallback", ex -> {
            String b = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String r = b.contains("<ObjectID>0</ObjectID>") ? "<errorCode>701</errorCode>" : response(photo(1, base), 1, 1);
            byte[] data2 = r.getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(b.contains("<ObjectID>0</ObjectID>") ? 500 : 200, data2.length); ex.getResponseBody().write(data2); ex.close();
        });
        server.createContext("/fault200", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String r = body.contains("<ObjectID>0</ObjectID>") ? "<Envelope><errorCode>701</errorCode><errorDescription>No Such Object</errorDescription></Envelope>" : response(photo(1, base), 1, 1);
            byte[] b = r.getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        server.start();
        try {
            test("real HTTP nested directory, pagination and cycle avoidance", () -> {
                CameraClient c = client(); CameraProtocol.Device d = c.device(base + "/desc", 1000);
                List<CameraProtocol.Photo> files = c.browse(d, count -> {});
                check(files.size() == 3, "incomplete pagination: " + files.size()); check(requests.get() == 4, "container cycle not avoided");
            });
            test("Samsung direct image-container fallback", () -> {
                CameraClient c = client(); CameraProtocol.Device d = c.device(base + "/desc", 1000); d.control = base + "/fallback";
                check(c.browse(d, n -> {}).size() == 1, "ObjectID 1 fallback missing");
            });
            test("SOAP fault at HTTP 200 also triggers Samsung container fallback", () -> {
                CameraClient c = client(); CameraProtocol.Device d = c.device(base + "/desc", 1000); d.control = base + "/fault200";
                check(c.browse(d, n -> {}).size() == 1, "HTTP 200 fault fallback missing");
            });
            test("download bytes unchanged and progress counted", () -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); long[] progress = {0};
                client().download(p(base + "/photo", 32), out, (n, t) -> { progress[0] = n; check(t == 32, "progress total"); });
                check(Arrays.equals(out.toByteArray(), data), "bytes changed"); check(progress[0] == 32, "progress count");
            });
            test("incomplete chunked download rejected", () -> {
                boolean rejected = false; try { client().download(p(base + "/chunked", 32), new ByteArrayOutputStream(), (n, t) -> {}); }
                catch (IOException e) { rejected = true; } check(rejected, "partial photo accepted");
            });
            test("HTTP vs directory size mismatch rejected", () -> {
                boolean rejected = false; try { client().download(p(base + "/photo", 99), new ByteArrayOutputStream(), (n, t) -> {}); }
                catch (IOException e) { rejected = true; } check(rejected, "size mismatch accepted");
            });
            test("HTML error never saved as photo", () -> {
                boolean rejected = false; try { client().download(p(base + "/text", 0), new ByteArrayOutputStream(), (n, t) -> {}); }
                catch (IOException e) { rejected = true; } check(rejected, "HTML accepted");
            });
            test("redirects rejected", () -> {
                boolean rejected = false; try { client().download(p(base + "/redirect", 32), new ByteArrayOutputStream(), (n, t) -> {}); }
                catch (IOException e) { rejected = true; } check(rejected, "redirect followed");
            });
            test("cancel aborts streaming", () -> {
                CameraClient c = client(); boolean rejected = false;
                try { c.download(p(base + "/photo", 32), new ByteArrayOutputStream(), (n, t) -> c.cancel()); }
                catch (IOException e) { rejected = true; } check(rejected, "cancellation ignored");
            });
            test("repeated pagination is reported, not silently truncated", () -> {
                CameraClient c = client(); CameraProtocol.Device d = c.device(base + "/desc", 1000); d.control = base + "/repeat";
                boolean rejected = false; try { c.browse(d, n -> {}); } catch (IOException e) { rejected = true; }
                check(rejected, "repeated page silently accepted");
            });
        } finally { server.stop(0); }
        System.out.println(passed + " tests passed");
    }
}
