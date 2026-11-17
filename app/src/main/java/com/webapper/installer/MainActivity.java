package com.webapper.installer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The WebApper installer: enter a web address, Firefox add-on links and the install type, press
 * Create, and a new app (its own APK, running on the Firefox engine) is built on the device.
 */
public class MainActivity extends Activity implements InstallReceiver.Listener {
    private static final int REQ_SAVE_APK = 1;
    private static final int REQ_UNKNOWN_SOURCES = 2;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText url, name, extensions;
    private RadioButton portable;
    private CheckBox desktop, screenOn;
    private Button create;
    private ProgressBar progress;
    private TextView status;
    private LinearLayout apps;
    private Generator.Result pendingResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        url = findViewById(R.id.url);
        name = findViewById(R.id.name);
        extensions = findViewById(R.id.extensions);
        portable = findViewById(R.id.mode_portable);
        desktop = findViewById(R.id.desktop);
        screenOn = findViewById(R.id.screen_on);
        create = findViewById(R.id.create);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        apps = findViewById(R.id.apps);
        create.setOnClickListener(v -> onCreateClicked());
        onVariantSetup();
        handleShare(getIntent());
    }

    /** Android TV: most sites serve their best "big screen" layout to desktop browsers. */
    protected void onVariantSetup() {
        desktop.setChecked(true);
        url.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (!m.find()) return;
        String link = m.group();
        if (link.contains("addons.mozilla.org/") && link.contains("/addon/")) {
            String cur = extensions.getText().toString().trim();
            extensions.setText(cur.isEmpty() ? link : cur + "\n" + link);
        } else {
            url.setText(link);
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if (subject != null && name.getText().length() == 0) name.setText(subject);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        InstallReceiver.listener = this;
        refreshApps();
    }

    @Override
    protected void onPause() {
        InstallReceiver.listener = null;
        super.onPause();
    }

    private void setBusy(boolean busy, String message) {
        create.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (message != null) status.setText(message);
    }

    private void onCreateClicked() {
        final Generator.Request req = new Generator.Request();
        req.url = url.getText().toString();
        req.name = name.getText().toString();
        req.extensions = extensions.getText().toString();
        req.portable = portable.isChecked();
        req.desktopMode = desktop.isChecked();
        req.keepScreenOn = screenOn.isChecked();
        try {
            Generator.normalizeUrl(req.url);
        } catch (IllegalArgumentException e) {
            url.setError(e.getMessage());
            return;
        }
        if (!req.portable && Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow app installs")
                    .setMessage("To install the apps it creates, WebApper needs the \"Install unknown apps\" permission.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())), REQ_UNKNOWN_SOURCES);
                        } catch (Exception ex) {
                            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        setBusy(true, "Starting…");
        worker.execute(() -> {
            try {
                Generator.Result r = Generator.build(this, req, msg -> ui.post(() -> status.setText(msg)));
                ui.post(() -> onBuilt(r, req.portable));
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                ui.post(() -> setBusy(false, "Error: " + msg));
            }
        });
    }

    private void onBuilt(Generator.Result r, boolean isPortable) {
        pendingResult = r;
        if (isPortable) {
            setBusy(false, "Built " + r.name + ". Choose where to save the APK…");
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_TITLE, r.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".apk");
            startActivityForResult(i, REQ_SAVE_APK);
            return;
        }
        setBusy(true, "Installing " + r.name + "…");
        worker.execute(() -> {
            try {
                InstallReceiver.install(this, r.apk, r.packageName);
            } catch (Exception e) {
                ui.post(() -> setBusy(false, "Install failed: " + e.getMessage()));
            }
        });
    }

    @Override
    public void onInstallFinished(boolean success, String packageName, String message) {
        ui.post(() -> {
            setBusy(false, success ? "Installed! You can find it in your app list." : "Install failed: " + message);
            refreshApps();
            if (success && packageName != null) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch != null) {
                    new AlertDialog.Builder(this)
                            .setMessage("App installed. Open it now?")
                            .setPositiveButton("Open", (d, w) -> startActivity(launch))
                            .setNegativeButton("Later", null)
                            .show();
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNKNOWN_SOURCES) {
            if (Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) onCreateClicked();
            return;
        }
        if (requestCode != REQ_SAVE_APK || pendingResult == null) return;
        final Generator.Result r = pendingResult;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            status.setText("Not saved.");
            return;
        }
        final Uri dest = data.getData();
        setBusy(true, "Saving…");
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(r.apk); OutputStream out = getContentResolver().openOutputStream(dest)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                ui.post(() -> setBusy(false, "Saved " + r.name + ".apk — install it on any Android device "
                        + "(signed by this WebApper installer)."));
            } catch (Exception e) {
                ui.post(() -> setBusy(false, "Save failed: " + e.getMessage()));
            }
        });
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void refreshApps() {
        apps.removeAllViews();
        for (History.Item it : History.list(this)) {
            final boolean isInstalled = installed(it.packageName);
            TextView row = new TextView(this);
            row.setText(it.name + (isInstalled ? "" : "  (not installed)") + "\n" + it.url);
            row.setPadding(0, 16, 0, 16);
            row.setFocusable(true);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v -> {
                String[] actions = isInstalled
                        ? new String[]{"Open", "Uninstall", "Edit / re-create"}
                        : new String[]{"Edit / re-create"};
                new AlertDialog.Builder(this).setTitle(it.name).setItems(actions, (d, which) -> {
                    String a = actions[which];
                    if (a.equals("Open")) {
                        Intent l = getPackageManager().getLaunchIntentForPackage(it.packageName);
                        if (l != null) startActivity(l);
                    } else if (a.equals("Uninstall")) {
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + it.packageName)));
                    } else {
                        url.setText(it.url);
                        name.setText(it.name);
                        url.requestFocus();
                    }
                }).show();
            });
            apps.addView(row);
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
