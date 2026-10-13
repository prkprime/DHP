package org.eclipse.mat.dhp.storage.jdbc;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * Append-only file of chunk blobs that lives next to a SQLite database ({@code <db>.chunks}) and replaces the
 * {@code dhp_chunks} table there. Reading a chunk through JDBC costs a statement, a result set and a B-tree seek
 * (about 1 us); here it is a directory lookup and a memory copy from a mapped window (about 0.1 us), which is what
 * every index lookup of the MAT UI boils down to.
 *
 * <p>Layout: {@code [records][trailer][trailerStart: long][magic: int]}. A record is {@code [length: int][bytes]}.
 * The trailer holds one directory per chunk kind: dense kinds map {@code chunk number -> record offset} (-1 when
 * absent), sparse kinds (out-of-line lists, class instance lists) a sorted key/offset list. All numbers are little
 * endian.</p>
 *
 * <p>While the file is being written, reads flush and use positional reads; after {@link #finish()} (or when an
 * existing file is opened) the data is mapped in read-only windows and the directories are read through mapped
 * views, so lookups take no lock and the directory costs no heap.</p>
 */
final class ChunkFile implements AutoCloseable {
    private static final int MAGIC = 0x44484331; // "DHC1"
    private static final int WINDOW_SHIFT = 30;
    private static final long WINDOW = 1L << WINDOW_SHIFT;
    private static final int WRITE_BUFFER = 8 << 20;
    private static final int MAX_KIND = 32;

    private final File file;
    private RandomAccessFile raf;
    private FileChannel channel;
    private final boolean[] sparse = new boolean[MAX_KIND];

    // write phase
    private ByteBuffer wbuf;
    private long bufStart; // file offset of the first byte in wbuf
    private long[][] dense = new long[MAX_KIND][];
    private int[] denseCount = new int[MAX_KIND];
    private Long2LongOpenHashMap sparseDir = new Long2LongOpenHashMap();

    // read phase
    private volatile boolean readOnly;
    private long dataEnd;
    private MappedByteBuffer[] windows;
    private LongBuffer[] denseView = new LongBuffer[MAX_KIND];
    private long[] sparseKeys = new long[0];
    private long[] sparseOffs = new long[0];
    private MappedByteBuffer trailer;

    private ChunkFile(File file, int[] sparseKinds) {
        this.file = file;
        for (int k : sparseKinds) {
            sparse[k] = true;
        }
        sparseDir.defaultReturnValue(-1L);
    }

    /** Starts a new, empty chunk file (an existing file is truncated). */
    static ChunkFile create(File file, int[] sparseKinds) throws IOException {
        ChunkFile f = new ChunkFile(file, sparseKinds);
        f.raf = new RandomAccessFile(file, "rw");
        f.raf.setLength(0);
        f.channel = f.raf.getChannel();
        f.wbuf = ByteBuffer.allocateDirect(WRITE_BUFFER).order(ByteOrder.LITTLE_ENDIAN);
        return f;
    }

    /** Opens a finished chunk file for reading. */
    static ChunkFile open(File file, int[] sparseKinds) throws IOException {
        ChunkFile f = new ChunkFile(file, sparseKinds);
        f.raf = new RandomAccessFile(file, "r");
        f.channel = f.raf.getChannel();
        try {
            f.mapForReading();
        } catch (IOException | RuntimeException e) {
            f.closeAndUnmap(); // do not leak the handle of a file that is not usable (Windows could not delete it)
            throw e;
        }
        return f;
    }

    boolean isReadOnly() {
        return readOnly;
    }

    // ------------------------------------------------------------------------------------------------ writing

    synchronized void put(int kind, long chunkNo, byte[] data) throws IOException {
        if (readOnly) {
            throw new IOException("chunk file " + file + " is finished");
        }
        long off = bufStart + wbuf.position();
        if (wbuf.remaining() < 4) {
            flushBuffer();
        }
        wbuf.putInt(data.length);
        int pos = 0;
        while (pos < data.length) { // blobs can be larger than the buffer
            if (!wbuf.hasRemaining()) {
                flushBuffer();
            }
            int n = Math.min(wbuf.remaining(), data.length - pos);
            wbuf.put(data, pos, n);
            pos += n;
        }
        if (sparse[kind]) {
            sparseDir.put(((long) kind << 32) | chunkNo, off);
        } else {
            int c = Math.toIntExact(chunkNo);
            long[] d = dense[kind];
            if (d == null || c >= d.length) {
                int old = d == null ? 0 : d.length;
                d = d == null ? new long[Math.max(1024, c + 1)] : Arrays.copyOf(d, Math.max(c + 1, old + (old >> 1)));
                Arrays.fill(d, old, d.length, -1L);
                dense[kind] = d;
            }
            d[c] = off;
            denseCount[kind] = Math.max(denseCount[kind], c + 1);
        }
    }

