package com.webapper.installer.apk;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rewrites strings inside a compiled (binary) Android XML file such as the AndroidManifest.xml
 * of an APK. The string pool is rebuilt from scratch, so replacements may have any length;
 * everything else in the file references strings by index and is copied unchanged.
 */
public final class AxmlPatcher {
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int UTF8_FLAG = 1 << 8;

    private AxmlPatcher() {}

    /** Decoded string pool, exposed for tests and tooling. */
    public static List<String> strings(byte[] axml) {
        return parse(axml).strings;
    }

    /**
     * @param exact     replace whole strings equal to a key (e.g. the app label)
     * @param substring replace every occurrence of a key inside any string (e.g. the package
     *                  name, which also appears in provider authorities and permission names)
     */
    public static byte[] patch(byte[] axml, Map<String, String> exact, Map<String, String> substring) {
        Pool pool = parse(axml);
        List<String> out = new ArrayList<>(pool.strings.size());
        for (String s : pool.strings) {
            String v = s;
            if (exact != null && exact.containsKey(v)) v = exact.get(v);
            if (substring != null) {
                for (Map.Entry<String, String> e : substring.entrySet()) v = v.replace(e.getKey(), e.getValue());
            }
            out.add(v);
        }
        byte[] newPool = encodePool(out, pool);
        ByteBuffer src = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        int fileHeaderSize = src.getShort(2) & 0xFFFF;
        int restStart = pool.chunkStart + pool.chunkSize;
        int total = fileHeaderSize + newPool.length + (axml.length - restStart);
        ByteBuffer dst = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        dst.put(axml, 0, fileHeaderSize);
        dst.putInt(4, total);
        dst.position(fileHeaderSize);
        dst.put(axml, fileHeaderSize, pool.chunkStart - fileHeaderSize); // normally nothing
        dst.put(newPool);
        dst.put(axml, restStart, axml.length - restStart);
        return dst.array();
    }

    private static final class Pool {
        int chunkStart, chunkSize, flags, styleCount;
        int[] styleOffsets;
        byte[] styleData;
        final List<String> strings = new ArrayList<>();
    }

    private static Pool parse(byte[] axml) {
        ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xFFFF) != RES_XML_TYPE) throw new IllegalArgumentException("not a binary XML file");
        int pos = b.getShort(2) & 0xFFFF;
        while (pos < axml.length && (b.getShort(pos) & 0xFFFF) != RES_STRING_POOL_TYPE) {
            pos += b.getInt(pos + 4);
        }
        if (pos >= axml.length) throw new IllegalArgumentException("no string pool");
        Pool p = new Pool();
        p.chunkStart = pos;
        int headerSize = b.getShort(pos + 2) & 0xFFFF;
        p.chunkSize = b.getInt(pos + 4);
        int stringCount = b.getInt(pos + 8);
        p.styleCount = b.getInt(pos + 12);
        p.flags = b.getInt(pos + 16);
        int stringsStart = b.getInt(pos + 20);
        int stylesStart = b.getInt(pos + 24);
        boolean utf8 = (p.flags & UTF8_FLAG) != 0;
        int offBase = pos + headerSize;
        for (int i = 0; i < stringCount; i++) {
            int at = pos + stringsStart + b.getInt(offBase + 4 * i);
            p.strings.add(utf8 ? readUtf8(b, at) : readUtf16(b, at));
        }
        p.styleOffsets = new int[p.styleCount];
        for (int i = 0; i < p.styleCount; i++) p.styleOffsets[i] = b.getInt(offBase + 4 * stringCount + 4 * i);
        if (p.styleCount > 0 && stylesStart > 0) {
            int from = pos + stylesStart;
            int to = pos + p.chunkSize;
            p.styleData = new byte[to - from];
            System.arraycopy(axml, from, p.styleData, 0, p.styleData.length);
        }
        return p;
    }

    private static String readUtf8(ByteBuffer b, int at) {
        int[] r = new int[1];
        at = readLen8(b, at, r); // UTF-16 length (unused)
        at = readLen8(b, at, r);
        byte[] bytes = new byte[r[0]];
        for (int i = 0; i < bytes.length; i++) bytes[i] = b.get(at + i);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readLen8(ByteBuffer b, int at, int[] out) {
        int v = b.get(at) & 0xFF;
        if ((v & 0x80) != 0) {
            out[0] = ((v & 0x7F) << 8) | (b.get(at + 1) & 0xFF);
            return at + 2;
        }
        out[0] = v;
        return at + 1;
    }

    private static String readUtf16(ByteBuffer b, int at) {
        int len = b.getShort(at) & 0xFFFF;
        at += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7FFF) << 16) | (b.getShort(at) & 0xFFFF);
            at += 2;
        }
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = (char) b.getShort(at + 2 * i);
        return new String(c);
    }

    private static void writeLen8(ByteArrayOutputStream o, int v) {
        if (v > 0x7FFF) throw new IllegalArgumentException("string too long");
        if (v > 0x7F) {
            o.write(0x80 | (v >> 8));
            o.write(v & 0xFF);
        } else {
            o.write(v);
        }
    }

    private static byte[] encodePool(List<String> strings, Pool old) {
        boolean utf8 = (old.flags & UTF8_FLAG) != 0;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = data.size();
            String s = strings.get(i);
            if (utf8) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                writeLen8(data, s.length());
                writeLen8(data, bytes.length);
                data.write(bytes, 0, bytes.length);
                data.write(0);
            } else {
                int len = s.length();
                if (len > 0x7FFF) {
                    int hi = 0x8000 | (len >> 16);
                    data.write(hi & 0xFF);
                    data.write(hi >> 8);
                }
                data.write(len & 0xFF);
                data.write((len >> 8) & 0xFF);
                for (int k = 0; k < len; k++) {
                    char ch = s.charAt(k);
                    data.write(ch & 0xFF);
                    data.write(ch >> 8);
                }
                data.write(0);
                data.write(0);
            }
        }
        while (data.size() % 4 != 0) data.write(0);
        int headerSize = 28;
        int stringsStart = headerSize + 4 * strings.size() + 4 * old.styleCount;
        int styleLen = old.styleData == null ? 0 : old.styleData.length;
        int stylesStart = old.styleCount > 0 ? stringsStart + data.size() : 0;
        int size = stringsStart + data.size() + styleLen;
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) RES_STRING_POOL_TYPE);
        out.putShort((short) headerSize);
        out.putInt(size);
        out.putInt(strings.size());
        out.putInt(old.styleCount);
        out.putInt(old.flags & ~1); // clear SORTED_FLAG: order may no longer be sorted
        out.putInt(stringsStart);
        out.putInt(stylesStart);
        for (int off : offsets) out.putInt(off);
        for (int off : old.styleOffsets) out.putInt(off);
        out.put(data.toByteArray());
        if (styleLen > 0) out.put(old.styleData);
        return out.array();
    }
}
