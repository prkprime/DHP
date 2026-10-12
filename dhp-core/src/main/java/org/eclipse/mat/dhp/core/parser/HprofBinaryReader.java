package org.eclipse.mat.dhp.core.parser;

import org.eclipse.mat.dhp.core.model.HprofConstants;
import org.eclipse.mat.dhp.core.model.HeapRecords;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;

/**
 * Fast streaming binary reader for HPROF files.
 *
 * <p>Uses a private, unsynchronised 1 MB buffer with array-based big-endian decoding. Plain files are read with
 * positional {@link FileChannel} reads so that large skips (primitive array payloads) become real seeks instead of
 * being read and discarded. Gzip-compressed files are read through (they cannot be seeked).</p>
 */
public class HprofBinaryReader implements AutoCloseable {

    private static final int BUFFER_SIZE = 1 << 20;
    private static final VarHandle SHORT_BE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_BE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private final FileChannel channel;   // null when reading a compressed stream
    private final InputStream stream;    // null when reading a plain file
    private final long fileSize;
    private final byte[] buf = new byte[BUFFER_SIZE];
    private final ByteBuffer byteBuffer = ByteBuffer.wrap(buf);

    /** Stream position of buf[0]. The logical position is {@code bufBase + bufPos}. */
    private long bufBase = 0;
    private int bufPos = 0;
    private int bufLimit = 0;
    private int idSize = 8;

    public HprofBinaryReader(File file) throws IOException {
        if (file.getName().endsWith(".gz")) {
            this.channel = null;
            this.stream = new GZIPInputStream(new FileInputStream(file), 64 * 1024);
            this.fileSize = -1;
        } else {
            this.channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
            this.stream = null;
            this.fileSize = channel.size();
        }
    }

    public HeapRecords.Header readHeader() throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = readByte()) != 0) {
            if (b == -1) {
                throw new IOException("Unexpected end of file while reading HPROF header");
            }
            sb.append((char) b);
            if (sb.length() > 128) {
                throw new IOException("Invalid HPROF header: magic string exceeded 128 characters: " + sb);
            }
        }
        String version = sb.toString();
        this.idSize = readInt();
        if (idSize != 4 && idSize != 8) {
            throw new IOException("Unsupported HPROF identifier size: " + idSize);
        }
        long creationTime = readLong();
        return new HeapRecords.Header(version, idSize, creationTime);
    }

    public int getIdSize() {
        return idSize;
    }

    public long getPosition() {
        return bufBase + bufPos;
    }

    /**
     * Makes at least {@code n} (n &lt;= buffer size) bytes available in the buffer.
     * @return false if the end of the stream was hit first
     */
    private boolean fill(int n) throws IOException {
        int remaining = bufLimit - bufPos;
        if (remaining >= n) {
            return true;
        }
        if (remaining > 0) {
            System.arraycopy(buf, bufPos, buf, 0, remaining);
        }
        bufBase += bufPos;
        bufPos = 0;
        bufLimit = remaining;
        while (bufLimit < n) {
            int read;
            if (channel != null) {
                byteBuffer.limit(buf.length).position(bufLimit);
                read = channel.read(byteBuffer, bufBase + bufLimit);
            } else {
                read = stream.read(buf, bufLimit, buf.length - bufLimit);
            }
            if (read < 0) {
                return false;
            }
            bufLimit += read;
        }
        return true;
    }

    public int readByte() throws IOException {
        if (bufPos == bufLimit && !fill(1)) {
            return -1;
        }
        return buf[bufPos++] & 0xFF;
    }

    public boolean readBoolean() throws IOException {
        int b = readByte();
        if (b < 0) {
            throw new IOException("Unexpected EOF while reading boolean");
        }
        return b != 0;
    }

    public char readChar() throws IOException {
        return (char) readUnsignedShort();
    }

    public short readShort() throws IOException {
        return (short) readUnsignedShort();
    }

    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    public int readUnsignedShort() throws IOException {
        if (bufLimit - bufPos < 2 && !fill(2)) {
            throw new IOException("Unexpected EOF while reading unsigned short");
        }
        int v = (short) SHORT_BE.get(buf, bufPos) & 0xFFFF;
        bufPos += 2;
        return v;
    }

    public int readInt() throws IOException {
        if (bufLimit - bufPos < 4 && !fill(4)) {
            throw new IOException("Unexpected EOF while reading int");
        }
        int v = (int) INT_BE.get(buf, bufPos);
        bufPos += 4;
        return v;
    }

    public long readUnsignedInt() throws IOException {
        return readInt() & 0xFFFFFFFFL;
    }

    public long readLong() throws IOException {
        if (bufLimit - bufPos < 8 && !fill(8)) {
            throw new IOException("Unexpected EOF while reading long");
        }
        long v = (long) LONG_BE.get(buf, bufPos);
        bufPos += 8;
        return v;
    }

    public long readId() throws IOException {
        return idSize == 4 ? readUnsignedInt() : readLong();
    }

    public byte[] readBytes(int length) throws IOException {
        byte[] bytes = new byte[length];
        readFully(bytes, length);
        return bytes;
    }

    /** Reads exactly {@code length} bytes into the start of {@code dst} (no allocation). */
    public void readFully(byte[] dst, int length) throws IOException {
        int done = 0;
        while (done < length) {
            if (bufPos == bufLimit && !fill(1)) {
                throw new IOException("Unexpected EOF while reading " + length + " bytes");
            }
            int chunk = Math.min(length - done, bufLimit - bufPos);
            System.arraycopy(buf, bufPos, dst, done, chunk);
            bufPos += chunk;
            done += chunk;
        }
    }

    public void skipBytes(long n) throws IOException {
        if (n <= 0) {
            return;
        }
        int inBuffer = bufLimit - bufPos;
        if (n <= inBuffer) {
            bufPos += (int) n;
            return;
        }
        long remaining = n - inBuffer;
        if (channel != null) {
            long target = bufBase + bufLimit + remaining;
            if (target > fileSize) {
                throw new IOException("Unexpected EOF while skipping " + n + " bytes");
            }
            bufBase = target;
            bufPos = 0;
            bufLimit = 0;
            return;
        }
        // Compressed stream: read through
        bufBase += bufLimit;
        bufPos = 0;
        bufLimit = 0;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped <= 0) {
                if (stream.read() == -1) {
                    throw new IOException("Unexpected EOF while skipping " + n + " bytes");
                }
                skipped = 1;
            }
            remaining -= skipped;
            bufBase += skipped;
        }
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            channel.close();
        } else {
            stream.close();
        }
    }
}
