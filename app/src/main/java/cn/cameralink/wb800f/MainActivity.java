package cn.cameralink.wb800f;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int TEAL = Color.rgb(0, 125, 118), INK = Color.rgb(23, 43, 45), MUTED = Color.rgb(91, 111, 113);
    private TransferService service;
    private boolean bound;
    private TextView status, detail, selection;
    private Button connect, manual, send, all, fresh, cancel;
    private ProgressBar progress;
    private ListView list;
    private PhotoAdapter adapter;
    private final Set<String> selected = new HashSet<>();
    private String pendingManual;
    private boolean awaitingConnection;
    private final TransferService.Listener listener = this::refresh;
    private final ServiceConnection binding = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TransferService.LocalBinder) binder).service(); service.listen(listener);
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; refresh(); }
    };
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private Button button(String title, boolean primary) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : TEAL); b.setMinHeight(dp(46)); b.setMinimumHeight(dp(46));
        b.setPadding(dp(10), dp(3), dp(10), dp(3)); b.setBackground(background(primary ? TEAL : Color.rgb(227, 241, 237), 12));
        return b;
    }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private void addButton(LinearLayout row, Button b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1); p.setMargins(dp(3), 0, dp(3), 0); row.addView(b, p);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { ArrayList<String> keys = state.getStringArrayList("selected"); if (keys != null) selected.addAll(keys); }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(10), dp(18), dp(8)); root.setBackgroundColor(Color.rgb(243, 246, 245));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(dp(18) + bars.left, dp(10) + bars.top, dp(18) + bars.right, dp(8) + bars.bottom);
            } else {
                view.setPadding(dp(18) + insets.getSystemWindowInsetLeft(), dp(10) + insets.getSystemWindowInsetTop(),
                    dp(18) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout heading = row();
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("WB800F", 28, INK, true)); titles.addView(text("照片传输  /  CAMERA LINK", 12, MUTED, false));
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        Button help = button("使用帮助", false); heading.addView(help, new LinearLayout.LayoutParams(dp(90), dp(42))); help.setOnClickListener(v -> help());
        root.addView(heading); Space gap = new Space(this); root.addView(gap, new LinearLayout.LayoutParams(1, dp(14)));
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(14), dp(16), dp(14)); card.setBackground(background(Color.WHITE, 18));
        status = text("等待连接相机", 17, INK, true); detail = text("先在相机上打开 Wi-Fi → MobileLink", 13, MUTED, false);
        detail.setPadding(0, dp(7), 0, dp(10)); detail.setMaxLines(4);
        card.addView(status); card.addView(detail);
        LinearLayout controls = row(); Button wifi = button("Wi-Fi 设置", false); connect = button("连接 / 刷新", true);
        addButton(controls, wifi); addButton(controls, connect); card.addView(controls);
        wifi.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        connect.setOnClickListener(v -> requestConnect(null));
        LinearLayout secondary = row(); manual = button("手动连接", false); Button diagnostics = button("连接诊断", false);
        LinearLayout.LayoutParams secondarySpace = new LinearLayout.LayoutParams(-1, -2); secondarySpace.topMargin = dp(8);
        addButton(secondary, manual); addButton(secondary, diagnostics); card.addView(secondary, secondarySpace);
        manual.setOnClickListener(v -> manual()); diagnostics.setOnClickListener(v -> diagnostics());
        root.addView(card);
        LinearLayout summary = row(); summary.setPadding(0, dp(12), 0, dp(8));
        selection = text("相机照片", 14, INK, true); summary.addView(selection, new LinearLayout.LayoutParams(0, -2, 1));
        all = button("全选", false); fresh = button("仅选未传", false);
        summary.addView(all, new LinearLayout.LayoutParams(dp(60), dp(38))); summary.addView(fresh, new LinearLayout.LayoutParams(dp(98), dp(38)));
        root.addView(summary);
        all.setOnClickListener(v -> {
            if (service == null) return;
            if (selected.size() == service.photos.size()) selected.clear();
            else { selected.clear(); for (CameraProtocol.Photo p : service.photos) selected.add(service.key(p)); }
            refresh();
        });
        fresh.setOnClickListener(v -> { if (service == null) return; selected.clear(); for (CameraProtocol.Photo p : service.photos) if (!service.saved(p)) selected.add(service.key(p)); refresh(); });
        FrameLayout gallery = new FrameLayout(this); list = new ListView(this);
        list.setDivider(null); list.setDividerHeight(dp(6)); list.setChoiceMode(ListView.CHOICE_MODE_NONE);
        list.setBackgroundColor(Color.TRANSPARENT); adapter = new PhotoAdapter(); list.setAdapter(adapter);
        TextView empty = text("让相机里的照片\n回到手机相册\n\n1  相机打开 MobileLink\n2  手机连接相机 Wi-Fi\n3  点「连接 / 刷新」", 16, MUTED, false);
        empty.setGravity(Gravity.CENTER); empty.setLineSpacing(dp(5), 1);
        gallery.addView(list, new FrameLayout.LayoutParams(-1, -1)); gallery.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        root.addView(gallery, new LinearLayout.LayoutParams(-1, 0, 1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(5)));
        LinearLayout footer = row(); footer.setPadding(0, dp(10), 0, dp(4));
        send = button("传输所选照片", true); cancel = button("取消", false);
        footer.addView(send, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(72), dp(50)); cp.leftMargin = dp(8); footer.addView(cancel, cp); root.addView(footer);
        send.setOnClickListener(v -> {
            if (service == null || selected.isEmpty()) return;
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
            service.transfer(new ArrayList<>(selected)); refresh();
        });
        cancel.setOnClickListener(v -> { if (service != null) service.cancel(); });
        TextView note = text("保存到相册 · Pictures/WB800F · 原照片字节不改动", 11, MUTED, false); note.setGravity(Gravity.CENTER); root.addView(note);
        setContentView(root); root.requestApplyInsets(); refresh();
    }
    @Override protected void onStart() {
        super.onStart(); bound = bindService(new Intent(this, TransferService.class), binding, BIND_AUTO_CREATE);
    }
    @Override protected void onStop() {
        if (service != null) service.unlisten(listener);
        if (bound) { unbindService(binding); bound = false; } service = null; super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putStringArrayList("selected", new ArrayList<>(selected)); super.onSaveInstanceState(out); }
    private void refresh() {
        if (adapter == null) return;
        TransferService s = service;
        boolean busy = s != null && s.busy(), connected = s != null && s.connected;
        if (s != null) { status.setText(s.status); detail.setText(s.detail); progress.setProgress(s.progress); }
        connect.setEnabled(!busy && s != null); manual.setEnabled(!busy && s != null);
        all.setEnabled(connected && !busy && !s.photos.isEmpty()); fresh.setEnabled(all.isEnabled());
        send.setEnabled(connected && !busy && !selected.isEmpty()); cancel.setEnabled(busy);
        send.setAlpha(send.isEnabled() ? 1 : 0.45f); cancel.setAlpha(busy ? 1 : 0.45f);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        selection.setText(s == null || s.photos.isEmpty() ? "相机照片" : "已选 " + selected.size() + " / " + s.photos.size() + " 张");
        send.setText(selected.isEmpty() ? "传输所选照片" : "传输 " + selected.size() + " 张照片");
        adapter.notifyDataSetChanged();
    }
    private void requestConnect(String manual) {
        if (service == null || service.busy()) return;
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            pendingManual = manual; awaitingConnection = true;
            requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"}, 101); return;
        }
        selected.clear(); adapter.clear(); service.connect(manual);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 101 && awaitingConnection) {
            awaitingConnection = false;
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) requestConnect(pendingManual);
            else new AlertDialog.Builder(this).setTitle("需要局域网访问权限")
                .setMessage("照片来自相机 Wi-Fi。请在应用权限中允许「附近设备 / 本地网络」，然后重新连接。")
                .setPositiveButton("应用设置", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("返回", null).show();
        }
    }
    private void manual() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextSize(15);
        input.setHint("192.168.107.1 或设备描述 URL"); input.setPadding(dp(20), dp(12), dp(20), dp(12));
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE); input.setText(prefs.getString("manual", ""));
        new AlertDialog.Builder(this).setTitle("手动连接相机").setMessage("自动连接失败时，可输入相机 IPv4 地址（支持 IP:端口），或完整 UPnP 设备描述 URL。相机地址可在已连接 Wi-Fi 的网关信息中查看。")
            .setView(input).setPositiveButton("连接", (dialog, which) -> {
                String value = input.getText().toString().trim();
                if (!value.isEmpty()) { prefs.edit().putString("manual", value).apply(); requestConnect(value); }
            }).setNegativeButton("取消", null).show();
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("WB800F 照片传输")
            .setMessage("1. 相机拨盘切换到 Wi-Fi，打开 MobileLink。若出现选项，请选择「从智能手机选择文件」。\n\n2. 点「Wi-Fi 设置」，连接相机显示的 AP_SSC_WB800F… 网络。提示没有互联网时选择保持连接。\n\n3. 回到本应用，点「连接 / 刷新」。安卓 17 提示附近设备 / 本地网络权限时允许；相机出现连接请求时，在相机上点「允许」。\n\n4. 选照片或「仅选未传」，点底部传输按钮。照片保存在 Pictures/WB800F，相册可能需几秒刷新。\n\n请保持相机开启。锁屏后传输可继续；相机省电关机或 Wi-Fi 断开会导致失败，可重新连接再传。已成功的照片会跳过，取消时当前未完成照片会清理。\n\n连接失败：先确认 MobileLink 模式，再暂时关闭 VPN / 自动切换到移动网络。仍有问题可导出诊断。\n\n本应用为独立开发的照片接收工具，无账号，无广告，不上传照片。WB800F 实机与安卓 17 手机连接仍待用户验证。版本 " + TransferService.version(this) + "。")
            .setPositiveButton("知道了", null).show();
    }
    private void diagnostics() {
        String value = service == null ? "服务未连接" : service.logs();
        TextView output = text(value, 12, INK, false); output.setTextIsSelectable(true); output.setTypeface(Typeface.MONOSPACE);
        output.setPadding(dp(16), dp(12), dp(16), dp(12)); ScrollView scroll = new ScrollView(this); scroll.addView(output);
        new AlertDialog.Builder(this).setTitle("连接诊断").setView(scroll).setPositiveButton("导出日志", (d, w) -> share(value))
            .setNeutralButton("复制", (d, w) -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("WB800F diagnostics", value));
                Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("关闭", null).show();
    }
    private void share(String value) {
        try {
            File file = new File(getCacheDir(), "diagnostic.txt");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
            Uri uri = Uri.parse("content://cn.cameralink.wb800f.logs/diagnostic.txt");
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("diagnostics", uri));
            startActivity(Intent.createChooser(share, "导出 WB800F 诊断"));
        } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private final class PhotoAdapter extends BaseAdapter {
        private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
        };
        private final Set<String> requested = new HashSet<>();
        void clear() { cache.evictAll(); requested.clear(); }
        @Override public int getCount() { return service == null ? 0 : service.photos.size(); }
        @Override public Object getItem(int position) { return service.photos.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reuse, android.view.ViewGroup parent) {
            final CameraProtocol.Photo photo = (CameraProtocol.Photo) getItem(position);
            final String key = service.key(photo); final boolean busy = service.busy();
            LinearLayout cell = reuse instanceof LinearLayout ? (LinearLayout) reuse : row();
            if (reuse == null) {
                cell.setPadding(dp(10), dp(10), dp(10), dp(10)); cell.setBackground(background(Color.WHITE, 14));
                ImageView image = new ImageView(MainActivity.this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackground(background(Color.rgb(232, 239, 236), 10)); cell.addView(image, new LinearLayout.LayoutParams(dp(64), dp(64)));
                LinearLayout labels = new LinearLayout(MainActivity.this); labels.setOrientation(LinearLayout.VERTICAL); labels.setPadding(dp(12), 0, dp(5), 0);
                TextView title = text("", 15, INK, true); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); labels.addView(title);
                TextView description = text("", 12, MUTED, false); description.setMaxLines(2); description.setPadding(0, dp(5), 0, 0); labels.addView(description);
                cell.addView(labels, new LinearLayout.LayoutParams(0, -2, 1)); CheckBox check = new CheckBox(MainActivity.this); cell.addView(check);
            }
            ImageView image = (ImageView) cell.getChildAt(0); image.setTag(key); Bitmap bitmap = cache.get(key);
            if (bitmap != null) image.setImageBitmap(bitmap); else image.setImageResource(R.drawable.ic_camera);
            LinearLayout labels = (LinearLayout) cell.getChildAt(1); ((TextView) labels.getChildAt(0)).setText(photo.title);
            String info = photo.date.isEmpty() ? "" : photo.date.replace('T', ' ').substring(0, Math.min(16, photo.date.length())) + "\n";
            info += photo.size > 0 ? TransferService.formatSize(photo.size) : "原图";
            if (photo.resolution != null && !photo.resolution.isEmpty()) info += " · " + photo.resolution;
            if (service.saved(photo)) info += " · 已保存";
            ((TextView) labels.getChildAt(1)).setText(info);
            CheckBox check = (CheckBox) cell.getChildAt(2); check.setOnCheckedChangeListener(null); check.setChecked(selected.contains(key)); check.setEnabled(!busy);
            check.setContentDescription("选择 " + photo.title);
            check.setOnCheckedChangeListener((button, checked) -> { if (checked) selected.add(key); else selected.remove(key); refresh(); });
            cell.setOnClickListener(v -> { if (!busy) check.setChecked(!check.isChecked()); });
            if (photo.thumb != null && bitmap == null && !busy && requested.add(key)) service.thumbnail(photo, result -> {
                if (result != null) cache.put(key, result); else requested.remove(key);
                if (key.equals(image.getTag()) && result != null) image.setImageBitmap(result);
            });
            return cell;
        }
    }
}
