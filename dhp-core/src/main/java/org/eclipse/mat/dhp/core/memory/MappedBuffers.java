package org.eclipse.mat.dhp.core.memory;

import java.nio.MappedByteBuffer;

/** Releases memory-mapped files explicitly (Java offers no public API; Windows cannot delete a file that is still mapped). */
public final class MappedBuffers {
    private MappedBuffers() {}

    /** True on Windows, where mapped files cannot be deleted or replaced and must be unmapped when closed. */
    public static boolean mustUnmapOnClose() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Unmaps the buffer. The buffer must not be used afterwards (access would crash the JVM), so only call this when no
     * reader can still be running. Does nothing if the JDK refuses; the mapping is then released by the garbage collector.
     */
    public static void unmap(MappedByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            ((sun.misc.Unsafe) f.get(null)).invokeCleaner(buffer);
        } catch (Throwable ignored) {
            // released by the GC
        }
    }
}
