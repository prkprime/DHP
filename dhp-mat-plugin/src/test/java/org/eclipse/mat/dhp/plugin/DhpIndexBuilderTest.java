package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.parser.model.XSnapshotInfo;
import org.eclipse.mat.util.VoidProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class DhpIndexBuilderTest {

    @Test
    void testIndexBuilderWithDhpConfigFile(@TempDir Path tempDir) throws IOException, SnapshotException {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/sun_jdk6_18_x64.hprof");
        if (!dumpFile.exists()) return;
        File dbFile = tempDir.resolve("mat_test.db").toFile();
        File configFile = tempDir.resolve("dump.dhp").toFile();

        Properties props = new Properties();
        props.setProperty("db.url", "jdbc:sqlite:" + dbFile.getAbsolutePath());
        props.setProperty("dump.file", dumpFile.getAbsolutePath());
        try (FileWriter fw = new FileWriter(configFile)) {
            props.store(fw, "DHP test configuration");
        }

        DhpIndexBuilder builder = new DhpIndexBuilder();
        builder.init(configFile, tempDir.resolve("prefix.").toString());

        XSnapshotInfo info = new XSnapshotInfo();
        MockPreliminaryIndex preliminary = new MockPreliminaryIndex(info);

        builder.fill(preliminary, new VoidProgressListener());

        // Assert preliminary indices are successfully initialized from DB
        assertThat(preliminary.classesById).isNotNull();
        assertThat(preliminary.identifiers).isNotNull();
        assertThat(preliminary.identifiers.size()).isGreaterThan(1000);
        assertThat(preliminary.outbound).isNotNull();
        assertThat(preliminary.array2size).isNotNull();
    }

    static class MockPreliminaryIndex implements org.eclipse.mat.parser.IPreliminaryIndex {
        final XSnapshotInfo snapshotInfo;
        org.eclipse.mat.collect.HashMapIntObject<org.eclipse.mat.parser.model.ClassImpl> classesById;
        org.eclipse.mat.collect.HashMapIntObject<java.util.List<org.eclipse.mat.parser.model.XGCRootInfo>> gcRoots;
        org.eclipse.mat.collect.HashMapIntObject<org.eclipse.mat.collect.HashMapIntObject<java.util.List<org.eclipse.mat.parser.model.XGCRootInfo>>> thread2objects2roots;
        org.eclipse.mat.parser.index.IIndexReader.IOne2ManyIndex outbound;
        org.eclipse.mat.parser.index.IIndexReader.IOne2LongIndex identifiers;
        org.eclipse.mat.parser.index.IIndexReader.IOne2OneIndex object2classId;
        org.eclipse.mat.parser.index.IIndexReader.IOne2SizeIndex array2size;

        MockPreliminaryIndex(XSnapshotInfo snapshotInfo) {
            this.snapshotInfo = snapshotInfo;
        }

        @Override public XSnapshotInfo getSnapshotInfo() { return snapshotInfo; }
        @Override public void setClassesById(org.eclipse.mat.collect.HashMapIntObject<org.eclipse.mat.parser.model.ClassImpl> classesById) { this.classesById = classesById; }
        @Override public void setGcRoots(org.eclipse.mat.collect.HashMapIntObject<java.util.List<org.eclipse.mat.parser.model.XGCRootInfo>> gcRoots) { this.gcRoots = gcRoots; }
        @Override public void setThread2objects2roots(org.eclipse.mat.collect.HashMapIntObject<org.eclipse.mat.collect.HashMapIntObject<java.util.List<org.eclipse.mat.parser.model.XGCRootInfo>>> thread2objects2roots) { this.thread2objects2roots = thread2objects2roots; }
        @Override public void setOutbound(org.eclipse.mat.parser.index.IIndexReader.IOne2ManyIndex outbound) { this.outbound = outbound; }
        @Override public void setIdentifiers(org.eclipse.mat.parser.index.IIndexReader.IOne2LongIndex identifiers) { this.identifiers = identifiers; }
        @Override public void setObject2classId(org.eclipse.mat.parser.index.IIndexReader.IOne2OneIndex object2classId) { this.object2classId = object2classId; }
        @Override public void setArray2size(org.eclipse.mat.parser.index.IIndexReader.IOne2SizeIndex array2size) { this.array2size = array2size; }
    }
}
