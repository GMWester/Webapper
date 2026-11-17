package com.webapper.template;

import android.content.Context;
import android.util.Log;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Installs the Firefox add-ons chosen in the installer into this app's Gecko profile. */
final class Extensions {
    private static final String TAG = "WebApperExt";

    private Extensions() {}

    /** Ensures all configured add-ons are installed and enabled; completes when done. */
    static GeckoResult<Void> ensureInstalled(Context ctx, GeckoRuntime runtime, WebAppConfig cfg) {
        final WebExtensionController controller = runtime.getWebExtensionController();
        controller.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override
            public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension extension, String[] permissions, String[] origins,
                    String[] dataCollectionPermissions) {
                // The user already chose these add-ons when creating the app.
                return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(true, false, false));
            }

            @Override
            public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onUpdatePrompt(
                    WebExtension extension, String[] newPermissions, String[] newOrigins,
                    String[] newDataCollectionPermissions) {
                return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.ALLOW);
            }
        });
        if (cfg.extensions.isEmpty()) return GeckoResult.fromValue(null);

        return controller.list().then(installed -> {
            Set<String> have = new HashSet<>();
            // Add-ons the user turned off in the add-ons menu stay off.
            if (installed != null) {
                for (WebExtension e : installed) have.add(e.id);
            }
            List<WebAppConfig.Ext> todo = new ArrayList<>();
            for (WebAppConfig.Ext x : cfg.extensions) {
                if (x.id.isEmpty() || !have.contains(x.id)) todo.add(x);
            }
            return installFrom(ctx, controller, todo, 0);
        }, err -> {
            Log.w(TAG, "list() failed", err);
            return GeckoResult.fromValue(null);
        });
    }

    private static GeckoResult<Void> installFrom(Context ctx, WebExtensionController c,
                                                 List<WebAppConfig.Ext> todo, int i) {
        if (i >= todo.size()) return GeckoResult.fromValue(null);
        final WebAppConfig.Ext x = todo.get(i);
        String local = x.asset != null ? copyAsset(ctx, x.asset) : null;
        GeckoResult<WebExtension> first = local != null ? c.install(local) : c.install(x.url);
        return first.then(ext -> installFrom(ctx, c, todo, i + 1), err -> {
            Log.w(TAG, "Installing " + x.name + " from " + (local != null ? local : x.url) + " failed", err);
            if (local != null && x.url != null && !x.url.isEmpty()) {
                return c.install(x.url).then(
                        ext -> installFrom(ctx, c, todo, i + 1),
                        err2 -> installFrom(ctx, c, todo, i + 1));
            }
            return installFrom(ctx, c, todo, i + 1);
        });
    }

    private static String copyAsset(Context ctx, String asset) {
        File dir = new File(ctx.getFilesDir(), "xpi");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, new File(asset).getName());
        try (InputStream in = ctx.getAssets().open(asset); OutputStream os = new FileOutputStream(out)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
            return "file://" + out.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "copy " + asset, e);
            return null;
        }
    }
}
