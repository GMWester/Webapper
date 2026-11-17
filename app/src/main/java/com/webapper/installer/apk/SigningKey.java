package com.webapper.installer.apk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The installer's own APK signing key: a 3072-bit RSA key with a self-signed X.509 certificate,
 * created on first use and kept in the installer's private storage. Every generated app is
 * signed with it, so re-creating an app with the same package name installs as an update.
 * The DER encoding is done by hand because Android has no public certificate-builder API.
 */
public final class SigningKey {
    public final PrivateKey privateKey;
    public final X509Certificate certificate;

    private SigningKey(PrivateKey key, X509Certificate cert) {
        this.privateKey = key;
        this.certificate = cert;
    }

    public static synchronized SigningKey loadOrCreate(File dir, String commonName)
            throws IOException, GeneralSecurityException {
        File keyFile = new File(dir, "signing-key.pk8");
        File certFile = new File(dir, "signing-cert.der");
        if (keyFile.exists() && certFile.exists()) {
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
            return new SigningKey(key, cert);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(3072, new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        byte[] der = selfSigned(kp, commonName);
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Files.write(keyFile.toPath(), kp.getPrivate().getEncoded());
        Files.write(certFile.toPath(), der);
        return new SigningKey(kp.getPrivate(), cert);
    }

    // ---------------------------------------------------------------- DER helpers

    private static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        byte[] v = body.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = v.length;
        if (len < 0x80) {
            o.write(len);
        } else if (len < 0x100) {
            o.write(0x81);
            o.write(len);
        } else if (len < 0x10000) {
            o.write(0x82);
            o.write(len >> 8);
            o.write(len & 0xFF);
        } else {
            o.write(0x83);
            o.write(len >> 16);
            o.write((len >> 8) & 0xFF);
            o.write(len & 0xFF);
        }
        o.write(v, 0, v.length);
        return o.toByteArray();
    }

    private static byte[] seq(byte[]... parts) { return tlv(0x30, parts); }

    private static byte[] integer(BigInteger v) { return tlv(0x02, v.toByteArray()); }

    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int a = arcs[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (a & 0x7F);
                a >>>= 7;
            } while (a != 0);
            for (int k = n - 1; k >= 0; k--) o.write(tmp[k] | (k > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    private static byte[] time(Date d) {
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        SimpleDateFormat gen = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        gen.setTimeZone(TimeZone.getTimeZone("UTC"));
        int year = Integer.parseInt(gen.format(d).substring(0, 4));
        return year < 2050 ? tlv(0x17, utc.format(d).getBytes()) : tlv(0x18, gen.format(d).getBytes());
    }

    private static byte[] name(String cn) {
        byte[] atv = seq(oid(2, 5, 4, 3), tlv(0x0C, cn.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        byte[] org = seq(oid(2, 5, 4, 10), tlv(0x0C, "WebApper".getBytes()));
        return seq(tlv(0x31, atv), tlv(0x31, org));
    }

    static byte[] selfSigned(KeyPair kp, String cn) throws GeneralSecurityException {
        byte[] sha256WithRsa = seq(oid(1, 2, 840, 113549, 1, 1, 11), new byte[]{0x05, 0x00});
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24L * 3600 * 1000);
        Date notAfter = new Date(now + 100L * 365 * 24 * 3600 * 1000); // ~100 years
        byte[] serial = integer(new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE));
        byte[] tbs = seq(
                tlv(0xA0, integer(BigInteger.valueOf(2))), // v3
                serial,
                sha256WithRsa,
                name(cn),
                seq(time(notBefore), time(notAfter)),
                name(cn),
                kp.getPublic().getEncoded()); // SubjectPublicKeyInfo
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bitString = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bitString, 1, sig.length);
        return seq(tbs, sha256WithRsa, tlv(0x03, bitString));
    }
}
