package com.webapper.installer.apk;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the bundled GeckoView template APK into a brand-new app: new package name, new launcher
 * label and icon, the web app configuration and the Firefox add-ons as assets. Pure Java so it
 * can be unit-tested on a desktop JVM.
 */
public final class ApkGenerator {
    public static final String TEMPLATE_PACKAGE = "com.webapper.gen.t0000000000000000";
    public static final String TEMPLATE_LABEL = "WebApperTemplateAppName";
    /** Placeholder icon dimensions in the template (unique, so the icon can be found by size). */
    public static final int ICON_SIZE = 193;
    /** Placeholder Android TV banner dimensions in the template (TV builds only). */
    public static final int BANNER_W = 321, BANNER_H = 181;

    public static final class Params {
        public String packageName;
        public String label;
        public byte[] configJson;
        public byte[] iconPng;
        public byte[] bannerPng;
        /** asset path (e.g. "extensions/1.xpi") to XPI bytes */
        public final Map<String, byte[]> extensions = new LinkedHashMap<>();
        /** Keep only this ABI's native libraries (null = keep all). */
        public String keepAbi;
        /** Further whole-string replacements in the manifest (variant specific). */
        public final Map<String, String> manifestStrings = new LinkedHashMap<>();
    }

    private ApkGenerator() {}

    public static boolean isValidPackageName(String p) {
        return p != null && p.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+") && p.length() < 200;
    }

    static int[] pngSize(byte[] png) {
        if (png == null || png.length < 24 || png[0] != (byte) 0x89 || png[1] != 'P') return null;
        int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        return new int[]{w, h};
    }

    public static void build(File template, File out, Params p) throws IOException {
        if (!isValidPackageName(p.packageName)) throw new IOException("Invalid package name " + p.packageName);
        final Map<String, String> exact = new LinkedHashMap<>();
        exact.putAll(p.manifestStrings);
        exact.put(TEMPLATE_LABEL, p.label);
        final Map<String, String> sub = new LinkedHashMap<>();
        sub.put(TEMPLATE_PACKAGE, p.packageName);

        Map<String, ZipRewriter.NewEntry> added = new LinkedHashMap<>();
        added.put("assets/webapp.json", new ZipRewriter.NewEntry(p.configJson, false));
        for (Map.Entry<String, byte[]> e : p.extensions.entrySet()) {
            // XPIs are zips already: store them (uncompressed, aligned).
            added.put("assets/" + e.getKey(), new ZipRewriter.NewEntry(e.getValue(), true));
        }

        ZipRewriter.rewrite(template, out, new ZipRewriter.Rules() {
            @Override
            public ZipRewriter.Action action(String name) {
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA")
                        || name.endsWith(".DSA") || name.endsWith(".EC") || name.equals("META-INF/MANIFEST.MF"))) {
                    return ZipRewriter.Action.DROP;
                }
                if (p.keepAbi != null && name.startsWith("lib/") && !name.startsWith("lib/" + p.keepAbi + "/")) {
                    return ZipRewriter.Action.DROP;
                }
                if (name.equals("AndroidManifest.xml")) return ZipRewriter.Action.REPLACE;
                if (name.startsWith("res/") && name.endsWith(".png")) return ZipRewriter.Action.REPLACE;
                return ZipRewriter.Action.KEEP;
            }

            @Override
            public byte[] replace(String name, byte[] data) {
                if (name.equals("AndroidManifest.xml")) return AxmlPatcher.patch(data, exact, sub);
                int[] size = pngSize(data);
                if (size != null && size[0] == ICON_SIZE && size[1] == ICON_SIZE && p.iconPng != null) {
                    return p.iconPng;
                }
                if (size != null && size[0] == BANNER_W && size[1] == BANNER_H && p.bannerPng != null) {
                    return p.bannerPng;
                }
                return data;
            }
        }, added);
    }
}
