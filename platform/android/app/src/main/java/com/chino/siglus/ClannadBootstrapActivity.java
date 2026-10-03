package com.chino.siglus;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Dedicated-game bootstrap, modelled after the yosuga-no-sora-remake shell:
 * launching the app goes straight into the game whenever the data directory is
 * ready. Only a first-run without data shows a minimal one-tap setup page.
 */
public class ClannadBootstrapActivity extends Activity {

    /** Well-known location this app pushes its data to. */
    private static final String DEFAULT_DIR = "ClannadData";

    private static final int REQUEST_PICK = 41;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        probeAndLaunch();
    }

    private File savedRoot() {
        File f = new File(getFilesDir(), "SiglusLauncher/launch.json");
        if (!f.isFile()) {
            return null;
        }
        LaunchParams p = readLaunchJson(f);
        return p != null ? new File(p.gameRoot) : null;
    }

    private File defaultRoot() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DEFAULT_DIR);
        // Note: on this device the data lives at /sdcard/ClannadData; Downloads
        // fallback keeps the probe honest on other phones.
    }

    private void probeAndLaunch() {
        File root = savedRoot();
        if (!isReady(root)) {
            root = new File(Environment.getExternalStorageDirectory(), DEFAULT_DIR);
        }
        if (!isReady(root)) {
            root = defaultRoot();
        }
        if (isReady(root)) {
            LaunchConfig.write(this, root.getAbsolutePath());
            startGame();
        }
        // else: setup page is already visible.
    }

    private static boolean isReady(File root) {
        return root != null && new File(root, "Gameexe.dat").isFile();
    }

    private void startGame() {
        startActivity(new Intent(this, SiglusGameActivity.class));
        finish();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("CLANNAD HD");
        title.setTextSize(28f);
        title.setGravity(android.view.Gravity.CENTER);

        TextView hint = new TextView(this);
        hint.setText("未找到游戏数据。请把数据目录(ClannadData,含 Gameexe.dat)放到手机存储根目录,或手动选择。");
        hint.setTextSize(14f);
        hint.setPadding(0, pad, 0, pad);
        hint.setGravity(android.view.Gravity.CENTER);

        Button probe = new Button(this);
        probe.setText("检测手机存储中的数据并开始");
        probe.setOnClickListener((View v) -> {
            if (Environment.isExternalStorageManager()) {
                probeAndLaunch();
            } else {
                requestStoragePermission();
            }
        });

        Button pick = new Button(this);
        pick.setText("手动选择数据目录…");
        pick.setOnClickListener((View v) -> {
            try {
                startActivityForResult(
                        new android.content.Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK);
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开目录选择器", Toast.LENGTH_SHORT).show();
            }
        });

        root.addView(title);
        root.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(probe);
        root.addView(pick);
        setContentView(root);
    }

    private void requestStoragePermission() {
        // Mirrors the launcher: all-files access keeps the no-copy import model.
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
        if (isReady(new File(requireNonNullPath(uri)))) {
            LaunchConfig.write(this, requireNonNullPath(uri));
            startGame();
        } else {
            Toast.makeText(this, "所选目录缺少 Gameexe.dat", Toast.LENGTH_LONG).show();
        }
    }

    private String requireNonNullPath(Uri uri) {
        // SAF direct mapping, same approach as GameLibrary.tryResolveDirectRootPath.
        String path = uri.getPath();
        if (path != null && path.contains(":")) {
            String after = path.substring(path.indexOf(':') + 1);
            return new File(Environment.getExternalStorageDirectory(), after).getAbsolutePath();
        }
        return path != null ? path : "";
    }

    private LaunchParams readLaunchJson(File f) {
        try {
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            org.json.JSONObject o = new org.json.JSONObject(new String(all, java.nio.charset.StandardCharsets.UTF_8));
            String root = o.optString("game_root_utf8", "");
            return root.isEmpty() ? null : new LaunchParams(root);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class LaunchParams {
        final String gameRoot;

        LaunchParams(String gameRoot) {
            this.gameRoot = gameRoot;
        }
    }
}
