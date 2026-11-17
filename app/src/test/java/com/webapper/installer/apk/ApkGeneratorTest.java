package com.webapper.installer.apk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.apksig.ApkVerifier;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Runs the on-device generator against the real GeckoView template APK on the JVM. */
public class ApkGeneratorTest {
    private static File template() {
        File f = new File(System.getProperty("webapper.templateApk"));
        assertTrue("template APK missing: " + f, f.exists());
        return f;
    }

    @Test
    public void templateHasPlaceholders() throws Exception {
        List<String> strings = AxmlPatcher.strings(ZipRewriter.read(template(), "AndroidManifest.xml"));
        assertTrue(strings.contains(ApkGenerator.TEMPLATE_PACKAGE));
        assertTrue(strings.contains(ApkGenerator.TEMPLATE_LABEL));
        boolean icon = false;
        for (String n : ZipRewriter.list(template())) {
            if (n.startsWith("res/") && n.endsWith(".png")) {
                int[] s = ApkGenerator.pngSize(ZipRewriter.read(template(), n));
                icon |= s != null && s[0] == ApkGenerator.ICON_SIZE && s[1] == ApkGenerator.ICON_SIZE;
            }
        }
        assertTrue("193x193 placeholder icon not found", icon);
    }

    @Test
    public void generatesSignedApp() throws Exception {
        File out = new File(System.getProperty("webapper.outDir"));
        out.mkdirs();
        ApkGenerator.Params p = new ApkGenerator.Params();
        p.packageName = "com.webapper.gen.a0123456789abcdef";
        p.label = "Example ✓";
        p.configJson = "{\"name\":\"Example\",\"url\":\"https://example.com\",\"extensions\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        p.iconPng = ZipRewriter.read(template(), firstPng());
        p.extensions.put("extensions/1.xpi", new byte[]{'P', 'K', 3, 4});
        p.keepAbi = "x86_64";
        File unsigned = new File(out, "unsigned.apk");
        File signed = new File(out, "generated.apk");
        ApkGenerator.build(template(), unsigned, p);
        SigningKey key = SigningKey.loadOrCreate(new File(out, "key"), "test");
        ApkSigning.sign(unsigned, signed, key, 26);

        ApkVerifier.Result r = new ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(26).build().verify();
        assertTrue("signature errors: " + r.getErrors(), r.isVerified());

        List<String> strings = AxmlPatcher.strings(ZipRewriter.read(signed, "AndroidManifest.xml"));
        assertTrue(strings.contains("com.webapper.gen.a0123456789abcdef"));
        assertTrue(strings.contains("Example ✓"));
        for (String s : strings) assertFalse(s, s.contains(ApkGenerator.TEMPLATE_PACKAGE));
        assertArrayEquals(p.configJson, ZipRewriter.read(signed, "assets/webapp.json"));
        for (String n : ZipRewriter.list(signed)) {
            assertFalse(n, n.startsWith("lib/") && !n.startsWith("lib/x86_64/"));
        }
        assertEquals(4, ZipRewriter.read(signed, "assets/extensions/1.xpi").length);
    }

    private static String firstPng() throws Exception {
        for (String n : ZipRewriter.list(template())) if (n.startsWith("res/") && n.endsWith(".png")) return n;
        throw new AssertionError("no png");
    }
}
