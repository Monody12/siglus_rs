package com.chino.siglus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Dedicated-game bootstrap, modelled after the yosuga-no-sora-remake shell:
 * launch goes straight into the game whenever the data directory is ready.
 * First run without data offers (a) an in-app downloader that pulls split zips
 * from the private clannad-data GitHub release (user-pasted read-only token,
 * stored app-private, never shipped inside the APK) or (b) a local-folder
 * SAF import fallback.
 */
public class ClannadBootstrapActivity extends Activity {

    private static final String DEFAULT_DIR = "ClannadData";
    private static final String DATA_REPO = "Monody12/clannad-data";
    private static final String MANIFEST_ASSET = "data-assets.json";
    private static final String PREFS = "data_setup";
    private static final String KEY_CONFIRMED_VERSION = "confirmed_version";
    private static final String KEY_TOKEN = "github_token";
    private static final int REQUEST_PICK = 41;

    /** Saved games live inside the data root; never delete them on re-import. */
    private static final String[] PRESERVE = {"SAVEDATA", "savedata_zh", "savedata", ".rlvm"};

    private static final long CHUNK = 8L * 1024 * 1024;
    private static final int WORKERS = 6;
    private static final int RETRIES = 3;

    private static volatile boolean busy = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private File dataDir;
    private EditText tokenBox;
    private TextView status;
    private TextView progressText;
    private ProgressBar progress;
    private Button downloadButton;
    private long downloadStartMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        dataDir = new File(Environment.getExternalStorageDirectory(), DEFAULT_DIR);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefillTokenFromFile();
        if (!busy) {
            probe();
        }
    }

    /** Convenience: /sdcard/clannad_token.txt prefills the token box (no typing). */
    private void prefillTokenFromFile() {
        try {
            File f = new File(Environment.getExternalStorageDirectory(), "clannad_token.txt");
            if (f.isFile()) {
                String t = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8).trim();
                if (!t.isEmpty() && tokenBox != null) {
                    tokenBox.setText(t);
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---- probe / version gate -------------------------------------------

    private void probe() {
        if (!Environment.isExternalStorageManager()) {
            showSetup("需要\"所有文件访问\"权限,点击\"检测数据\"后授权。");
            return;
        }
        if (isReady(dataDir)) {
            maybeConfirmUpdate();
        } else {
            showSetup("未检测到游戏数据。可自动下载(约 4.3GB),或从本机导入。");
        }
    }

    private static boolean isReady(File root) {
        return root != null && new File(root, "Gameexe.dat").isFile();
    }

    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    private void maybeConfirmUpdate() {
        int confirmed = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_CONFIRMED_VERSION, -1);
        if (confirmed == versionCode()) {
            startGame();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("游戏数据")
                .setMessage("检测到已有游戏数据,但 APK 已更新。\n\n重新导入将删除旧数据(存档会保留)。")
                .setCancelable(false)
                .setPositiveButton("使用旧数据", (d, w) -> markConfirmedAndStart())
                .setNegativeButton("重新导入", (d, w) -> {
                    deleteTreeKeepingSaves(dataDir);
                    showSetup("旧数据已清除。输入 token 重新下载,或从本机导入。");
                })
                .show();
    }

    private void markConfirmedAndStart() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putInt(KEY_CONFIRMED_VERSION, versionCode()).apply();
        startGame();
    }

    private void startGame() {
        launchWith(dataDir.getAbsolutePath());
    }

    private void launchWith(String rootPath) {
        try {
            LaunchConfig.write(this, rootPath);
        } catch (Exception e) {
            Toast.makeText(this, "写入启动配置失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, SiglusGameActivity.class));
        finish();
    }

    /** Delete the data tree but keep save directories (and engine config). */
    private void deleteTreeKeepingSaves(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            boolean keep = false;
            for (String p : PRESERVE) {
                if (f.getName().equalsIgnoreCase(p)) { keep = true; break; }
            }
            if (keep) continue;
            if (f.isDirectory()) {
                deleteTreeKeepingSaves(f);
            }
            f.delete();
        }
    }

    // ---- UI --------------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("CLANNAD HD");
        title.setTextSize(26f);
        title.setGravity(android.view.Gravity.CENTER);

        status = new TextView(this);
        status.setTextSize(14f);
        status.setPadding(0, pad, 0, pad);
        status.setGravity(android.view.Gravity.CENTER);

        tokenBox = new EditText(this);
        tokenBox.setHint("GitHub token(仅 clannad-data 仓库只读)");
        tokenBox.setTextSize(13f);
        // Password-style input: disables IME autocorrect/auto-capitalize, which
        // would otherwise mangle a 93-character token (and masks it on screen).
        tokenBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenBox.setTypeface(android.graphics.Typeface.MONOSPACE);

        downloadButton = new Button(this);
        downloadButton.setText("下载游戏数据(约 4.3GB)");
        downloadButton.setOnClickListener(v -> startDownload());

        Button pick = new Button(this);
        pick.setText("从本机目录导入…");
        pick.setOnClickListener(v -> {
            try {
                startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK);
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开目录选择器", Toast.LENGTH_SHORT).show();
            }
        });

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(10000);
        progress.setPadding(0, pad, 0, 0);

        progressText = new TextView(this);
        progressText.setTextSize(13f);
        progressText.setGravity(android.view.Gravity.CENTER);

        root.addView(title);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(tokenBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(downloadButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(pick, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(progressText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, "");
        if (!saved.isEmpty()) {
            tokenBox.setText(saved);
        }
    }

    private void showSetup(String message) {
        status.setText(message);
        downloadButton.setEnabled(!busy);
        // 断点续传:检测到暂存进度时提示"继续"
        File staging = new File(dataDir.getParentFile(), dataDir.getName() + ".dl");
        File[] leftovers = staging.listFiles();
        boolean hasProgress = leftovers != null && leftovers.length > 0;
        downloadButton.setText(hasProgress ? "继续下载数据(已有进度)" : "下载游戏数据(约 4.3GB)");
    }

    private void setBusy(boolean b, String message) {
        busy = b;
        downloadButton.setEnabled(!b);
        tokenBox.setEnabled(!b);
        status.setText(message);
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        progressText.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    // ---- download pipeline -----------------------------------------------

    private void startDownload() {
        String token = tokenBox.getText().toString().trim();
        if (token.isEmpty()) {
            Toast.makeText(this, "请先粘贴 GitHub token(见 README 获取步骤)", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply();
        if (!Environment.isExternalStorageManager()) {
            requestStoragePermission();
            return;
        }
        setBusy(true, "获取数据清单…");
        DataDownloadService.start(this);
        downloadStartMs = System.currentTimeMillis();
        new Thread(() -> {
            try {
                runPipeline(token);
            } catch (Exception e) {
                fail("下载失败: " + e.getMessage());
            }
        }, "clannad-data-dl").start();
    }

    private void fail(String message) {
        ui.post(() -> {
            setBusy(false, message);
            progressText.setText("");
        });
        DataDownloadService.stop(this);
    }

    private void done() {
        DataDownloadService.stop(this);
        ui.post(this::markConfirmedAndStart);
    }

    private void runPipeline(String token) throws Exception {
        JSONObject release = httpJson(
                "https://api.github.com/repos/" + DATA_REPO + "/releases/latest", token);
        org.json.JSONArray assets = release.getJSONArray("assets");
        JSONObject manifestAsset = findAsset(assets, MANIFEST_ASSET);
        if (manifestAsset == null) {
            throw new IOException("release 中没有 " + MANIFEST_ASSET);
        }
        byte[] raw = httpGetAsset(manifestAsset.getLong("id"), token, null, 0, null);
        JSONObject manifest = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        org.json.JSONArray parts = manifest.getJSONArray("assets");
        long grandTotal = manifest.optLong("totalSize", -1);
        AtomicLong doneBytes = new AtomicLong();

        File tmpRoot = new File(dataDir.getParentFile(), dataDir.getName() + ".dl");
        // 不清空暂存目录 —— 断点续传依赖其中的 .part/.done
        tmpRoot.mkdirs();

        List<JSONObject> partList = new ArrayList<>();
        for (int i = 0; i < parts.length(); i++) partList.add(parts.getJSONObject(i));

        for (int p = 0; p < partList.size(); p++) {
            JSONObject part = partList.get(p);
            String name = part.getString("name");
            long size = part.getLong("size");
            String sha = part.optString("sha256", "");
            File zip = new File(tmpRoot, name);
            File marker = new File(tmpRoot, name + ".unzipped");
            if (marker.isFile()) {
                continue; // 该卷下载+解压均已完成
            }
            if (zip.isFile() && zip.length() == size && sha256Equals(zip, sha)) {
                post("解压(续) " + name);
                unzipInto(zip, tmpRoot);
                marker.createNewFile();
                zip.delete();
                continue;
            }
            JSONObject remote = findAsset(assets, name);
            if (remote == null) throw new IOException("release 缺少分卷 " + name);
            post("下载 " + name + " (" + (p + 1) + "/" + partList.size() + ")");
            downloadInParallel(remote.getLong("id"), token, zip, size, grandTotal, doneBytes);
            post("校验 " + name);
            if (!sha.isEmpty() && !sha256Equals(zip, sha)) {
                throw new IOException(name + " SHA-256 校验失败(分卷已保留,重试可续传)");
            }
            post("解压 " + name);
            unzipInto(zip, tmpRoot);
            marker.createNewFile();
            zip.delete();
        }

        // Atomically swap into place, preserving any existing saves.
        if (dataDir.exists()) {
            File old = new File(dataDir.getParentFile(), dataDir.getName() + ".old");
            deleteTree(old);
            dataDir.renameTo(old);
            tmpRoot.renameTo(dataDir);
            // carry saves/config from the old tree, then drop it
            for (String p : PRESERVE) {
                File src = new File(old, p);
                if (src.exists()) {
                    moveTree(src, new File(dataDir, p));
                }
            }
            deleteTree(old);
        } else {
            if (!tmpRoot.renameTo(dataDir)) {
                throw new IOException("无法安装数据目录");
            }
        }
        ensureNoMedia(new File(dataDir, "SAVEDATA"));
        done();
    }

    private JSONObject findAsset(org.json.JSONArray assets, String name) throws Exception {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (name.equals(a.getString("name"))) return a;
        }
        return null;
    }

    private void post(String message) {
        ui.post(() -> status.setText(message));
    }

    // ---- multi-thread ranged download ------------------------------------

    private void downloadInParallel(long assetId, String token, File dest,
                                    long total, long grandTotal, AtomicLong doneBytes) throws Exception {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        File ledger = new File(dest.getParentFile(), dest.getName() + ".done");
        int chunks = (int) ((total + CHUNK - 1) / CHUNK);
        // 断点续传账本:每完成一块记一行块号;重启时跳过已完成的块。
        java.util.Set<Integer> doneSet = new java.util.HashSet<>();
        if (ledger.isFile() && tmp.isFile() && tmp.length() == total) {
            try (InputStream in = new FileInputStream(ledger)) {
                StringBuilder sb = new StringBuilder();
                int b;
                while ((b = in.read()) > 0) {
                    if (b == '\n') {
                        try { doneSet.add(Integer.parseInt(sb.toString().trim())); } catch (NumberFormatException ignored) {}
                        sb.setLength(0);
                    } else {
                        sb.append((char) b);
                    }
                }
                try { if (sb.length() > 0) doneSet.add(Integer.parseInt(sb.toString().trim())); } catch (NumberFormatException ignored) {}
            }
        } else {
            tmp.delete();
            ledger.delete();
        }
        List<Integer> missing = new ArrayList<>();
        long preBytes = 0;
        for (int i = 0; i < chunks; i++) {
            if (doneSet.contains(i)) {
                preBytes += Math.min(CHUNK, total - (long) i * CHUNK);
            } else {
                missing.add(i);
            }
        }
        java.io.BufferedOutputStream ledgerOut = new java.io.BufferedOutputStream(
                new FileOutputStream(ledger, true));
        final File tmpFinal = tmp;
        final int chunksFinal = chunks;
        final long preBytesFinal = preBytes;
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicLong partDone = new AtomicLong();
        AtomicReference<IOException> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < WORKERS; w++) {
            Thread t = new Thread(() -> {
                try {
                    int i;
                    while ((i = cursor.getAndIncrement()) < missing.size()) {
                        int chunkNo = missing.get(i);
                        long start = (long) chunkNo * CHUNK;
                        long end = Math.min(total - 1, start + CHUNK - 1);
                        IOException err = null;
                        for (int attempt = 0; attempt < RETRIES; attempt++) {
                            try {
                                httpRangeTo(tmpFinal, assetId, token, start, end, chunksFinal == 1);
                                synchronized (ledgerOut) {
                                    ledgerOut.write((chunkNo + "\n").getBytes(StandardCharsets.UTF_8));
                                    ledgerOut.flush();
                                }
                                partDone.addAndGet(end - start + 1);
                                publishProgress(doneBytes, grandTotal, partDone, preBytesFinal);
                                err = null;
                                break;
                            } catch (IOException ioe) {
                                err = ioe;
                                Thread.sleep(2000);
                            }
                        }
                        if (err != null) throw err;
                    }
                } catch (Exception e) {
                    failure.compareAndSet(null,
                            e instanceof IOException ? (IOException) e : new IOException(e));
                }
            }, "dl-" + w);
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) t.join();
        ledgerOut.close();
        if (failure.get() != null) throw failure.get();   // 保留 .part/.done,下次继续
        ledger.delete();
        if (!tmp.renameTo(dest)) throw new IOException("无法完成 " + dest.getName());
    }

    private void publishProgress(AtomicLong doneBytes, long grandTotal,
                                 AtomicLong partDone, long preBytes) {
        long now = doneBytes.get() + partDone.get() + preBytes;
        ui.post(() -> {
            int pct = grandTotal > 0 ? (int) Math.min(10000L, now * 10000L / grandTotal) : 0;
            progress.setProgress(pct);
            long secs = Math.max(1, (System.currentTimeMillis() - downloadStartMs) / 1000);
            progressText.setText(String.format("下载 %.1f%%  ·  %.1f MB/s",
                    pct / 100.0, now / 1048576.0 / secs));
        });
    }

    /** Strict range fetch. `allow200` only for single-chunk assets where the
     *  server may ignore Range and return the whole body. */
    private void httpRangeTo(File dest, long assetId, String token,
                             long start, long end, boolean allow200) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(assetUrl(assetId)).openConnection();
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/octet-stream");
        c.setRequestProperty("Range", "bytes=" + start + "-" + end);
        c.setRequestProperty("User-Agent", "ClannadHD/1.0");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        // 多块下载必须 206:若服务器忽略 Range 返回 200(整卷内容),
        // 写入偏移会毁掉文件布局 —— 视为可重试错误。
        if (code != 206 && !(allow200 && code == 200)) {
            c.disconnect();
            throw new IOException("HTTP " + code + " (期望 206) on range " + start);
        }
        try (InputStream in = c.getInputStream();
             java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dest, "rw")) {
            java.nio.channels.FileChannel ch = raf.getChannel();
            byte[] buf = new byte[1 << 16];
            long pos = start;
            int n;
            while ((n = in.read(buf)) > 0) {
                ch.write(java.nio.ByteBuffer.wrap(buf, 0, n), pos);
                pos += n;
            }
            if (pos != end + 1) {
                throw new IOException("range length mismatch got " + (pos - start));
            }
        } finally {
            c.disconnect();
        }
    }

    private String assetUrl(long assetId) {
        return "https://api.github.com/repos/" + DATA_REPO + "/releases/assets/" + assetId;
    }

    private JSONObject httpJson(String url, String token) throws Exception {
        byte[] raw = httpGetAsset(-1, token, url, 0, null);
        return new JSONObject(new String(raw, StandardCharsets.UTF_8));
    }

    /** Simple full GET; assetId >= 0 → release asset endpoint (octet-stream). */
    private byte[] httpGetAsset(long assetId, String token, String plainUrl,
                                long hintSize, File unused) throws IOException {
        URL u = new URL(assetId >= 0 ? assetUrl(assetId) : plainUrl);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        c.setRequestProperty(assetId >= 0 ? "Accept" : "Accept",
                assetId >= 0 ? "application/octet-stream" : "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "ClannadHD/1.0");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IOException("HTTP " + code + " for " + u);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        return out.toByteArray();
    }

    private boolean sha256Equals(File f, String expected) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString().equalsIgnoreCase(expected);
    }

    // ---- unzip -------------------------------------------------------------

    private void unzipInto(File zip, File targetRoot) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            var entries = zf.entries();
            byte[] buf = new byte[1 << 16];
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name.endsWith("/")) continue;
                if (name.equals(MANIFEST_ASSET)) continue; // 卷内进度小清单
                String rel = name.startsWith("data/") ? name.substring(5) : name;
                File out = new File(targetRoot, rel);
                if (!out.getCanonicalPath().startsWith(targetRoot.getCanonicalPath())) {
                    continue; // zip-slip 防护
                }
                out.getParentFile().mkdirs();
                try (InputStream in = zf.getInputStream(e);
                     OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }

    private void ensureNoMedia(File dir) {
        try {
            dir.mkdirs();
            new File(dir, ".nomedia").createNewFile();
        } catch (Exception ignored) {
        }
    }

    /** Full recursive delete — only for staging dirs, never for the live data root. */
    private void deleteTree(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) deleteTree(f);
            f.delete();
        }
    }

    private void moveTree(File src, File dst) throws IOException {
        if (src.renameTo(dst)) return;
        dst.mkdirs();
        File[] children = src.listFiles();
        if (children == null) return;
        for (File f : children) {
            File target = new File(dst, f.getName());
            if (f.isDirectory()) moveTree(f, target);
            else if (!f.renameTo(target)) {
                try (FileInputStream in = new FileInputStream(f);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                f.delete();
            }
        }
        src.delete();
    }

    // ---- misc --------------------------------------------------------------

    private void requestStoragePermission() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Toast.makeText(this, "请在系统设置中授予\"所有文件访问\"权限", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String path = uri.getPath();
        if (path != null && path.contains(":")) {
            path = new File(Environment.getExternalStorageDirectory(),
                    path.substring(path.indexOf(':') + 1)).getAbsolutePath();
        }
        if (path != null && isReady(new File(path))) {
            writeLaunchAndStart(path);
        } else {
            Toast.makeText(this, "所选目录缺少 Gameexe.dat", Toast.LENGTH_LONG).show();
        }
    }

    private void writeLaunchAndStart(String rootPath) {
        launchWith(rootPath);
    }
}
