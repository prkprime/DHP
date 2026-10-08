package org.eclipse.mat.dhp.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DhpMainTest {

    @Test
    void testCliExecutionWithDumpAndSqlite(@TempDir Path tempDir) {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/sun_jdk6_18_x64.hprof");
        if (!dumpFile.exists()) return;
        File dbFile = tempDir.resolve("cli_test.db").toFile();

        int exitCode = new CommandLine(new DhpMain()).execute(
                "--dump", dumpFile.getAbsolutePath(),
                "--jdbcurl", "jdbc:sqlite:" + dbFile.getAbsolutePath(),
                "--memory-budget", "134217728", // 128MB
                "--threads", "2"
        );

        assertThat(exitCode).isEqualTo(0);
        assertThat(dbFile).exists();
        assertThat(dbFile.length()).isGreaterThan(0);

        // Second run without --clean should fail because tables already exist
        int failExitCode = new CommandLine(new DhpMain()).execute(
                "--dump", dumpFile.getAbsolutePath(),
                "--jdbcurl", "jdbc:sqlite:" + dbFile.getAbsolutePath()
        );
        assertThat(failExitCode).isEqualTo(1);

        // Third run with --clean should succeed
        int cleanExitCode = new CommandLine(new DhpMain()).execute(
                "--dump", dumpFile.getAbsolutePath(),
                "--jdbcurl", "jdbc:sqlite:" + dbFile.getAbsolutePath(),
                "--clean"
        );
        assertThat(cleanExitCode).isEqualTo(0);
    }
}
