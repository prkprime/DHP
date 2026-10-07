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
        assertThat(dumpFile).exists();

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
        assertThat(dumpFile).exists();

        try (HprofBinaryReader reader = new HprofBinaryReader(dumpFile)) {
            HeapRecords.Header header = reader.readHeader();
            assertThat(header.version()).startsWith("JAVA PROFILE");
            assertThat(header.idSize()).isEqualTo(8);
            assertThat(header.creationTime()).isGreaterThan(0);
        }
    }
}
