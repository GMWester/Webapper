package com.webapper.installer.apk;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.util.Collections;

/** Signs an APK with APK Signature Scheme v2 + v3 using Google's apksig library. */
public final class ApkSigning {
    private ApkSigning() {}

    public static void sign(File unsigned, File signed, SigningKey key, int minSdk) throws Exception {
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(
                "WEBAPPER", key.privateKey, Collections.singletonList(key.certificate)).build();
        ApkSigner.Builder b = new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true);
        V3.enable(b);
        b.build().sign();
    }

    /** v3 is only available in newer apksig releases; enable it reflectively when present. */
    private static final class V3 {
        static void enable(ApkSigner.Builder b) {
            try {
                ApkSigner.Builder.class.getMethod("setV3SigningEnabled", boolean.class).invoke(b, true);
            } catch (ReflectiveOperationException ignored) {
                // v2 alone is sufficient for Android 7+.
            }
        }
    }
}
