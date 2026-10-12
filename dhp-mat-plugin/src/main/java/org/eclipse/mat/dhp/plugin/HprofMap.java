package org.eclipse.mat.dhp.plugin;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Read-only, memory-mapped view of an HPROF file for random access. Object inspection reads a few bytes at an arbitrary
 * offset millions of times, so system calls per read dominate; mapped windows turn those into memory loads and let
 * the page cache do the rest. Windows of 1 GiB are mapped on first use; values that straddle two windows are
 * assembled byte by byte. All methods are safe to call from several threads.
 */
final class HprofMap implements AutoCloseable {
    private static final int WINDOW_SHIFT = 30;
    private static final long WINDOW = 1L << WINDOW_SHIFT;
    private static final int OVERLAP = 1 << 20; // each window also maps the first MiB of the next, so most values never straddle

    private final RandomAccessFile file;
    private final FileChannel channel;
    private final long size;
    private final MappedByteBuffer[] windows;

    HprofMap(File f) throws IOException {
        this.file = new RandomAccessFile(f, "r");
        this.channel = file.getChannel();
        this.size = channel.size();
        this.windows = new MappedByteBuffer[(int) ((size + WINDOW - 1) >>> WINDOW_SHIFT)];
    }

    private MappedByteBuffer window(int w) throws IOException {
        MappedByteBuffer b = windows[w];
        if (b == null) {
            synchronized (this) {
                b = windows[w];
                if (b == null) {
                    long start = (long) w << WINDOW_SHIFT;
                    b = channel.map(FileChannel.MapMode.READ_ONLY, start, Math.min(WINDOW + OVERLAP, size - start));
                    windows[w] = b;
                }
            }
        }
        return b;
    }

    private void check(long pos, int len) throws IOException {
        if (pos < 0 || pos + len > size) {
            throw new IOException("read of " + len + " bytes at " + pos + " is outside the dump file (" + size + " bytes)");
        }
    }

    byte getByte(long pos) throws IOException {
        check(pos, 1);
        return window((int) (pos >>> WINDOW_SHIFT)).get((int) (pos & (WINDOW - 1)));
    }

    int getInt(long pos) throws IOException {
        check(pos, 4);
        int off = (int) (pos & (WINDOW - 1));
        MappedByteBuffer b = window((int) (pos >>> WINDOW_SHIFT));
        if (off + 4 <= b.limit()) {
            return b.getInt(off);
        }
        return (int) assemble(pos, 4);
    }

    long getLong(long pos) throws IOException {
        check(pos, 8);
        int off = (int) (pos & (WINDOW - 1));
        MappedByteBuffer b = window((int) (pos >>> WINDOW_SHIFT));
        if (off + 8 <= b.limit()) {
            return b.getLong(off);
        }
        return assemble(pos, 8);
    }

    short getShort(long pos) throws IOException {
        return (short) assemble(pos, 2);
    }

    char getChar(long pos) throws IOException {
        return (char) assemble(pos, 2);
    }

    /** Big-endian value of {@code n} bytes starting at {@code pos} (n &lt;= 8). */
    private long assemble(long pos, int n) throws IOException {
        check(pos, n);
        long v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 8) | (getByte(pos + i) & 0xFFL);
        }
        return v;
    }

    /** Copies {@code len} bytes starting at {@code pos} into {@code dst}. */
    void copy(long pos, byte[] dst, int dstOff, int len) throws IOException {
        check(pos, len);
        while (len > 0) {
            int w = (int) (pos >>> WINDOW_SHIFT);
            int off = (int) (pos & (WINDOW - 1));
            MappedByteBuffer b = window(w);
            int n = (int) Math.min(len, b.limit() - off);
            b.get(off, dst, dstOff, n);
            pos += n;
            dstOff += n;
            len -= n;
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
