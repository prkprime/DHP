package org.eclipse.mat.dhp.core.parser;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class Pass1ScanParserTest {

    @Test
    void testScanJdk6Dump() throws IOException {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/sun_jdk6_18_x64.hprof");
        Pass1ScanParser pass1 = new Pass1ScanParser();
        pass1.scan(dumpFile);

        assertThat(pass1.getHeader()).isNotNull();
        assertThat(pass1.getHeader().idSize()).isEqualTo(8);
        assertThat(pass1.getStrings()).isNotEmpty();
        assertThat(pass1.getClasses()).isNotEmpty();
        assertThat(pass1.getGcRoots()).isNotEmpty();
    }
}
