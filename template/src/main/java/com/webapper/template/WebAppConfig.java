package com.webapper.template;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads assets/webapp.json, written by the WebApper installer into every generated APK. */
public final class WebAppConfig {
    public static final class Ext {
        public String id;
        public String name;
        /** Path inside assets/ of the bundled, signed XPI (may be null). */
        public String asset;
        /** Download URL used when the bundled XPI cannot be installed. */
        public String url;
    }

    public String id = "";
    public String name = "Web App";
    public String url = "https://example.com";
    public boolean desktopMode = false;
    public boolean keepScreenOn = false;
    public final List<Ext> extensions = new ArrayList<>();

    public static WebAppConfig load(Context ctx) {
        WebAppConfig c = new WebAppConfig();
        try (InputStream in = ctx.getAssets().open("webapp.json")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            JSONObject o = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            c.id = o.optString("id", c.id);
            c.name = o.optString("name", c.name);
            c.url = o.optString("url", c.url);
            c.desktopMode = o.optBoolean("desktopMode", false);
            c.keepScreenOn = o.optBoolean("keepScreenOn", false);
            JSONArray exts = o.optJSONArray("extensions");
            if (exts != null) {
                for (int i = 0; i < exts.length(); i++) {
                    JSONObject e = exts.getJSONObject(i);
                    Ext x = new Ext();
                    x.id = e.optString("id", "");
                    x.name = e.optString("name", x.id);
                    x.asset = e.isNull("asset") ? null : e.optString("asset", null);
                    x.url = e.optString("url", "");
                    c.extensions.add(x);
                }
            }
        } catch (Exception ignored) {
            // Unconfigured template build: fall back to defaults.
        }
        return c;
    }
}
