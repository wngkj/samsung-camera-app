package cn.cameralink.wb800f;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only sharing of exactly one diagnostic file, granted only by the Android share chooser. */
public final class LogProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        if (!"/diagnostic.txt".equals(uri.getPath())) throw new FileNotFoundException("Unknown log");
        return new File(getContext().getCacheDir(), "diagnostic.txt");
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor c = new MatrixCursor(columns); Object[] values = new Object[columns.length];
        try {
            File f = file(uri);
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "WB800F-诊断.txt";
                if (OpenableColumns.SIZE.equals(columns[i])) values[i] = f.length();
            }
            c.addRow(values);
        } catch (FileNotFoundException ignored) { }
        return c;
    }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
