package com.webapper.installer;

import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Resolves Firefox add-on links (addons.mozilla.org pages, slugs, GUIDs or .xpi URLs). */
final class Addons {
    private static final Pattern AMO = Pattern.compile(
            "^https?://addons\\.mozilla\\.org/(?:[a-zA-Z-]+/)?(?:firefox|android)/addon/([^/?#]+)",
            Pattern.CASE_INSENSITIVE);

    static final class Addon {
        String source, id = "", name = "", xpiUrl = "";
        byte[] xpi;
    }

    private Addons() {}

    static List<String> split(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : text.split("[\\s,]+")) if (!s.trim().isEmpty()) out.add(s.trim());
        return new ArrayList<>(out);
    }

    static String slug(String source) {
        Matcher m = AMO.matcher(source.trim());
        if (m.find()) return Uri.decode(m.group(1));
        if (!source.contains("://") && !source.toLowerCase().endsWith(".xpi")
                && source.matches("[A-Za-z0-9._@{}-]+")) return source;
        return null;
    }

    static Addon resolve(String source) throws IOException {
        Addon a = new Addon();
        a.source = source;
        String slug = slug(source);
        if (slug != null) {
            String api = "https://addons.mozilla.org/api/v5/addons/addon/" + Uri.encode(slug, "@{}") + "/";
            try {
                JSONObject o = new JSONObject(new String(Net.get(api, 0).body, StandardCharsets.UTF_8));
                a.id = o.optString("guid", "");
                Object name = o.opt("name");
                if (name instanceof JSONObject) {
                    JSONObject n = (JSONObject) name;
                    a.name = n.optString("en-US", n.keys().hasNext() ? n.optString(n.keys().next()) : slug);
                } else if (name != null) {
                    a.name = name.toString();
                }
                JSONObject file = o.optJSONObject("current_version") == null ? null
                        : o.getJSONObject("current_version").optJSONObject("file");
                a.xpiUrl = file != null ? file.optString("url",
                        "https://addons.mozilla.org/firefox/downloads/latest/" + slug + "/latest.xpi")
                        : "https://addons.mozilla.org/firefox/downloads/latest/" + slug + "/latest.xpi";
            } catch (org.json.JSONException e) {
                throw new IOException("Unexpected answer from addons.mozilla.org for " + slug, e);
            } catch (IOException e) {
                throw new IOException("Could not find add-on '" + slug + "' on addons.mozilla.org", e);
            }
        } else if (source.startsWith("http://") || source.startsWith("https://")) {
            a.xpiUrl = source;
            a.name = Uri.parse(source).getLastPathSegment();
        } else {
            throw new IOException("'" + source + "' is not an addons.mozilla.org link or .xpi URL");
        }
        a.xpi = Net.get(a.xpiUrl, 0).body;
        if (a.id.isEmpty()) a.id = idFromXpi(a.xpi);
        if (a.name == null || a.name.isEmpty()) a.name = a.id;
        return a;
    }

    /** Reads browser_specific_settings.gecko.id (or applications.gecko.id) from an XPI. */
    static String idFromXpi(byte[] xpi) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xpi))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.getName().equals("manifest.json")) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = z.read(buf)) > 0) b.write(buf, 0, n);
                JSONObject m = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
                JSONObject bss = m.optJSONObject("browser_specific_settings");
                if (bss == null) bss = m.optJSONObject("applications");
                JSONObject gecko = bss == null ? null : bss.optJSONObject("gecko");
                if (gecko != null && !gecko.optString("id").isEmpty()) return gecko.optString("id");
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Invalid add-on manifest", e);
        }
        // Signed AMO add-ons without an explicit id: GeckoView reads the id from the signature.
        return "";
    }
}
