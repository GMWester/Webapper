package com.webapper.installer;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;

import com.webapper.installer.apk.ApkGenerator;
import com.webapper.installer.apk.ApkSigning;
import com.webapper.installer.apk.SigningKey;
import com.webapper.installer.apk.ZipRewriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/** Builds a signed APK for one web app, entirely on the device. */
final class Generator {
    interface Progress {
        void step(String message);
    }

    static final class Request {
        String name = "";
        String url = "";
        String extensions = "";
        boolean portable;
        boolean desktopMode;
        boolean keepScreenOn;
        /** Android XR: launch in Full Space instead of a Home Space panel. */
        boolean fullSpace;
    }

    static final class Result {
        File apk;
        String packageName;
        String name;
    }

    private Generator() {}

    static String normalizeUrl(String text) {
        String t = text.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("Please enter a website address.");
        if (!t.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) t = "https://" + t;
        Uri u = Uri.parse(t);
        if (u.getHost() == null || u.getHost().isEmpty()) throw new IllegalArgumentException("'" + text + "' is not a valid web address.");
        if (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme())) {
            throw new IllegalArgumentException("Only http:// and https:// addresses are supported.");
        }
        return t;
    }

    /** Stable package name per (name, url): re-creating the same app installs as an update. */
    static String packageNameFor(String name, String url) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] h = md.digest((name.trim().toLowerCase(Locale.ROOT) + "\n" + url).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("com.webapper.gen.a");
        for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", h[i]));
        return sb.toString();
    }

    /** Copies the bundled template APK out of assets (once per installer version). */
    static File template(Context ctx) throws Exception {
        PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        File f = new File(ctx.getNoBackupFilesDir(), "template-" + pi.lastUpdateTime + ".apk");
        if (f.exists()) return f;
        File[] old = ctx.getNoBackupFilesDir().listFiles((d, n) -> n.startsWith("template-"));
        if (old != null) for (File o : old) //noinspection ResultOfMethodCallIgnored
            o.delete();
        File tmp = new File(f.getPath() + ".tmp");
        try (InputStream in = ctx.getAssets().open("template.apk"); OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("Could not prepare the app template");
        return f;
    }

    static String pickAbi(File template) throws Exception {
        List<String> names = ZipRewriter.list(template);
        for (String abi : Build.SUPPORTED_ABIS) {
            for (String n : names) if (n.startsWith("lib/" + abi + "/")) return abi;
        }
        return null;
    }

    static Result build(Context ctx, Request req, Progress progress) throws Exception {
        String url = normalizeUrl(req.url);

        progress.step("Fetching the site title and icon…");
        Icons.Site site = Icons.fetch(url);
        String name = req.name.trim();
        if (name.isEmpty()) name = site.title != null && !site.title.isEmpty() ? site.title : Uri.parse(url).getHost();
        if (name.length() > 50) name = name.substring(0, 50).trim();

        ApkGenerator.Params p = new ApkGenerator.Params();
        p.packageName = packageNameFor(name, url);
        p.label = name;
        if (req.fullSpace) {
            p.manifestStrings.put("XR_ACTIVITY_START_MODE_HOME_SPACE", "XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED");
        }

        JSONArray exts = new JSONArray();
        List<String> sources = Addons.split(req.extensions);
        int i = 0;
        for (String src : sources) {
            i++;
            progress.step("Downloading add-on " + i + "/" + sources.size() + ": " + src);
            Addons.Addon a = Addons.resolve(src);
            String asset = "extensions/" + i + ".xpi";
            p.extensions.put(asset, a.xpi);
            exts.put(new JSONObject().put("id", a.id).put("name", a.name).put("asset", asset)
                    .put("url", a.xpiUrl).put("source", a.source));
        }

        JSONObject cfg = new JSONObject()
                .put("id", p.packageName)
                .put("name", name)
                .put("url", url)
                .put("desktopMode", req.desktopMode)
                .put("keepScreenOn", req.keepScreenOn)
                .put("extensions", exts)
                .put("platform", Variant.KIND)
                .put("createdBy", "WebApper " + BuildConfig.VERSION_NAME);
        p.configJson = cfg.toString(2).getBytes(StandardCharsets.UTF_8);

        Bitmap icon = Icons.launcherIcon(site.icon, name, 192);
        p.iconPng = Icons.png(icon);
        if (Variant.TV_BANNER) {
            p.bannerPng = Icons.png(Icons.banner(icon, name, ApkGenerator.BANNER_W, ApkGenerator.BANNER_H));
        }

        progress.step("Preparing the Firefox (GeckoView) engine…");
        File template = template(ctx);
        p.keepAbi = req.portable ? null : pickAbi(template);

        File work = new File(ctx.getCacheDir(), "build");
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        File unsigned = new File(work, "unsigned.apk");
        File signed = new File(work, p.packageName + ".apk");
        progress.step("Building " + name + ".apk…");
        ApkGenerator.build(template, unsigned, p);

        progress.step("Signing…");
        SigningKey key = SigningKey.loadOrCreate(new File(ctx.getNoBackupFilesDir(), "keys"), "WebApper generated apps");
        ApkSigning.sign(unsigned, signed, key, 26);
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();

        Result r = new Result();
        r.apk = signed;
        r.packageName = p.packageName;
        r.name = name;
        History.add(ctx, r, url, sources);
        return r;
    }
}
