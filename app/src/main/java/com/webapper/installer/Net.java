package com.webapper.installer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tiny HTTP helper (redirect-following GET). */
final class Net {
    static final String USER_AGENT = "WebApper-Android/" + BuildConfig.VERSION_NAME;

    private Net() {}

    static final class Response {
        final String finalUrl;
        final byte[] body;
        final String contentType;

        Response(String finalUrl, byte[] body, String contentType) {
            this.finalUrl = finalUrl;
            this.body = body;
            this.contentType = contentType;
        }
    }

    static Response get(String url, int maxBytes) throws IOException {
        String current = url;
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException(current + ": HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    if (maxBytes > 0 && out.size() > maxBytes) break;
                }
                return new Response(current, out.toByteArray(), c.getContentType());
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects: " + url);
    }
}
