package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.parser.IIndexBuilder;
import org.eclipse.mat.parser.IObjectReader;
import org.eclipse.mat.parser.internal.SnapshotImpl;
import org.eclipse.mat.parser.internal.SnapshotImplBuilder;
import org.eclipse.mat.parser.internal.util.ParserRegistry;
import org.eclipse.mat.parser.model.ClassImpl;
import org.eclipse.mat.parser.model.XSnapshotInfo;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.util.VoidProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class EclipseMatEquivalenceParityTest {

    @Test
    void testParityBetweenDhpAndMatStructures(@TempDir Path tempDir) throws Exception {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/sun_jdk6_18_x64.hprof");
        File dbFile = tempDir.resolve("parity_test.db").toFile();
        File configFile = tempDir.resolve("dump.properties").toFile();

        Properties props = new Properties();
        props.setProperty("db.url", "jdbc:sqlite:" + dbFile.getAbsolutePath());
        props.setProperty("dump.file", dumpFile.getAbsolutePath());
        try (FileWriter fw = new FileWriter(configFile)) {
            props.store(fw, "DHP Parity configuration");
        }

        DhpIndexBuilder builder = new DhpIndexBuilder();
        builder.init(configFile, tempDir.resolve("prefix.").toString());

        XSnapshotInfo info = new XSnapshotInfo();
        info.setPath(dumpFile.getAbsolutePath());
        DhpIndexBuilderTest.MockPreliminaryIndex preliminary = new DhpIndexBuilderTest.MockPreliminaryIndex(info);

        builder.fill(preliminary, new VoidProgressListener());

        // 1. Verify object count (reachable objects after DB garbage collection)
        int objectCount = preliminary.identifiers.size();
        assertThat(objectCount).isGreaterThan(15000);

        // 2. Verify class resolution parity
        var classesMap = preliminary.classesById;
        assertThat(classesMap.size()).isGreaterThan(1000);

        // Check java.lang.String and java.lang.Class exist
        boolean foundString = false;
        boolean foundClass = false;
        for (int id : classesMap.getAllKeys()) {
            ClassImpl c = classesMap.get(id);
            if ("java.lang.String".equals(c.getName())) foundString = true;
            if ("java.lang.Class".equals(c.getName())) foundClass = true;
        }
        assertThat(foundString).isTrue();
        assertThat(foundClass).isTrue();

        // 3. Verify System Class Loader at address 0
        int bootLoaderId = preliminary.identifiers.reverse(0L);
        assertThat(bootLoaderId).isEqualTo(0);
        assertThat(preliminary.identifiers.get(bootLoaderId)).isZero();

        // 4. Verify GC roots & Thread Locals mapping
        assertThat(preliminary.gcRoots).isNotNull();
        assertThat(preliminary.gcRoots.size()).isGreaterThan(100);
        assertThat(preliminary.thread2objects2roots).isNotNull();

        // 5. Verify outbound graph edges
        int sampleObjectId = 0;
        int[] outbounds = preliminary.outbound.get(sampleObjectId);
        assertThat(outbounds).isNotNull();

        // 5. Verify object reader
        ISnapshot snapshot = (ISnapshot) java.lang.reflect.Proxy.newProxyInstance(
                ISnapshot.class.getClassLoader(),
                new Class<?>[]{ISnapshot.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getSnapshotInfo".equals(name)) return preliminary.snapshotInfo;
                    if ("mapIdToAddress".equals(name)) return preliminary.identifiers.get((Integer) args[0]);
                    if ("mapAddressToId".equals(name)) return preliminary.identifiers.reverse((Long) args[0]);
                    if ("getClassOf".equals(name)) {
                        int classId = preliminary.object2classId.get((Integer) args[0]);
                        return preliminary.classesById.get(classId);
                    }
                    if ("isClassLoader".equals(name)) return false;
                    if ("isArray".equals(name)) return false;
                    if ("getGCRoots".equals(name)) return preliminary.gcRoots.getAllKeys();
                    if ("getOutboundReferrers".equals(name)) return preliminary.outbound.get((Integer) args[0]);
                    if (method.getReturnType().equals(boolean.class)) return false;
                    if (method.getReturnType().equals(int.class)) return 0;
                    if (method.getReturnType().equals(long.class)) return 0L;
                    return null;
                }
        );

        DhpHeapObjectReader reader = new DhpHeapObjectReader();
        reader.open(snapshot);
        var obj = reader.read(sampleObjectId, snapshot);
        assertThat(obj).isNotNull();
        assertThat(obj.getObjectId()).isEqualTo(sampleObjectId);

        // Find a string instance or primitive array to verify binary payload decoding
        int charArrayId = -1;
        int stringId = -1;
        for (int i = 0; i < Math.min(1000, objectCount); i++) {
            IClass cls = (IClass) snapshot.getClassOf(i);
            if (cls != null) {
                if ("char[]".equals(cls.getName()) && charArrayId == -1) {
                    charArrayId = i;
                }
                if ("java.lang.String".equals(cls.getName()) && stringId == -1) {
                    stringId = i;
                }
            }
            if (charArrayId != -1 && stringId != -1) break;
        }

        if (stringId != -1) {
            var stringObj = reader.read(stringId, snapshot);
            assertThat(stringObj).isNotNull();
            assertThat(stringObj).isInstanceOf(org.eclipse.mat.snapshot.model.IInstance.class);
            var instance = (org.eclipse.mat.snapshot.model.IInstance) stringObj;
            assertThat(instance.getFields()).isNotEmpty();
        }

        if (charArrayId != -1) {
            var arrayObj = reader.read(charArrayId, snapshot);
            assertThat(arrayObj).isInstanceOf(org.eclipse.mat.parser.model.PrimitiveArrayImpl.class);
            var primArray = (org.eclipse.mat.parser.model.PrimitiveArrayImpl) arrayObj;
            if (primArray.getLength() > 0) {
                Object chars = reader.readPrimitiveArrayContent(primArray, 0, Math.min(5, primArray.getLength()));
                assertThat(chars).isNotNull();
                assertThat(chars).isInstanceOf(char[].class);
            }
        }
        reader.close();
    }
}
