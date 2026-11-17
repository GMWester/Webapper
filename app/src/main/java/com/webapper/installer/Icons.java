package com.webapper.installer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds a site's best icon (apple-touch-icon, web manifest, favicon) and makes launcher art. */
final class Icons {
    private Icons() {}

    static final class Site {
        String title;
        Bitmap icon;
    }

    private static final class Candidate implements Comparable<Candidate> {
        final int size;
        final String url;

        Candidate(int size, String url) {
            this.size = size;
            this.url = url;
        }

        @Override
        public int compareTo(Candidate o) {
            return Integer.compare(o.size, size);
        }
    }

    private static String abs(String base, String href) {
        try {
            return new URL(new URL(base), Html.fromHtml(href, Html.FROM_HTML_MODE_LEGACY).toString()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    static Site fetch(String pageUrl) {
        Site site = new Site();
        String base = pageUrl;
        String html = "";
        try {
            Net.Response r = Net.get(pageUrl, 1 << 20);
            base = r.finalUrl;
            html = new String(r.body, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        Matcher t = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (t.find()) site.title = Html.fromHtml(t.group(1), Html.FROM_HTML_MODE_LEGACY).toString().trim().replaceAll("\\s+", " ");

        List<Candidate> cands = new ArrayList<>();
        Matcher links = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        Pattern relP = Pattern.compile("rel\\s*=\\s*[\"']?([^\"'>]+)", Pattern.CASE_INSENSITIVE);
        Pattern hrefP = Pattern.compile("href\\s*=\\s*[\"']?([^\"'\\s>]+)", Pattern.CASE_INSENSITIVE);
        Pattern sizeP = Pattern.compile("sizes\\s*=\\s*[\"']?(\\d+)x", Pattern.CASE_INSENSITIVE);
        while (links.find()) {
            String tag = links.group();
            Matcher rel = relP.matcher(tag), href = hrefP.matcher(tag);
            if (!rel.find() || !href.find()) continue;
            String relV = rel.group(1).toLowerCase();
            String url = abs(base, href.group(1));
            if (url == null || url.toLowerCase().split("\\?")[0].endsWith(".svg")) continue;
            if (relV.contains("manifest")) {
                try {
                    JSONObject m = new JSONObject(new String(Net.get(url, 1 << 20).body, StandardCharsets.UTF_8));
                    JSONArray icons = m.optJSONArray("icons");
                    for (int i = 0; icons != null && i < icons.length(); i++) {
                        JSONObject ic = icons.getJSONObject(i);
                        String src = ic.optString("src");
                        if (src.isEmpty() || src.toLowerCase().split("\\?")[0].endsWith(".svg")) continue;
                        int s = 0;
                        Matcher sm = Pattern.compile("(\\d+)x").matcher(ic.optString("sizes"));
                        while (sm.find()) s = Math.max(s, Integer.parseInt(sm.group(1)));
                        String u = abs(url, src);
                        if (u != null) cands.add(new Candidate(s + 1, u));
                    }
                } catch (Exception ignored) {
                }
                continue;
            }
            if (!relV.contains("icon")) continue;
            Matcher sm = sizeP.matcher(tag);
            int size = sm.find() ? Integer.parseInt(sm.group(1)) : relV.contains("apple-touch-icon") ? 180 : 0;
            cands.add(new Candidate(size, url));
        }
        Uri u = Uri.parse(base);
        String origin = u.getScheme() + "://" + u.getAuthority();
        cands.add(new Candidate(-1, origin + "/apple-touch-icon.png"));
        cands.add(new Candidate(-2, origin + "/favicon.ico"));
        Collections.sort(cands);
        for (Candidate c : cands) {
            try {
                byte[] data = Net.get(c.url, 4 << 20).body;
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (b == null) continue;
                if (site.icon == null || b.getWidth() > site.icon.getWidth()) site.icon = b;
                if (site.icon.getWidth() >= 128) break;
            } catch (Exception ignored) {
            }
        }
        if (site.icon != null && site.icon.getWidth() < 32) site.icon = null;
        return site;
    }

    static Bitmap letter(String name, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float[] hsv = {Math.abs(name.hashCode() % 360), 0.55f, 0.85f};
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.HSVToColor(hsv));
        c.drawRoundRect(new RectF(0, 0, size, size), size / 5f, size / 5f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(size * 0.6f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        String l = name.trim().isEmpty() ? "W" : name.trim().substring(0, 1).toUpperCase();
        Rect r = new Rect();
        p.getTextBounds(l, 0, 1, r);
        c.drawText(l, size / 2f, size / 2f + r.height() / 2f, p);
        return b;
    }

    /** Square launcher icon: the site icon centred on a white rounded tile. */
    static Bitmap launcherIcon(Bitmap site, String name, int size) {
        if (site == null) return letter(name, size);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(Color.WHITE);
        c.drawRoundRect(new RectF(0, 0, size, size), size / 5f, size / 5f, p);
        int inner = (int) (size * 0.72f);
        float scale = Math.min(inner / (float) site.getWidth(), inner / (float) site.getHeight());
        int w = Math.round(site.getWidth() * scale), h = Math.round(site.getHeight() * scale);
        int x = (size - w) / 2, y = (size - h) / 2;
        c.drawBitmap(site, null, new Rect(x, y, x + w, y + h), p);
        return b;
    }

    /** 16:9 Android TV launcher banner: icon + app name. */
    static Bitmap banner(Bitmap icon, String name, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF1F2937);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int s = (int) (h * 0.55f);
        c.drawBitmap(icon, null, new Rect((w - s) / 2, (int) (h * 0.08f), (w + s) / 2, (int) (h * 0.08f) + s), p);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(h * 0.14f);
        String label = name.length() > 22 ? name.substring(0, 21) + "…" : name;
        c.drawText(label, w / 2f, h * 0.88f, p);
        return b;
    }

    static byte[] png(Bitmap b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }
}
