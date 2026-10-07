package org.eclipse.mat.dhp.core.parser;

import org.eclipse.mat.dhp.core.model.HprofConstants;
import org.eclipse.mat.dhp.core.model.HeapRecords;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Fast streaming binary reader for HPROF files.
 */
public class HprofBinaryReader implements AutoCloseable {

    private final InputStream in;
    private long position = 0;
    private int idSize = 8;

    public HprofBinaryReader(File file) throws IOException {
        InputStream rawIn = new FileInputStream(file);
        if (file.getName().endsWith(".gz")) {
            rawIn = new GZIPInputStream(rawIn, 64 * 1024);
        }
        this.in = new BufferedInputStream(rawIn, 128 * 1024);
    }

    public HeapRecords.Header readHeader() throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = readByte()) != 0) {
            sb.append((char) b);
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
        return position;
    }

    public int readByte() throws IOException {
        int b = in.read();
        if (b == -1) {
            return -1;
        }
        position++;
        return b & 0xFF;
    }

    public int readUnsignedShort() throws IOException {
        int ch1 = readByte();
        int ch2 = readByte();
        if ((ch1 | ch2) < 0) {
            throw new IOException("Unexpected EOF while reading unsigned short");
        }
        return (ch1 << 8) + ch2;
    }

    public int readInt() throws IOException {
        int ch1 = readByte();
        int ch2 = readByte();
        int ch3 = readByte();
        int ch4 = readByte();
        if ((ch1 | ch2 | ch3 | ch4) < 0) {
            throw new IOException("Unexpected EOF while reading int");
        }
        return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + ch4);
    }

    public long readUnsignedInt() throws IOException {
        return readInt() & 0xFFFFFFFFL;
    }

    public long readLong() throws IOException {
        return (((long) readInt()) << 32) + (readInt() & 0xFFFFFFFFL);
    }

    public long readId() throws IOException {
        return idSize == 4 ? readUnsignedInt() : readLong();
    }

    public byte[] readBytes(int length) throws IOException {
        byte[] bytes = new byte[length];
        int read = 0;
        while (read < length) {
            int count = in.read(bytes, read, length - read);
            if (count < 0) {
                throw new IOException("Unexpected EOF while reading " + length + " bytes");
            }
            read += count;
            position += count;
        }
        return bytes;
    }

    public void skipBytes(long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // fall back to reading byte
                if (in.read() == -1) {
                    throw new IOException("Unexpected EOF while skipping " + n + " bytes");
                }
                skipped = 1;
            }
            remaining -= skipped;
            position += skipped;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
