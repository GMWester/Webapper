package com.webapper.installer.apk;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Minimal, fast APK (zip) rewriter. Unchanged entries are copied byte-for-byte without
 * recompression (GeckoView's native libraries are >100 MB, so this matters on a phone), entries
 * can be dropped, replaced or added, and every uncompressed entry is 4-byte aligned (16 KiB for
 * .so files) the way zipalign/apksigner do it, so resources.arsc stays mmap-able.
 */
public final class ZipRewriter {
    public enum Action { KEEP, DROP, REPLACE }

    public interface Rules {
        Action action(String name);

        /** Called for {@link Action#REPLACE} entries with the original, uncompressed data. */
        default byte[] replace(String name, byte[] original) throws IOException {
            return original;
        }
    }

    public static final class NewEntry {
        final byte[] data;
        final boolean store;

        public NewEntry(byte[] data, boolean store) {
            this.data = data;
            this.store = store;
        }
    }

    private static final int LOCAL_SIG = 0x04034b50;
    private static final int CENTRAL_SIG = 0x02014b50;
    private static final int EOCD_SIG = 0x06054b50;
    private static final int DOS_TIME = 0x0821; // 01:01:02
    private static final int DOS_DATE = 0x0221; // 1981-01-01
    private static final short ALIGNMENT_EXTRA_ID = (short) 0xD935;

    private ZipRewriter() {}

    private static final class In {
        String name;
        int method, crc, flags;
        long csize, usize, localOffset;
    }

    private static List<In> readCentralDirectory(RandomAccessFile f) throws IOException {
        long len = f.length();
        int scan = (int) Math.min(len, 65557);
        byte[] tail = new byte[scan];
        f.seek(len - scan);
        f.readFully(tail);
        ByteBuffer t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = scan - 22; i >= 0; i--) {
            if (t.getInt(i) == EOCD_SIG) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("not a zip file");
        int count = t.getShort(eocd + 10) & 0xFFFF;
        long cdSize = t.getInt(eocd + 12) & 0xFFFFFFFFL;
        long cdOffset = t.getInt(eocd + 16) & 0xFFFFFFFFL;
        byte[] cd = new byte[(int) cdSize];
        f.seek(cdOffset);
        f.readFully(cd);
        ByteBuffer c = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);
        List<In> out = new ArrayList<>(count);
        int p = 0;
        for (int i = 0; i < count; i++) {
            if (c.getInt(p) != CENTRAL_SIG) throw new IOException("bad central directory");
            In e = new In();
            e.flags = c.getShort(p + 8) & 0xFFFF;
            e.method = c.getShort(p + 10) & 0xFFFF;
            e.crc = c.getInt(p + 16);
            e.csize = c.getInt(p + 20) & 0xFFFFFFFFL;
            e.usize = c.getInt(p + 24) & 0xFFFFFFFFL;
            int n = c.getShort(p + 28) & 0xFFFF;
            int x = c.getShort(p + 30) & 0xFFFF;
            int m = c.getShort(p + 32) & 0xFFFF;
            e.localOffset = c.getInt(p + 42) & 0xFFFFFFFFL;
            e.name = new String(cd, p + 46, n, StandardCharsets.UTF_8);
            out.add(e);
            p += 46 + n + x + m;
        }
        return out;
    }

    private static long dataOffset(RandomAccessFile f, In e) throws IOException {
        byte[] h = new byte[30];
        f.seek(e.localOffset);
        f.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != LOCAL_SIG) throw new IOException("bad local header for " + e.name);
        return e.localOffset + 30 + (b.getShort(26) & 0xFFFF) + (b.getShort(28) & 0xFFFF);
    }

    /** Reads and inflates one entry of an open zip. */
    private static byte[] readEntry(RandomAccessFile f, In e) throws IOException {
        byte[] raw = new byte[(int) e.csize];
        f.seek(dataOffset(f, e));
        f.readFully(raw);
        if (e.method == 0) return raw;
        Inflater inf = new Inflater(true);
        inf.setInput(raw);
        byte[] out = new byte[(int) e.usize];
        try {
            int n = 0;
            while (n < out.length && !inf.finished()) {
                int r = inf.inflate(out, n, out.length - n);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += r;
            }
        } catch (DataFormatException ex) {
            throw new IOException("corrupt entry " + e.name, ex);
        } finally {
            inf.end();
        }
        return out;
    }

    /** Reads one entry from a zip file (null when missing). */
    public static byte[] read(File zip, String name) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(zip, "r")) {
            for (In e : readCentralDirectory(f)) if (e.name.equals(name)) return readEntry(f, e);
        }
        return null;
    }

    /** Lists entry names. */
    public static List<String> list(File zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(zip, "r")) {
            for (In e : readCentralDirectory(f)) names.add(e.name);
        }
        return names;
    }

    private static final class CountingOut extends OutputStream {
        final OutputStream out;
        long count;

        CountingOut(OutputStream out) { this.out = out; }

        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); count += l; }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.close(); }
    }

    private static final class Written {
        String name;
        int method, crc, flags;
        long csize, usize, offset;
        byte[] extra;
    }

    private static int alignmentFor(String name) {
        return name.endsWith(".so") ? 16384 : 4;
    }

    private static byte[] alignmentExtra(long headerStart, int nameLen, int align) {
        long dataStart = headerStart + 30 + nameLen;
        // Extra field: id(2) size(2) alignment(2) padding(n)
        long afterMin = dataStart + 6;
        int pad = (int) ((align - (afterMin % align)) % align);
        ByteBuffer b = ByteBuffer.allocate(6 + pad).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(ALIGNMENT_EXTRA_ID);
        b.putShort((short) (2 + pad));
        b.putShort((short) align);
        return b.array();
    }

    private static void writeEntry(CountingOut out, List<Written> cd, String name, int method, int crc,
                                   long usize, byte[] data, long dataLen, RandomAccessFile src, long srcOffset)
            throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        Written w = new Written();
        w.name = name;
        w.method = method;
        w.crc = crc;
        w.csize = dataLen;
        w.usize = usize;
        w.offset = out.count;
        w.flags = 0x0800; // UTF-8 names, no data descriptor
        w.extra = method == 0 ? alignmentExtra(out.count, nameBytes.length, alignmentFor(name)) : new byte[0];
        ByteBuffer h = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(LOCAL_SIG).putShort((short) (method == 0 ? 10 : 20)).putShort((short) w.flags)
                .putShort((short) method).putShort((short) DOS_TIME).putShort((short) DOS_DATE)
                .putInt(crc).putInt((int) dataLen).putInt((int) usize)
                .putShort((short) nameBytes.length).putShort((short) w.extra.length);
        out.write(h.array());
        out.write(nameBytes);
        out.write(w.extra);
        if (data != null) {
            out.write(data, 0, (int) dataLen);
        } else {
            byte[] buf = new byte[1 << 16];
            src.seek(srcOffset);
            long left = dataLen;
            while (left > 0) {
                int n = src.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("unexpected end of " + name);
                out.write(buf, 0, n);
                left -= n;
            }
        }
        cd.add(w);
    }

    private static void writeNew(CountingOut out, List<Written> cd, String name, byte[] data, boolean store)
            throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        if (store) {
            writeEntry(out, cd, name, 0, (int) crc.getValue(), data.length, data, data.length, null, 0);
            return;
        }
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        byte[] buf = new byte[65536];
        while (!d.finished()) {
            int n = d.deflate(buf);
            bo.write(buf, 0, n);
        }
        d.end();
        byte[] comp = bo.toByteArray();
        writeEntry(out, cd, name, 8, (int) crc.getValue(), data.length, comp, comp.length, null, 0);
    }

    /**
     * Copies {@code in} to {@code out} applying {@code rules}, then appends {@code added} entries
     * (in insertion order). Added entries replace same-named existing ones.
     */
    public static void rewrite(File in, File out, Rules rules, Map<String, NewEntry> added) throws IOException {
        Map<String, NewEntry> extra = added == null ? new LinkedHashMap<>() : new LinkedHashMap<>(added);
        List<Written> cd = new ArrayList<>();
        try (RandomAccessFile src = new RandomAccessFile(in, "r");
             CountingOut o = new CountingOut(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            for (In e : readCentralDirectory(src)) {
                if (e.name.endsWith("/")) continue; // directory entries
                if (extra.containsKey(e.name)) continue; // replaced by an added entry
                Action a = rules == null ? Action.KEEP : rules.action(e.name);
                if (a == Action.DROP) continue;
                if (a == Action.REPLACE) {
                    byte[] data = rules.replace(e.name, readEntry(src, e));
                    if (data != null) writeNew(o, cd, e.name, data, e.method == 0);
                    continue;
                }
                writeEntry(o, cd, e.name, e.method, e.crc, e.usize, null, e.csize, src, dataOffset(src, e));
            }
            for (Map.Entry<String, NewEntry> e : extra.entrySet()) {
                writeNew(o, cd, e.getKey(), e.getValue().data, e.getValue().store);
            }
            long cdStart = o.count;
            for (Written w : cd) {
                byte[] nameBytes = w.name.getBytes(StandardCharsets.UTF_8);
                ByteBuffer h = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
                h.putInt(CENTRAL_SIG).putShort((short) 20).putShort((short) (w.method == 0 ? 10 : 20))
                        .putShort((short) w.flags).putShort((short) w.method)
                        .putShort((short) DOS_TIME).putShort((short) DOS_DATE)
                        .putInt(w.crc).putInt((int) w.csize).putInt((int) w.usize)
                        .putShort((short) nameBytes.length).putShort((short) 0).putShort((short) 0)
                        .putShort((short) 0).putShort((short) 0).putInt(0).putInt((int) w.offset);
                o.write(h.array());
                o.write(nameBytes);
            }
            long cdSize = o.count - cdStart;
            ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            eocd.putInt(EOCD_SIG).putShort((short) 0).putShort((short) 0)
                    .putShort((short) cd.size()).putShort((short) cd.size())
                    .putInt((int) cdSize).putInt((int) cdStart).putShort((short) 0);
            o.write(eocd.array());
        }
    }
}
