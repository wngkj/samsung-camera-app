package cn.cameralink.wb800f;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.*;
import java.security.*;
import java.time.*;
import java.util.Locale;

/** Atomic scoped-storage writes. Index entries are recorded only after a completed import. */
public final class PhotoStore {
    private final ContentResolver resolver;
    private final SharedPreferences index;
    public PhotoStore(Context c) { resolver = c.getContentResolver(); index = c.getSharedPreferences("imports-v1", Context.MODE_PRIVATE); }
    public boolean exists(String key) {
        String saved = index.getString(key, null);
        if (saved == null) return false;
        try (ParcelFileDescriptor file = resolver.openFileDescriptor(Uri.parse(saved), "r")) {
            if (file != null) return true;
        } catch (Exception ignored) { }
        index.edit().remove(key).apply(); return false;
    }
    // Executed on the transfer worker: persist the completed import before stopping the service.
    @android.annotation.SuppressLint("ApplySharedPref")
    public Uri save(CameraProtocol.Device device, CameraProtocol.Photo photo, CameraClient client, CameraClient.Progress progress) throws Exception {
        String key = photo.key(device);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, CameraProtocol.filename(photo));
        values.put(MediaStore.Images.Media.MIME_TYPE, photo.mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WB800F");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        try {
            long date;
            try { date = OffsetDateTime.parse(photo.date).toInstant().toEpochMilli(); }
            catch (Exception ignored) { date = LocalDateTime.parse(photo.date).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
            if (date > 0) values.put(MediaStore.Images.Media.DATE_TAKEN, date);
        } catch (Exception ignored) { }
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法创建相册照片，请检查手机剩余空间");
        boolean committed = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream raw = resolver.openOutputStream(uri, "w")) {
                if (raw == null) throw new IOException("无法写入相册");
                DigestOutputStream output = new DigestOutputStream(new BufferedOutputStream(raw), digest);
                client.download(photo, output, progress); output.flush();
            }
            client.check();
            ContentValues done = new ContentValues(); done.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(uri, done, null, null) != 1) throw new IOException("无法完成相册保存");
            committed = true;
            StringBuilder sha = new StringBuilder(); for (byte b : digest.digest()) sha.append(String.format(Locale.ROOT, "%02x", b & 255));
            index.edit().putString(key, uri.toString()).putString(key + ".sha256", sha.toString()).commit();
            return uri;
        } finally { if (!committed) resolver.delete(uri, null, null); }
    }
    /** Transaction used by camera-selected sending; deduplicates the full received bytes. */
    public SamsungPushReceiver.Transaction beginPush(String name, long size) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WB800F");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法创建相册照片，请检查手机剩余空间");
        try {
            OutputStream raw = resolver.openOutputStream(uri, "w");
            if (raw == null) throw new IOException("无法写入相册");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DigestOutputStream output = new DigestOutputStream(new BufferedOutputStream(raw), digest);
            return new SamsungPushReceiver.Transaction() {
                private boolean finished;
                @Override public OutputStream output() { return output; }
                @Override @android.annotation.SuppressLint("ApplySharedPref") public boolean commit() throws Exception {
                    output.close();
                    StringBuilder sha = new StringBuilder();
                    for (byte b : digest.digest()) sha.append(String.format(Locale.ROOT, "%02x", b & 255));
                    String key = "push-" + sha;
                    if (exists(key)) {
                        resolver.delete(uri, null, null); finished = true; return false;
                    }
                    ContentValues complete = new ContentValues(); complete.put(MediaStore.Images.Media.IS_PENDING, 0);
                    if (resolver.update(uri, complete, null, null) != 1) throw new IOException("无法完成相册保存");
                    finished = true;
                    index.edit().putString(key, uri.toString()).putString(key + ".sha256", sha.toString()).commit();
                    return true;
                }
                @Override public void close() {
                    try { output.close(); } catch (IOException ignored) { }
                    if (!finished) resolver.delete(uri, null, null);
                }
            };
        } catch (Exception e) { resolver.delete(uri, null, null); throw e; }
    }
    /** Remove this app's stale pending writes after process death; completed photos remain untouched. */
    public void cleanupPending() {
        String selection = MediaStore.Images.Media.IS_PENDING + "=1 AND " + MediaStore.Images.Media.RELATIVE_PATH + "=? AND "
            + MediaStore.Images.Media.DATE_ADDED + "<?";
        String[] args = {"Pictures/WB800F/", String.valueOf(System.currentTimeMillis() / 1000 - 3600)};
        try (Cursor c = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            new String[]{MediaStore.Images.Media._ID}, selection, args, null)) {
            if (c != null) while (c.moveToNext()) resolver.delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), null, null);
        } catch (Exception ignored) { }
    }
}
