package org.eclipse.mat.dhp.core.parser;

import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class HprofBinaryReaderTest {

    @Test
    void testReadHeaderFromSunJdkDump() throws IOException {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/sun_jdk6_18_x64.hprof");
        if (!dumpFile.exists()) return;

        try (HprofBinaryReader reader = new HprofBinaryReader(dumpFile)) {
            HeapRecords.Header header = reader.readHeader();
            assertThat(header.version()).startsWith("JAVA PROFILE");
            assertThat(header.idSize()).isEqualTo(8);
            assertThat(header.creationTime()).isGreaterThan(0);
        }
    }

    @Test
    void testReadHeaderFromCompressedDump() throws IOException {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/openjdk_jdk11_04_x64.hprof.gz");
        if (!dumpFile.exists()) return;

        try (HprofBinaryReader reader = new HprofBinaryReader(dumpFile)) {
            HeapRecords.Header header = reader.readHeader();
            assertThat(header.version()).startsWith("JAVA PROFILE");
            assertThat(header.idSize()).isEqualTo(8);
            assertThat(header.creationTime()).isGreaterThan(0);
        }
    }

    @Test
    void testReadHeaderSynthetic(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws IOException {
        File file = tempDir.resolve("test.hprof").toFile();
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(new java.io.FileOutputStream(file))) {
            dos.writeBytes("JAVA PROFILE 1.0.2\0");
            dos.writeInt(8);
            dos.writeLong(123456789L);
        }
        try (HprofBinaryReader reader = new HprofBinaryReader(file)) {
            HeapRecords.Header header = reader.readHeader();
            assertThat(header.version()).isEqualTo("JAVA PROFILE 1.0.2");
            assertThat(header.idSize()).isEqualTo(8);
            assertThat(header.creationTime()).isEqualTo(123456789L);
        }
    }
}
