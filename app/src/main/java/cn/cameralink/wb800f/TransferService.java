package cn.cameralink.wb800f;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Connection survives Activity recreation; imports run as a cancellable dataSync foreground service. */
public final class TransferService extends Service {
    public static final String ACTION_CANCEL = "cn.cameralink.wb800f.CANCEL";
    private static final String CHANNEL = "photo-transfer";
    private static final int NOTIFICATION = 800;
    public final class LocalBinder extends Binder { public TransferService service() { return TransferService.this; } }
    public interface Listener { void changed(); }
    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    private final AtomicBoolean occupied = new AtomicBoolean();
    private volatile Listener listener;
    public volatile String status = "等待连接相机", detail = "先在相机上打开 Wi-Fi → MobileLink";
    public volatile int progress = 0;
    public volatile boolean transferring, connected;
    public volatile List<CameraProtocol.Photo> photos = Collections.emptyList();
    public volatile Set<String> downloaded = Collections.emptySet();
    private volatile CameraProtocol.Device device;
    private volatile CameraClient client;
    private volatile WifiConnection wifi;
    private PhotoStore store;
    private PowerManager.WakeLock wake;
    private final Deque<String> logLines = new ArrayDeque<>();
    private volatile boolean destroyed;
    public boolean busy() { return occupied.get(); }
    public void listen(Listener l) { listener = l; changed(); }
    public void unlisten(Listener l) { if (listener == l) listener = null; }
    private void changed() { main.post(() -> { Listener l = listener; if (l != null && !destroyed) l.changed(); }); }
    private synchronized void log(String s) {
        logLines.addLast(java.time.LocalTime.now().withNano(0) + " " + s);
        while (logLines.size() > 500) logLines.removeFirst();
    }
    public static String version(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "unknown"; }
    }
    public synchronized String logs() {
        return "WB800F Transfer " + version(this) + " / targetSdk 37\nAndroid " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT
            + " / " + Build.MANUFACTURER + " " + Build.MODEL + "\n状态: " + status + "\n" + String.join("\n", logLines) + "\n";
    }
    @Override public void onCreate() {
        super.onCreate(); store = new PhotoStore(this);
        NotificationManager n = getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel(CHANNEL, "相机照片传输", NotificationManager.IMPORTANCE_LOW));
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wb800f:photo-transfer");
        worker.submit(store::cleanupPending); log("应用启动");
    }
    @Override public IBinder onBind(Intent i) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) cancel();
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId, int fgsType) {
        cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private String clientMac() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        String mac = prefs.getString("clientMac", null);
        if (mac == null) {
            byte[] bytes = new byte[5]; new java.security.SecureRandom().nextBytes(bytes);
            StringBuilder b = new StringBuilder("02"); for (byte v : bytes) b.append(String.format(Locale.ROOT, ":%02x", v & 255));
            mac = b.toString(); prefs.edit().putString("clientMac", mac).apply();
        }
        return mac;
    }
    public void connect(String manual) {
        if (!occupied.compareAndSet(false, true)) return;
        status = "正在连接相机"; detail = "请保持相机 MobileLink 开启，并连接相机 Wi-Fi"; progress = 0;
        connected = false; photos = Collections.emptyList(); changed();
        worker.submit(() -> {
            try {
                disconnectInternal();
                WifiConnection connection = new WifiConnection(this, clientMac(), this::log); wifi = connection;
                CameraClient c = new CameraClient(connection, connection.agent(), this::log); client = c;
                connection.connect(); c.check();
                List<String> candidates = connection.candidates(manual);
                log("开始发现相机，服务等待窗口约 45 秒" + (manual == null ? "" : "，手动地址=" + manual));
                connection.beginDiscovery();
                CameraProtocol.Device found = null; Exception last = null;
                Set<String> paired = new HashSet<>();
                Map<String, Long> probed = new HashMap<>();
                long deadline = SystemClock.elapsedRealtime() + 45000;
                try {
                    while (found == null && SystemClock.elapsedRealtime() < deadline) {
                        c.check();
                        LinkedHashSet<String> current = new LinkedHashSet<>(connection.discovered());
                        current.addAll(candidates);
                        String location = null;
                        long now = SystemClock.elapsedRealtime();
                        long oldest = Long.MAX_VALUE;
                        for (String url : current) {
                            Long previous = probed.get(url);
                            if (previous == null) { location = url; break; }
                            if (now - previous >= 7000 && previous < oldest) { oldest = previous; location = url; }
                        }
                        if (location == null) { Thread.sleep(200); continue; }
                        probed.put(location, now);
                        status = "正在连接相机";
                        detail = "正在等待相机照片服务 · 剩余约 " + Math.max(1, (deadline - now) / 1000) + " 秒"; changed();
                        try { found = c.device(location, 1800); }
                        catch (Exception e) {
                            c.check(); last = e; log("设备描述不可用: " + location + " / " + e.getMessage());
                            String host = new URL(location).getHost();
                            if (paired.add(host)) connection.pair(host);
                        }
                    }
                } finally { connection.endDiscovery(); }
                if (found == null) {
                    String discoveryHint = connection.discoveryResponses() == 0
                        ? "未收到相机发现回复或公告。请确认 MobileLink → 从智能手机选择文件，并保持连接相机 Wi-Fi。"
                        : "已收到局域网发现报文，但相机照片服务仍不可用，请查看相机屏幕并导出完整日志。";
                    throw new IOException(discoveryHint + (last == null ? "" : "\n" + last.getMessage()));
                }
                device = found;
                status = "正在读取照片"; detail = found.name + " · " + connection.localIp(); changed();
                connection.startEvents(found); c.check();
                List<CameraProtocol.Photo> result = c.browse(found, count -> { detail = "已找到 " + count + " 张照片，请保持相机开启"; changed(); });
                photos = Collections.unmodifiableList(result); refreshDownloaded(); connected = true;
                status = result.isEmpty() ? "已连接，未找到照片" : "已连接 · " + found.name;
                detail = result.isEmpty() ? "请在相机选择「从智能手机选择文件」，并确认存储卡内有照片。" : result.size() + " 张照片 · 已保存 " + downloaded.size() + " 张";
                log("照片列表完成: " + result.size());
            } catch (Exception e) {
                status = client != null && client.cancelled ? "连接已取消" : "连接未完成";
                detail = e.getMessage() == null ? e.toString() : e.getMessage(); log("连接: " + detail); disconnectInternal();
            } finally { occupied.set(false); changed(); }
        });
    }
    private void refreshDownloaded() {
        Set<String> keys = new HashSet<>(); CameraProtocol.Device d = device;
        if (d != null) for (CameraProtocol.Photo p : photos) if (store.exists(p.key(d))) keys.add(p.key(d));
        downloaded = Collections.unmodifiableSet(keys);
    }
    public String key(CameraProtocol.Photo p) { CameraProtocol.Device d = device; return d == null ? p.url : p.key(d); }
    public boolean saved(CameraProtocol.Photo p) { return downloaded.contains(key(p)); }
    public void transfer(List<String> selected) {
        if (!connected || client == null || selected.isEmpty() || !occupied.compareAndSet(false, true)) return;
        List<CameraProtocol.Photo> jobs = new ArrayList<>();
        for (CameraProtocol.Photo p : photos) if (selected.contains(key(p))) jobs.add(p);
        if (jobs.isEmpty()) { occupied.set(false); return; }
        client.cancelled = false; transferring = true; progress = 0; status = "准备传输 " + jobs.size() + " 张"; detail = "保存到相册 Pictures/WB800F";
        try {
            startForeground(NOTIFICATION, notification(status), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            startService(new Intent(this, TransferService.class));
            wake.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception e) {
            transferring = false; occupied.set(false); status = "无法启动传输"; detail = e.getMessage(); changed(); return;
        }
        changed();
        worker.submit(() -> {
            int success = 0, skip = 0, failed = 0; boolean cancelled = false;
            CameraClient c = client; CameraProtocol.Device d = device;
            try {
                for (int index = 0; index < jobs.size(); index++) {
                    c.check(); CameraProtocol.Photo p = jobs.get(index);
                    if (store.exists(p.key(d))) { skip++; continue; }
                    final int at = index;
                    status = "传输 " + (index + 1) + "/" + jobs.size() + " · " + p.title;
                    log("下载: " + p.title + " / " + p.url + " / " + p.size);
                    final long[] last = {0};
                    try {
                        Uri uri = store.save(d, p, c, (bytes, total) -> {
                            long now = SystemClock.elapsedRealtime();
                            if (now - last[0] < 350 && bytes != total) return; last[0] = now;
                            progress = Math.min(99, (int) ((at + (total > 0 ? (double) bytes / total : 0)) * 100 / jobs.size()));
                            detail = formatSize(bytes) + (total > 0 ? " / " + formatSize(total) : "") + " · 保持相机开启";
                            changed(); getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(status));
                        });
                        success++; log("保存完成: " + p.title + " → " + uri);
                        Set<String> done = new HashSet<>(downloaded); done.add(p.key(d)); downloaded = Collections.unmodifiableSet(done);
                    } catch (Exception e) {
                        c.check(); failed++; log("下载失败: " + p.title + " / " + e.getMessage());
                    }
                    changed();
                }
            } catch (Exception e) { cancelled = c.cancelled; log("传输停止: " + e.getMessage()); }
            finally {
                progress = cancelled ? progress : 100;
                status = cancelled ? "传输已取消" : failed > 0 ? "传输结束，部分照片失败" : "传输完成";
                detail = "已保存 " + success + " 张 · 已有 " + skip + " 张 · 失败 " + failed + " 张";
                if (failed > 0) detail += "，可再次选择失败照片重试";
                transferring = false; occupied.set(false);
                if (c.cancelled) { connected = false; disconnectInternal(); detail += "。再次传输前请重新连接相机"; }
                if (wake.isHeld()) wake.release();
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); changed();
            }
        });
    }
    private Notification notification(String title) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TransferService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_camera).setContentTitle(title)
            .setContentText("照片保存到 Pictures/WB800F").setContentIntent(open).setOngoing(true)
            .setProgress(100, progress, false).addAction(new Notification.Action.Builder(null, "取消", stop).build()).build();
    }
    public void cancel() { CameraClient c = client; if (c != null) c.cancel(); log("用户取消操作"); }
    public void thumbnail(CameraProtocol.Photo p, java.util.function.Consumer<Bitmap> callback) {
        CameraClient c = client;
        if (p.thumb == null || c == null || busy() || !connected) { callback.accept(null); return; }
        thumbs.submit(() -> {
            Bitmap bitmap = null;
            try {
                if (c != client || busy() || !connected) return;
                byte[] bytes = c.thumbnail(p.thumb);
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                while (bounds.outWidth / options.inSampleSize > 320 || bounds.outHeight / options.inSampleSize > 320) options.inSampleSize *= 2;
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            } catch (Exception e) { log("缩略图: " + e.getMessage()); }
            finally { final Bitmap result = bitmap; main.post(() -> { if (!destroyed) callback.accept(result); }); }
        });
    }
    public static String formatSize(long size) { return size < 1024 * 1024 ? String.format(Locale.CHINA, "%.0f KB", size / 1024.0) : String.format(Locale.CHINA, "%.1f MB", size / 1048576.0); }
    private void disconnectInternal() {
        CameraClient c = client; if (c != null) c.cancel();
        WifiConnection w = wifi; if (w != null) w.close();
        client = null; wifi = null; connected = false;
    }
    @Override public void onDestroy() {
        destroyed = true; listener = null; disconnectInternal(); worker.shutdownNow(); thumbs.shutdownNow();
        if (wake != null && wake.isHeld()) wake.release(); super.onDestroy();
    }
}
