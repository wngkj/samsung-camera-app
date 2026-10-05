package cn.cameralink.wb800f;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Pure Java, independently testable UPnP/DIDL parsing. No camera mutation actions. */
public final class CameraProtocol {
    public static final String CONTENT = "urn:schemas-upnp-org:service:ContentDirectory:1";
    public static final class Device {
        public String name, manufacturer, udn, location, control, event, type;
    }
    public static final class Photo {
        public String id, title, date, url, thumb, mime, resolution;
        public long size;
        public String key(Device d) {
            // Host IP can change; UDN + object ID + resource path survive reconnects.
            String path;
            try { path = new URI(url).getRawPath(); } catch (Exception e) { path = url; }
            return hash(d.udn + "|" + id + "|" + path + "|" + title + "|" + date + "|" + size);
        }
    }
    public static final class Page {
        public List<Photo> photos = new ArrayList<>();
        public List<String> containers = new ArrayList<>();
        public int returned, total;
    }
    public static String hash(String s) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte v : b) out.append(String.format(Locale.ROOT, "%02x", v & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }
    public static Document parse(String xml) throws Exception {
        // Reject DTD entirely on both the Android and desktop XML implementations.
        if (xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) throw new IOException("不支持含 DTD 的 XML");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("External entity blocked"); });
        return b.parse(new InputSource(new StringReader(xml)));
    }
    private static String local(Node n) { return n.getLocalName() == null ? n.getNodeName() : n.getLocalName(); }
    public static List<Element> descendants(Element e, String name) {
        List<Element> out = new ArrayList<>();
        NodeList ns = e.getElementsByTagNameNS("*", name);
        for (int i = 0; i < ns.getLength(); i++) out.add((Element) ns.item(i));
        if (out.isEmpty()) {
            ns = e.getElementsByTagName(name);
            for (int i = 0; i < ns.getLength(); i++) out.add((Element) ns.item(i));
        }
        return out;
    }
    public static String child(Element e, String name) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && name.equals(local(n))) return n.getTextContent().trim();
        return "";
    }
    private static String first(Element e, String name) {
        List<Element> a = descendants(e, name);
        return a.isEmpty() ? "" : a.get(0).getTextContent().trim();
    }
    public static String resolve(String base, String path) throws Exception {
        URL u = new URL(new URL(base), path.trim());
        if (!(u.getProtocol().equals("http") || u.getProtocol().equals("https"))) throw new IOException("Unsupported URL");
        if (u.getUserInfo() != null) throw new IOException("URL credentials not permitted");
        return u.toExternalForm();
    }
    public static Device device(String body, String location) throws Exception {
        Element root = parse(body).getDocumentElement();
        String base = first(root, "URLBase");
        if (base.isEmpty()) base = location;
        for (Element dev : descendants(root, "device")) {
            String name = child(dev, "friendlyName"), maker = child(dev, "manufacturer");
            if (!maker.toLowerCase(Locale.ROOT).contains("samsung") ||
                !(name.toLowerCase(Locale.ROOT).contains("camera") || name.toLowerCase(Locale.ROOT).contains("wb800"))) continue;
            for (Element service : descendants(dev, "service")) {
                String type = child(service, "serviceType");
                if (!type.startsWith("urn:schemas-upnp-org:service:ContentDirectory:")) continue;
                String control = child(service, "controlURL");
                if (control.isEmpty()) continue;
                Device d = new Device();
                d.name = name; d.manufacturer = maker; d.udn = child(dev, "UDN");
                if (d.udn.isEmpty()) d.udn = name;
                d.type = type; d.location = location; d.control = resolve(base, control);
                String event = child(service, "eventSubURL");
                d.event = event.isEmpty() ? "" : resolve(base, event);
                return d;
            }
        }
        throw new IOException("未找到三星相机的 ContentDirectory 服务");
    }
    public static String browseBody(String type, String object, int start, int count) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Browse xmlns:u=\"" + xml(type) + "\">"
            + "<ObjectID>" + xml(object) + "</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>"
            + "<StartingIndex>" + start + "</StartingIndex><RequestedCount>" + count + "</RequestedCount>"
            + "<SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>";
    }
    public static Page page(String soap, String base) throws Exception {
        Element env = parse(soap).getDocumentElement();
        String fault = first(env, "errorCode");
        if (!fault.isEmpty()) throw new IOException("UPnP " + fault + ": " + first(env, "errorDescription"));
        List<Element> results = descendants(env, "Result");
        if (results.isEmpty()) throw new IOException("相机未返回 Browse Result");
        Element r = results.get(0);
        // Result is normally escaped XML. Some devices return actual child elements.
        Element didl = null;
        for (Node n = r.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) { didl = (Element) n; break; }
        String value = r.getTextContent().trim();
        Page p = new Page();
        p.returned = (int) number(first(env, "NumberReturned"));
        p.total = (int) number(first(env, "TotalMatches"));
        if (didl == null && value.isEmpty()) return p;
        if (didl == null) didl = parse(value).getDocumentElement(); // Do not unescape again: titles may contain &.
        int entries = 0;
        for (Node node = didl.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element)) continue;
            Element e = (Element) node;
            if (local(e).equals("container")) {
                entries++;
                if (!e.getAttribute("id").isEmpty()) p.containers.add(e.getAttribute("id"));
            } else if (local(e).equals("item")) {
                entries++;
                Photo photo = new Photo();
                photo.id = e.getAttribute("id"); photo.title = child(e, "title"); photo.date = child(e, "date");
                long best = Long.MIN_VALUE, thumbScore = Long.MAX_VALUE;
                for (Element res : descendants(e, "res")) {
                    String pi = res.getAttribute("protocolInfo");
                    String[] parts = pi.split(":", 4);
                    String mime = parts.length > 2 ? parts[2].toLowerCase(Locale.ROOT) : "";
                    if (!mime.startsWith("image/")) continue;
                    String url = res.getTextContent().trim();
                    if (url.isEmpty()) continue;
                    long size = number(res.getAttribute("size")), pixels = pixels(res.getAttribute("resolution"));
                    boolean thumbnail = pi.contains("JPEG_TN") || pi.contains("PNG_TN") || (pixels > 0 && pixels <= 20000);
                    boolean small = pi.contains("JPEG_SM") || pi.contains("PNG_SM");
                    if (thumbnail && (photo.thumb == null || pixels < thumbScore)) { photo.thumb = resolve(base, url); thumbScore = pixels; }
                    if (thumbnail || small) continue; // Never silently save a thumbnail as the original.
                    long score = (pi.contains("JPEG_LRG") ? 1L << 60 : 0) + pixels * 1024 + Math.min(size, 1L << 30);
                    if (score > best) {
                        best = score; photo.url = resolve(base, url); photo.size = size;
                        photo.mime = mime; photo.resolution = res.getAttribute("resolution");
                    }
                }
                if (photo.url != null) {
                    if (photo.title.isEmpty()) photo.title = "IMG_" + photo.id + ".jpg";
                    p.photos.add(photo);
                }
            }
        }
        if (p.returned == 0) p.returned = entries;
        if (p.total == 0) p.total = p.returned;
        return p;
    }
    public static long number(String s) { try { return Math.max(0, Long.parseLong(s)); } catch (Exception e) { return 0; } }
    private static long pixels(String s) {
        String[] a = s.toLowerCase(Locale.ROOT).split("x");
        return a.length == 2 ? Math.min(100000, number(a[0])) * Math.min(100000, number(a[1])) : 0;
    }
    public static Map<String, String> headers(String packet) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String s : packet.split("\r?\n")) {
            int colon = s.indexOf(':');
            if (colon > 0) out.put(s.substring(0, colon).trim().toLowerCase(Locale.ROOT), s.substring(colon + 1).trim());
        }
        return out;
    }
    public static String filename(Photo p) {
        String name = p.title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "IMG_" + hash(p.url).substring(0, 12);
        if (name.length() > 120) name = name.substring(0, 120);
        if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|webp|heic|gif|bmp)$")) {
            name += p.mime.equals("image/png") ? ".png" : p.mime.equals("image/webp") ? ".webp" : ".jpg";
        }
        return name;
    }
}
