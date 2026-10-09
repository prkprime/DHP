package org.eclipse.mat.dhp.core.memory;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Off-heap memory-mapped sorted address mapping.
 * Uses 0 MB of JVM heap memory, relying directly on OS page cache.
 * Enables DHP to parse dumps containing hundreds of millions of objects
 * even within tight 512MB / 1GB JVM heap limits.
 */
public class MmapAddressToIdMap implements IAddressToIdMap {
    private static final Logger log = LoggerFactory.getLogger(MmapAddressToIdMap.class);

    private final Long2IntOpenHashMap classAddressToId;
    private final File tempFile;
    private final RandomAccessFile raf;
    private final MappedByteBuffer[] buffers;
    private final int instanceCount;
    private final int baseInstanceId;
    private final int totalSize;

    // 1GB per mapped buffer chunk (must be multiple of 8 bytes for longs)
    private static final long CHUNK_BYTES = 1024L * 1024 * 1024;
    private static final int LONGS_PER_CHUNK = (int) (CHUNK_BYTES / 8);

    public MmapAddressToIdMap(Long2IntOpenHashMap classAddressToId, long[] sortedAddresses, int baseInstanceId, File tempDir) throws IOException {
        this(classAddressToId, sortedAddresses, sortedAddresses != null ? sortedAddresses.length : 0, baseInstanceId, tempDir);
    }

    public MmapAddressToIdMap(Long2IntOpenHashMap classAddressToId, long[] sortedAddresses, int instanceCount, int baseInstanceId, File tempDir) throws IOException {
        this.classAddressToId = classAddressToId != null ? classAddressToId : new Long2IntOpenHashMap();
        this.instanceCount = instanceCount;
        this.baseInstanceId = baseInstanceId;
        this.totalSize = this.classAddressToId.size() + instanceCount;

        File dir = (tempDir != null && tempDir.isDirectory()) ? tempDir : null;
        this.tempFile = File.createTempFile("dhp_addr_mmap_", ".tmp", dir);
        this.tempFile.deleteOnExit();

        long totalBytes = (long) instanceCount * 8L;
        this.raf = new RandomAccessFile(tempFile, "rw");
        this.raf.setLength(totalBytes);

        FileChannel channel = raf.getChannel();
        int numChunks = (int) ((totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES);
        this.buffers = new MappedByteBuffer[Math.max(1, numChunks)];

        long offset = 0;
        for (int i = 0; i < numChunks; i++) {
            long size = Math.min(CHUNK_BYTES, totalBytes - offset);
            this.buffers[i] = channel.map(FileChannel.MapMode.READ_WRITE, offset, size);
            offset += size;
        }

        // Stream populate mapped buffers
        for (int i = 0; i < instanceCount; i++) {
            int chunkIndex = i / LONGS_PER_CHUNK;
            int pos = (i % LONGS_PER_CHUNK) * 8;
            buffers[chunkIndex].putLong(pos, sortedAddresses[i]);
        }
        for (MappedByteBuffer buf : buffers) {
            buf.force();
        }
        log.info("Initialized MmapAddressToIdMap: {} items ({} MB) off-heap via {}",
                instanceCount, totalBytes / (1024 * 1024), tempFile.getAbsolutePath());
    }

    @Override
    public int get(long address) {
        if (classAddressToId.containsKey(address)) {
            return classAddressToId.get(address);
        }
        if (instanceCount == 0) return -1;

        int low = 0;
        int high = instanceCount - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int chunkIndex = mid / LONGS_PER_CHUNK;
            int pos = (mid % LONGS_PER_CHUNK) * 8;
            long midVal = buffers[chunkIndex].getLong(pos);

            if (midVal < address) {
                low = mid + 1;
            } else if (midVal > address) {
                high = mid - 1;
            } else {
                return baseInstanceId + mid;
            }
        }
        return -1;
    }

    @Override
    public boolean containsKey(long address) {
        return get(address) >= 0;
    }

    @Override
    public int size() {
        return totalSize;
    }

    @Override
    public void close() {
        if (buffers != null) {
            for (MappedByteBuffer buf : buffers) {
                unmap(buf);
            }
        }
        try {
            raf.close();
            if (tempFile.exists()) {
                tempFile.delete();
            }
        } catch (IOException e) {
            log.warn("Failed closing mmap address file: {}", e.getMessage());
        }
    }

    private static void unmap(MappedByteBuffer buffer) {
        if (buffer == null) return;
        try {
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            unsafe.invokeCleaner(buffer);
        } catch (Throwable t) {
            try {
                java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
                cleanerMethod.setAccessible(true);
                Object cleaner = cleanerMethod.invoke(buffer);
                if (cleaner != null) {
                    java.lang.reflect.Method cleanMethod = cleaner.getClass().getMethod("clean");
                    cleanMethod.setAccessible(true);
                    cleanMethod.invoke(cleaner);
                }
            } catch (Throwable ignored) {}
        }
    }
}
