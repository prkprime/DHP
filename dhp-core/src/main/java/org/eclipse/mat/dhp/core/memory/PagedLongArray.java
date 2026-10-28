package org.eclipse.mat.dhp.core.memory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Indexed {@code long} storage backed by a chunked memory-mapped temporary file, so that large per-object
 * columns stay off the JVM heap. Writes and reads go through the OS page cache.
 *
 * <p>Each element is 8 bytes, mapped in 1 GiB chunks. The file is deleted on {@link #close()}.</p>
 */
public final class PagedLongArray implements AutoCloseable {
    private static final long CHUNK_BYTES = 1024L * 1024 * 1024;
    private static final int LONGS_PER_CHUNK = (int) (CHUNK_BYTES / 8);

    private final RandomAccessFile raf;
    private final MappedByteBuffer[] buffers;
    private final File file;
    private final int capacity;
    private boolean closed;

    public PagedLongArray(File tempDir, String prefix, int capacity) throws IOException {
        if (capacity < 0) {
            throw new IllegalArgumentException("Negative capacity: " + capacity);
        }
        this.capacity = capacity;
        File dir = (tempDir != null && tempDir.isDirectory()) ? tempDir : null;
        this.file = File.createTempFile(prefix, ".tmp", dir);
        this.file.deleteOnExit();
        long totalBytes = (long) capacity * 8L;
        this.raf = new RandomAccessFile(file, "rw");
        raf.setLength(totalBytes);
        FileChannel channel = raf.getChannel();
        int numChunks = (int) ((totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES);
        this.buffers = new MappedByteBuffer[Math.max(1, numChunks)];
        long offset = 0;
        for (int i = 0; i < numChunks; i++) {
            long size = Math.min(CHUNK_BYTES, totalBytes - offset);
            buffers[i] = channel.map(FileChannel.MapMode.READ_WRITE, offset, size);
            offset += size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public long get(int index) {
        return buffers[index / LONGS_PER_CHUNK].getLong((index % LONGS_PER_CHUNK) * 8);
    }

    public void set(int index, long value) {
        buffers[index / LONGS_PER_CHUNK].putLong((index % LONGS_PER_CHUNK) * 8, value);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (MappedByteBuffer buf : buffers) {
            MappedBuffers.unmap(buf);
        }
        try {
            raf.close();
        } catch (IOException ignored) {
            // the mapping is gone, the file will be deleted by the OS on exit if at all
        }
        if (file.exists()) {
            file.delete();
        }
    }
}