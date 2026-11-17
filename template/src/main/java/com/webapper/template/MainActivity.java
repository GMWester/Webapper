package com.webapper.template;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single screen of a generated web app: a full-window GeckoView (Firefox engine) showing the
 * configured site, with the chosen Firefox add-ons installed in its private profile.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_ANDROID_PERMS = 100;

    protected GeckoRuntime runtime;
    protected GeckoSession session;
    protected GeckoView geckoView;
    protected FrameLayout root;
    protected WebAppConfig config;
    private Prompts prompts;
    private boolean canGoBack;
    private boolean fullScreen;
    private TextView extButton;
    private final Map<String, WebExtension.Action> actions = new LinkedHashMap<>();
    private final Map<String, String> actionNames = new LinkedHashMap<>();
    private final SparseArray<GeckoSession.PermissionDelegate.Callback> permCallbacks = new SparseArray<>();
    private int nextPermRequest = REQUEST_ANDROID_PERMS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = WebAppConfig.load(this);
        setTitle(config.name);
        if (config.keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        geckoView = new GeckoView(this);
        root.addView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        extButton = makeExtensionButton();
        root.addView(extButton);
        setContentView(root);
        onRootCreated(root);

        runtime = GeckoRuntime.getDefault(this);
        prompts = new Prompts(this);
        openSession();

        Extensions.ensureInstalled(this, runtime, config).accept(v -> {
            hookExtensionActions();
            session.loadUri(config.url);
        }, err -> session.loadUri(config.url));
    }

    /** Hook for platform variants (TV cursor, XR tweaks). */
    protected void onRootCreated(FrameLayout root) {}

    protected GeckoSessionSettings sessionSettings() {
        GeckoSessionSettings.Builder b = new GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .usePrivateMode(false);
        if (config.desktopMode) {
            b.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                    .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP);
        }
        return b.build();
    }

    private void openSession() {
        session = new GeckoSession(sessionSettings());
        session.setPromptDelegate(prompts);
        session.setNavigationDelegate(new Navigation());
        session.setContentDelegate(new Content());
        session.setPermissionDelegate(new Permissions());
        session.open(runtime);
        geckoView.setSession(session);
    }

    // ------------------------------------------------------------------ navigation

    private class Navigation implements GeckoSession.NavigationDelegate {
        @Override
        public void onCanGoBack(GeckoSession s, boolean value) {
            canGoBack = value;
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
            Uri uri = Uri.parse(request.uri);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            switch (scheme) {
                case "http": case "https": case "about": case "data": case "blob": case "file":
                case "resource": case "moz-extension": case "javascript": case "view-source":
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                default:
                    openExternally(request.uri);
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
        }

        @Override
        public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
            // Tabs/windows are not shown in an app; open pop-up links in place.
            session.loadUri(uri);
            return null;
        }
    }

    private void openExternally(String uri) {
        try {
            Intent i = uri.startsWith("intent:") ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            i.setSelector(null);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No app can open " + uri, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ content

    private class Content implements GeckoSession.ContentDelegate {
        @Override
        public void onFullScreen(GeckoSession s, boolean full) {
            setFullScreen(full);
        }

        @Override
        public void onTitleChange(GeckoSession s, String title) {
            // Keep the app's own name in the recents screen.
        }

        @Override
        public void onExternalResponse(GeckoSession s, WebResponse response) {
            saveDownload(response);
        }

        @Override
        public void onCrash(GeckoSession s) {
            recover();
        }

        @Override
        public void onKill(GeckoSession s) {
            recover();
        }
    }

    private void recover() {
        runOnUiThread(() -> {
            try {
                session.close();
            } catch (Exception ignored) {
            }
            openSession();
            session.loadUri(config.url);
        });
    }

    protected void setFullScreen(boolean full) {
        fullScreen = full;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (full) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(full
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : 0);
        }
        extButton.setVisibility(!full && !config.extensions.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static String fileName(WebResponse r) {
        String cd = r.headers.get("Content-Disposition");
        if (cd == null) cd = r.headers.get("content-disposition");
        if (cd != null) {
            Matcher m = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) return Uri.decode(m.group(1)).replaceAll("[/\\\\]", "_");
        }
        String last = Uri.parse(r.uri).getLastPathSegment();
        return last == null || last.isEmpty() ? "download" : last.replaceAll("[/\\\\]", "_");
    }

    private void saveDownload(WebResponse response) {
        final InputStream body = response.body;
        if (body == null) return;
        final String name = fileName(response);
        String type = response.headers.get("Content-Type");
        if (type == null) type = response.headers.get("content-type");
        if (type == null) {
            String ext = MimeTypeMap.getFileExtensionFromUrl(name);
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
        final String mime = type == null ? "application/octet-stream" : type.split(";")[0].trim();
        Toast.makeText(this, "Downloading " + name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                OutputStream out;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri item = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    out = getContentResolver().openOutputStream(item);
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    out = new FileOutputStream(new File(dir, name));
                }
                try (InputStream in = body; OutputStream o = out) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
                runOnUiThread(() -> Toast.makeText(this, "Saved " + name + " to Downloads", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ------------------------------------------------------------------ permissions

    private class Permissions implements GeckoSession.PermissionDelegate {
        @Override
        public void onAndroidPermissionsRequest(GeckoSession s, String[] perms, Callback callback) {
            if (perms == null || perms.length == 0) {
                callback.grant();
                return;
            }
            boolean all = true;
            for (String p : perms) all &= checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
            if (all) {
                callback.grant();
                return;
            }
            int code = nextPermRequest++;
            permCallbacks.put(code, callback);
            requestPermissions(perms, code);
        }

        @Override
        public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission perm) {
            String what;
            switch (perm.permission) {
                case PERMISSION_GEOLOCATION: what = "access your location"; break;
                case PERMISSION_DESKTOP_NOTIFICATION: what = "show notifications"; break;
                case PERMISSION_XR: what = "use virtual reality"; break;
                case PERMISSION_LOCAL_DEVICE_ACCESS:
                case PERMISSION_LOCAL_NETWORK_ACCESS: what = "access devices on your network"; break;
                default:
                    return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
            }
            if (perm.permission == PERMISSION_DESKTOP_NOTIFICATION && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 99);
            }
            return ask(Uri.parse(perm.uri).getHost() + " wants to " + what + ".",
                    ContentPermission.VALUE_ALLOW, ContentPermission.VALUE_DENY);
        }

        @Override
        public void onMediaPermissionRequest(GeckoSession s, String uri, MediaSource[] video,
                                             MediaSource[] audio, MediaCallback callback) {
            String what = video != null && audio != null ? "camera and microphone"
                    : video != null ? "camera" : "microphone";
            ask(Uri.parse(uri).getHost() + " wants to use your " + what + ".", 1, 0).accept(v -> {
                if (v != null && v == 1) {
                    callback.grant(video != null && video.length > 0 ? video[0] : null,
                            audio != null && audio.length > 0 ? audio[0] : null);
                } else {
                    callback.reject();
                }
            });
        }
    }

    private GeckoResult<Integer> ask(String message, int yes, int no) {
        GeckoResult<Integer> res = new GeckoResult<>();
        final boolean[] done = {false};
        new AlertDialog.Builder(this)
                .setTitle(config.name)
                .setMessage(message)
                .setPositiveButton("Allow", (d, w) -> { done[0] = true; res.complete(yes); })
                .setNegativeButton("Block", (d, w) -> { done[0] = true; res.complete(no); })
                .setOnDismissListener(d -> { if (!done[0]) res.complete(no); })
                .show();
        return res;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        GeckoSession.PermissionDelegate.Callback cb = permCallbacks.get(requestCode);
        if (cb == null) return;
        permCallbacks.remove(requestCode);
        boolean ok = grantResults.length > 0;
        for (int r : grantResults) ok &= r == PackageManager.PERMISSION_GRANTED;
        if (ok) cb.grant(); else cb.reject();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == Prompts.REQUEST_FILE) {
            prompts.onFileResult(resultCode, data);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ------------------------------------------------------------------ add-on toolbar buttons

    private TextView makeExtensionButton() {
        TextView b = new TextView(this);
        b.setText("🧩");
        b.setTextSize(20);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription("Add-ons");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC202124);
        b.setBackground(bg);
        b.setAlpha(0.8f);
        int size = (int) (44 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
        int m = (int) (16 * getResources().getDisplayMetrics().density);
        lp.setMargins(m, m, m, m);
        b.setLayoutParams(lp);
        b.setFocusable(true);
        b.setVisibility(config.extensions.isEmpty() ? View.GONE : View.VISIBLE);
        b.setOnClickListener(v -> showActions());
        return b;
    }

    private void hookExtensionActions() {
        final WebExtension.ActionDelegate delegate = new WebExtension.ActionDelegate() {
            @Override
            public void onBrowserAction(WebExtension ext, GeckoSession s, WebExtension.Action action) {
                actions.put(ext.id, action);
                actionNames.put(ext.id, ext.metaData.name != null ? ext.metaData.name : ext.id);
            }

            @Override
            public GeckoResult<GeckoSession> onTogglePopup(WebExtension ext, WebExtension.Action action) {
                return showPopup(ext);
            }

            @Override
            public GeckoResult<GeckoSession> onOpenPopup(WebExtension ext, WebExtension.Action action) {
                return showPopup(ext);
            }
        };
        runtime.getWebExtensionController().list().accept(list -> {
            if (list == null) return;
            for (WebExtension e : list) e.setActionDelegate(delegate);
        });
    }

    /** Add-ons manager: open an add-on's popup, its settings page, or turn it on/off. */
    protected void showActions() {
        runtime.getWebExtensionController().list().accept(list -> {
            if (list == null || list.isEmpty()) {
                Toast.makeText(this, "No add-ons installed", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = new String[list.size()];
            for (int i = 0; i < names.length; i++) {
                WebExtension e = list.get(i);
                String n = e.metaData.name != null ? e.metaData.name : e.id;
                names[i] = e.metaData.enabled ? n : n + "  (off)";
            }
            new AlertDialog.Builder(this).setTitle("Add-ons")
                    .setItems(names, (d, which) -> showAddon(list.get(which)))
                    .show();
        });
    }

    private void showAddon(WebExtension e) {
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Runnable> actionsList = new java.util.ArrayList<>();
        final boolean enabled = e.metaData.enabled;
        final WebExtension.Action action = actions.get(e.id);
        if (enabled && action != null) {
            labels.add("Open");
            actionsList.add(action::click);
        }
        if (enabled && e.metaData.optionsPageUrl != null && !e.metaData.optionsPageUrl.isEmpty()) {
            labels.add("Settings");
            actionsList.add(() -> openPanel(e.metaData.name + " settings").loadUri(e.metaData.optionsPageUrl));
        }
        labels.add(enabled ? "Turn off" : "Turn on");
        actionsList.add(() -> setAddonEnabled(e, !enabled));
        StringBuilder info = new StringBuilder("Version ").append(e.metaData.version);
        if (e.metaData.description != null) info.append("\n\n").append(e.metaData.description);
        TextView header = new TextView(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        header.setPadding(pad, pad / 2, pad, 0);
        header.setText(info);
        new AlertDialog.Builder(this)
                .setTitle(e.metaData.name != null ? e.metaData.name : e.id)
                .setView(header)
                .setItems(labels.toArray(new String[0]), (d, which) -> actionsList.get(which).run())
                .setNegativeButton("Close", null)
                .show();
    }

    private void setAddonEnabled(WebExtension e, boolean enable) {
        org.mozilla.geckoview.WebExtensionController c = runtime.getWebExtensionController();
        GeckoResult<WebExtension> r = enable
                ? c.enable(e, org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
                : c.disable(e, org.mozilla.geckoview.WebExtensionController.EnableSource.USER);
        r.accept(updated -> {
            if (!enable) actions.remove(e.id);
            Toast.makeText(this, (enable ? "Turned on " : "Turned off ") + e.metaData.name, Toast.LENGTH_SHORT).show();
            if (enable) hookExtensionActions();
            session.reload();
        }, err -> Toast.makeText(this, "Could not change " + e.metaData.name, Toast.LENGTH_SHORT).show());
    }

    /** A dialog with its own GeckoSession, used for add-on popups and settings pages. */
    private GeckoSession openPanel(String title) {
        final GeckoSession panel = new GeckoSession();
        panel.open(runtime);
        GeckoView view = new GeckoView(this);
        view.setSession(panel);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.7);
        FrameLayout box = new FrameLayout(this);
        box.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Close", null)
                .setOnDismissListener(d -> panel.close())
                .show();
        return panel;
    }

    private GeckoResult<GeckoSession> showPopup(WebExtension ext) {
        return GeckoResult.fromValue(openPanel(ext.metaData.name));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onBackPressed() {
        if (fullScreen) {
            session.exitFullScreen();
        } else if (canGoBack) {
            session.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && session != null) session.close();
        super.onDestroy();
    }
}
