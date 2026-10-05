package cn.cameralink.wb800f.tests;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import cn.cameralink.wb800f.*;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Separate test APK; not shipped inside the main application. Exercises real API 37 MediaStore + FGS. */
public final class SmokeInstrumentation extends Instrumentation {
    private MainActivity activity;
    private final StringBuilder report = new StringBuilder();
    private String url;
    @Override public void onCreate(Bundle args) { super.onCreate(args); url = args.getString("cameraUrl", "http://10.0.2.2:8765/description.xml"); start(); }
    private void note(String text) { report.append("PASS ").append(text).append('\n'); Bundle b = new Bundle(); b.putString("stream", text + "\n"); sendStatus(0, b); }
    private void waitFor(BooleanSupplier f, String message) throws Exception {
        long until = SystemClock.elapsedRealtime() + 180000;
        while (!f.getAsBoolean()) { if (SystemClock.elapsedRealtime() > until) throw new AssertionError(message); Thread.sleep(200); }
    }
    private TransferService service() {
        try { Field f = MainActivity.class.getDeclaredField("service"); f.setAccessible(true); return (TransferService) f.get(activity); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void connect() {
        runOnMainSync(() -> {
            try { Method m = MainActivity.class.getDeclaredMethod("requestConnect", String.class); m.setAccessible(true); m.invoke(activity, url); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private Button find(View v, String label) {
        if (v instanceof Button && label.equals(((Button) v).getText().toString())) return (Button) v;
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) { Button b = find(g.getChildAt(i), label); if (b != null) return b; } }
        return null;
    }
    private void click(String label) {
        runOnMainSync(() -> { Button b = find(activity.getWindow().getDecorView(), label); if (b == null || !b.isEnabled()) throw new AssertionError("Button unavailable: " + label); b.performClick(); });
    }
    private AccessibilityNodeInfo permission(AccessibilityNodeInfo root, boolean allow) {
        if (root == null) return null;
        String id = root.getViewIdResourceName(); CharSequence text = root.getText();
        if (id != null && id.endsWith(allow ? "/permission_allow_button" : "/permission_deny_button")) return root;
        if (text != null && (allow ? text.toString().equals("Allow") : text.toString().equals("Don't allow"))) return root;
        for (int i = 0; i < root.getChildCount(); i++) { AccessibilityNodeInfo n = permission(root.getChild(i), allow); if (n != null) return n; }
        return null;
    }
    private void clickPermission(boolean allow) throws Exception {
        final AccessibilityNodeInfo[] node = {null};
        waitFor(() -> (node[0] = permission(getUiAutomation().getRootInActiveWindow(), allow)) != null, "No permission prompt");
        if (!node[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Permission click failed");
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new IOException("No screenshot");
        try (OutputStream out = new FileOutputStream(new File(getContext().getFilesDir(), name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    private List<Uri> images() {
        List<Uri> out = new ArrayList<>();
        String selection = MediaStore.Images.Media.RELATIVE_PATH + "=? AND " + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ? AND " + MediaStore.Images.Media.IS_PENDING + "=0";
        try (Cursor c = getTargetContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            new String[]{MediaStore.Images.Media._ID}, selection, new String[]{"Pictures/WB800F/", "DEMO_%.jpg"}, null)) {
            if (c != null) while (c.moveToNext()) out.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
        }
        return out;
    }
    private byte[] digest(InputStream input) throws Exception {
        try (InputStream in = input) { MessageDigest sha = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) sha.update(b, 0, n); return sha.digest(); }
    }
    private void verifyBytes() throws Exception {
        for (Uri uri : images()) {
            String name;
            try (Cursor c = getTargetContext().getContentResolver().query(uri, new String[]{MediaStore.Images.Media.DISPLAY_NAME}, null, null, null)) { if (c == null || !c.moveToFirst()) throw new AssertionError("Missing image"); name = c.getString(0); }
            if (!Arrays.equals(digest(getTargetContext().getContentResolver().openInputStream(uri)), digest(getContext().getAssets().open(name)))) throw new AssertionError("Photo SHA256 mismatch: " + name);
        }
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_OK;
        try {
            if (Build.VERSION.SDK_INT != 37) throw new AssertionError("Expected Android API 37");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitFor(() -> service() != null, "Service did not bind"); screenshot("01-home"); note("Android 17 API 37 install, launch and service bind");
            connect();
            waitFor(() -> permission(getUiAutomation().getRootInActiveWindow(), true) != null, "Local network permission prompt missing");
            screenshot("02-local-network-permission"); clickPermission(true); note("ACCESS_LOCAL_NETWORK runtime permission prompt and grant");
            waitFor(() -> service() != null && service().connected && !service().busy(), "Camera browse failed");
            TransferService s = service();
            if (s.photos.size() != 3) throw new AssertionError("Expected 3 photos: " + s.status + " / " + s.detail);
            screenshot("03-photo-list"); note("Wi-Fi-bound HTTP, service parsing, GENA subscription and paginated photo list");
            click("仅选未传"); click("传输 3 张照片");
            clickPermission(false); note("Transfer allowed while notification permission denied");
            waitFor(() -> !s.busy() && !s.transferring, "Transfer did not finish");
            if (images().size() != 3 || !s.status.equals("传输完成")) throw new AssertionError("Import failed: " + s.status + " / " + s.detail + " / " + images().size());
            verifyBytes(); screenshot("04-transfer-complete"); note("Three MediaStore imports; SHA256 equals original fixture bytes");
            ArrayList<String> keys = new ArrayList<>(); for (CameraProtocol.Photo p : s.photos) keys.add(s.key(p));
            runOnMainSync(() -> s.transfer(keys)); waitFor(() -> !s.busy(), "Duplicate skip did not finish");
            if (images().size() != 3 || !s.detail.contains("已有 3 张")) throw new AssertionError("Duplicate imports");
            click("仅选未传"); note("Completed-photo duplicate skip and untransferred selection");
            // Delete only this simulator's imports to force a real in-flight cancellation.
            for (Uri uri : images()) getTargetContext().getContentResolver().delete(uri, null, null);
            ArrayList<String> one = new ArrayList<>(); one.add(keys.get(0));
            runOnMainSync(() -> s.transfer(one)); Thread.sleep(700); runOnMainSync(s::cancel);
            waitFor(() -> !s.busy(), "Cancel did not finish");
            if (!s.status.equals("传输已取消") || !images().isEmpty()) throw new AssertionError("Incomplete cancelled import was committed: " + s.status);
            note("In-flight cancellation rolls back current MediaStore import");
            connect(); waitFor(() -> service() != null && service().connected && !service().busy(), "Reconnect failed");
            TransferService again = service();
            runOnMainSync(() -> again.transfer(one)); getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            waitFor(() -> !again.busy(), "Background transfer did not finish");
            if (images().size() != 1) throw new AssertionError("Background import failed: " + again.status + " / " + again.detail);
            verifyBytes(); note("Foreground-service transfer completes after returning to Home");
            result.putString("stream", report.toString() + "ALL ANDROID 17 SMOKE CHECKS PASSED\n");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            StringWriter sw = new StringWriter(); error.printStackTrace(new PrintWriter(sw)); result.putString("stream", report + "FAIL " + sw);
            try { TransferService s = service(); if (s != null) result.putString("diagnostics", s.logs()); } catch (Throwable ignored) { }
        }
        try (Writer out = new OutputStreamWriter(new FileOutputStream(new File(getContext().getFilesDir(), "result.txt")), java.nio.charset.StandardCharsets.UTF_8)) { out.write(result.getString("stream")); }
        catch (Exception ignored) { }
        finish(code, result);
    }
}