    private void flushBuffer() throws IOException {
        wbuf.flip();
        while (wbuf.hasRemaining()) {
            bufStart += channel.write(wbuf, bufStart);
        }
        wbuf.clear();
    }

    /** Writes the directories, makes the file durable and switches to the memory-mapped read path. */
    synchronized void finish() throws IOException {
        if (readOnly) {
            return;
        }
        flushBuffer();
        long trailerStart = bufStart;
        ByteBuffer t = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
        long p = trailerStart;
        for (int kind = 0; kind < MAX_KIND; kind++) {
            if (sparse[kind]) {
                continue;
            }
            int count = denseCount[kind];
            p = writeInts(t, p, kind, count);
            long[] d = dense[kind];
            for (int i = 0; i < count; i += 1 << 14) {
                int n = Math.min(1 << 14, count - i);
                ByteBuffer b = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN);
                b.asLongBuffer().put(d, i, n);
                b.limit(n * 8);
                while (b.hasRemaining()) {
                    p += channel.write(b, p);
                }
            }
        }
        // sparse kinds: sorted (key, offset) pairs
        long[] keys = sparseDir.keySet().toLongArray();
        Arrays.sort(keys);
        p = writeInts(t, p, -1, keys.length);
        ByteBuffer b = ByteBuffer.allocate(Math.max(16, keys.length * 16)).order(ByteOrder.LITTLE_ENDIAN);
        for (long k : keys) {
            b.putLong(k).putLong(sparseDir.get(k));
        }
        b.flip();
        while (b.hasRemaining()) {
            p += channel.write(b, p);
        }
        ByteBuffer foot = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        foot.putLong(trailerStart).putInt(MAGIC).flip();
        while (foot.hasRemaining()) {
            p += channel.write(foot, p);
        }
        channel.force(true);
        wbuf = null;
        dense = new long[MAX_KIND][];
        sparseDir = null;
        mapForReading();
    }

    private long writeInts(ByteBuffer scratch, long pos, int a, int b) throws IOException {
        scratch.clear();
        scratch.putInt(a).putInt(b).flip();
        while (scratch.hasRemaining()) {
            pos += channel.write(scratch, pos);
        }
        return pos;
    }

    // ------------------------------------------------------------------------------------------------ reading

    private void mapForReading() throws IOException {
        long size = channel.size();
        if (size < 12) {
            throw new IOException("chunk file " + file + " is truncated");
        }
        ByteBuffer foot = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        while (foot.hasRemaining()) {
            if (channel.read(foot, size - 12 + foot.position()) < 0) {
                throw new IOException("chunk file " + file + " is truncated");
            }
        }
        foot.flip();
        long trailerStart = foot.getLong();
        if (foot.getInt() != MAGIC || trailerStart < 0 || trailerStart > size - 12) {
            throw new IOException("chunk file " + file + " is incomplete (ingestion did not finish?)");
        }
        dataEnd = trailerStart;
        long tlen = size - 12 - trailerStart;
        if (tlen >= Integer.MAX_VALUE) {
            throw new IOException("chunk file directory too large");
        }
        trailer = channel.map(FileChannel.MapMode.READ_ONLY, trailerStart, tlen);
        trailer.order(ByteOrder.LITTLE_ENDIAN);
        int pos = 0;
        for (int kind = 0; kind < MAX_KIND; kind++) {
            if (sparse[kind]) {
                continue;
            }
            int k = trailer.getInt(pos);
            int count = trailer.getInt(pos + 4);
            pos += 8;
            if (k != kind) {
                throw new IOException("chunk file " + file + " has a corrupt directory");
            }
            denseView[kind] = trailer.slice(pos, count * 8).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
            pos += count * 8;
        }
        int sparseCount = trailer.getInt(pos + 4);
        pos += 8;
        sparseKeys = new long[sparseCount];
        sparseOffs = new long[sparseCount];
        for (int i = 0; i < sparseCount; i++) {
            sparseKeys[i] = trailer.getLong(pos + i * 16);
            sparseOffs[i] = trailer.getLong(pos + i * 16 + 8);
        }
        windows = new MappedByteBuffer[(int) ((dataEnd + WINDOW - 1) >>> WINDOW_SHIFT)];
        readOnly = true;
    }

    private long offsetOf(int kind, long chunkNo) {
        if (sparse[kind]) {
            if (readOnly) {
                int i = Arrays.binarySearch(sparseKeys, ((long) kind << 32) | chunkNo);
                return i < 0 ? -1L : sparseOffs[i];
            }
            return sparseDir.get(((long) kind << 32) | chunkNo);
        }
        if (readOnly) {
            LongBuffer v = denseView[kind];
            return v != null && chunkNo >= 0 && chunkNo < v.limit() ? v.get((int) chunkNo) : -1L;
        }
        long[] d = dense[kind];
        return d != null && chunkNo >= 0 && chunkNo < denseCount[kind] ? d[(int) chunkNo] : -1L;
    }

    /** The bytes of a chunk, or null if it does not exist. */
    byte[] read(int kind, long chunkNo) throws IOException {
        if (!readOnly) {
            synchronized (this) {
                if (!readOnly) {
                    return readWhileWriting(kind, chunkNo);
                }
            }
        }
        long off = offsetOf(kind, chunkNo);
        if (off < 0) {
            return null;
        }
        byte[] len4 = new byte[4];
        copy(off, len4, 4);
        int len = (len4[0] & 0xFF) | (len4[1] & 0xFF) << 8 | (len4[2] & 0xFF) << 16 | (len4[3] & 0xFF) << 24;
        byte[] b = new byte[len];
        copy(off + 4, b, len);
        return b;
    }

    private byte[] readWhileWriting(int kind, long chunkNo) throws IOException {
        long off = offsetOf(kind, chunkNo);
        if (off < 0) {
            return null;
        }
        if (wbuf.position() > 0) {
            flushBuffer();
        }
        ByteBuffer len4 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        readFully(len4, off);
        byte[] b = new byte[len4.getInt(0)];
        readFully(ByteBuffer.wrap(b), off + 4);
        return b;
    }

    private void readFully(ByteBuffer dst, long pos) throws IOException {
        while (dst.hasRemaining()) {
            int n = channel.read(dst, pos + dst.position());
            if (n < 0) {
                throw new IOException("unexpected end of chunk file " + file);
            }
        }
    }

    private void copy(long pos, byte[] dst, int len) throws IOException {
        int done = 0;
        while (done < len) {
            long p = pos + done;
            int w = (int) (p >>> WINDOW_SHIFT);
            MappedByteBuffer win = windows[w];
            if (win == null) {
                win = mapWindow(w);
            }
            int off = (int) (p & (WINDOW - 1));
            int n = Math.min(len - done, win.limit() - off);
            win.get(off, dst, done, n);
            done += n;
        }
    }

    private synchronized MappedByteBuffer mapWindow(int w) throws IOException {
        MappedByteBuffer b = windows[w];
        if (b == null) {
            long start = (long) w << WINDOW_SHIFT;
            b = channel.map(FileChannel.MapMode.READ_ONLY, start, Math.min(WINDOW, dataEnd - start));
            windows[w] = b;
        }
        return b;
    }

    /** Number of chunk slots of a dense kind (also counts absent ones). */
    int chunkCount(int kind) {
        if (readOnly) {
            LongBuffer v = denseView[kind];
            return v == null ? 0 : v.limit();
        }
        return denseCount[kind];
    }

    /** Releases the file handle. Mappings stay valid for readers that are still running; the GC releases them. */
    @Override
    public synchronized void close() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            closeAndUnmap(); // Windows refuses to delete (or truncate) a file that is still mapped
            return;
        }
        if (raf != null) {
            wbuf = null; // an unfinished file has no directory and is truncated by the next ingestion
            raf.close();
            raf = null;
        }
    }

    /** Closes the file and unmaps it so that it can be deleted (Windows refuses to delete mapped files). */
    synchronized void closeAndUnmap() throws IOException {
        MappedByteBuffer[] ws = windows;
        windows = null;
        if (ws != null) {
            for (MappedByteBuffer b : ws) {
                unmap(b);
            }
        }
        unmap(trailer);
        trailer = null;
        if (raf != null) {
            wbuf = null;
            raf.close();
            raf = null;
        }
    }

    private static void unmap(MappedByteBuffer b) {
        if (b == null) {
            return;
        }
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            ((sun.misc.Unsafe) f.get(null)).invokeCleaner(b);
        } catch (Throwable ignored) {
            // the mapping is released when the buffer is garbage collected
        }
    }
}
