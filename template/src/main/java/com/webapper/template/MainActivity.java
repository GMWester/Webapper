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
    private DpadCursor cursor;

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
        // TV: add-on buttons live in the MENU-key menu instead of a floating button.
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

    /** Android TV: add the remote-control pointer on top of the page. */
    protected void onRootCreated(FrameLayout root) {
        cursor = new DpadCursor(this, geckoView);
        root.addView(cursor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        if (code == android.view.KeyEvent.KEYCODE_MENU || code == android.view.KeyEvent.KEYCODE_BUTTON_Y) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) showTvMenu();
            return true;
        }
        if (cursor != null && cursor.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private void showTvMenu() {
        final String[] items = {
                cursor.isCursorEnabled() ? "Use D-pad to move focus (hide pointer)" : "Show pointer",
                "Reload", "Add-ons", "Home page"};
        new AlertDialog.Builder(this).setTitle(config.name).setItems(items, (d, which) -> {
            switch (which) {
                case 0: cursor.setCursorEnabled(!cursor.isCursorEnabled()); break;
                case 1: session.reload(); break;
                case 2: showActions(); break;
                default: session.loadUri(config.url);
            }
        }).show();
    }

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
        if (cursor != null) cursor.setSession(session);
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
        extButton.setVisibility(!full && !actions.isEmpty() ? View.VISIBLE : View.GONE);
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
        b.setVisibility(View.GONE);
        b.setOnClickListener(v -> showActions());
        return b;
    }

    private void hookExtensionActions() {
        final WebExtension.ActionDelegate delegate = new WebExtension.ActionDelegate() {
            @Override
            public void onBrowserAction(WebExtension ext, GeckoSession s, WebExtension.Action action) {
                actions.put(ext.id, action);
                actionNames.put(ext.id, ext.metaData.name != null ? ext.metaData.name : ext.id);
                extButton.setVisibility(fullScreen ? View.GONE : View.VISIBLE);
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

    protected void showActions() {
        if (actions.isEmpty()) return;
        final String[] ids = actions.keySet().toArray(new String[0]);
        if (ids.length == 1) {
            actions.get(ids[0]).click();
            return;
        }
        String[] names = new String[ids.length];
        for (int i = 0; i < ids.length; i++) names[i] = actionNames.get(ids[i]);
        new AlertDialog.Builder(this).setTitle("Add-ons")
                .setItems(names, (d, which) -> actions.get(ids[which]).click())
                .show();
    }

    private GeckoResult<GeckoSession> showPopup(WebExtension ext) {
        final GeckoSession popup = new GeckoSession();
        popup.open(runtime);
        GeckoView view = new GeckoView(this);
        view.setSession(popup);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.7);
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        FrameLayout box = new FrameLayout(this);
        box.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        new AlertDialog.Builder(this)
                .setTitle(ext.metaData.name)
                .setView(box)
                .setOnDismissListener(d -> popup.close())
                .show();
        return GeckoResult.fromValue(popup);
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
