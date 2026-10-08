package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.BitField;
import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.eclipse.mat.dhp.plugin.index.DbOne2LongIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2ManyIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2OneIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2SizeIndex;
import org.eclipse.mat.parser.IObjectReader;
import org.eclipse.mat.parser.index.IndexManager;
import org.eclipse.mat.parser.internal.SnapshotImpl;
import org.eclipse.mat.parser.model.ClassImpl;
import org.eclipse.mat.parser.model.PrimitiveArrayImpl;
import org.eclipse.mat.parser.model.XGCRootInfo;
import org.eclipse.mat.parser.model.XSnapshotInfo;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.util.VoidProgressListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the official Eclipse MAT test suite invariants directly against DHP:
 * 1. GeneralSnapshotTests invariants (classes, objects, heap sizes, GC roots, object sizes)
 * 2. TestInstanceSizes jmap histogram validation
 * 3. DominatorTreeTest Lengauer-Tarjan immediate dominator and retained size assertions
 */
class EclipseMatGeneralSnapshotTestSuiteTest {

    private static final Pattern PATTERN_OBJ_ARRAY = Pattern.compile("^(\\[+)L(.*);$");
    private static final Pattern PATTERN_PRIMITIVE_ARRAY = Pattern.compile("^(\\[+)(.)$");

    private SnapshotContext openDhpSnapshot(File dumpFile, Path tempDir, String dbPrefix) throws Exception {
        File dbFile = tempDir.resolve(dbPrefix + ".db").toFile();
        File configFile = tempDir.resolve(dbPrefix + ".dhp").toFile();

        Properties props = new Properties();
        props.setProperty("db.url", "jdbc:sqlite:" + dbFile.getAbsolutePath());
        props.setProperty("dump.file", dumpFile.getAbsolutePath());
        try (FileWriter fw = new FileWriter(configFile)) {
            props.store(fw, "DHP Parity configuration");
        }

        DhpIndexBuilder builder = new DhpIndexBuilder();
        builder.init(configFile, tempDir.resolve(dbPrefix + "_prefix.").toString());

        XSnapshotInfo info = new XSnapshotInfo();
        info.setPath(dumpFile.getAbsolutePath());
        info.setPrefix(tempDir.resolve(dbPrefix + "_prefix.").toString());
        DhpIndexBuilderTest.MockPreliminaryIndex preliminary = new DhpIndexBuilderTest.MockPreliminaryIndex(info);

        builder.fill(preliminary, new VoidProgressListener());
        HeapStorageEngine storage = builder.getStorage();
        int objectCount = storage.getObjectCount();

        IndexManager indexManager = new IndexManager();
        indexManager.setReader(IndexManager.Index.IDENTIFIER, (org.eclipse.mat.parser.index.IIndexReader) preliminary.identifiers);
        indexManager.setReader(IndexManager.Index.O2CLASS, (org.eclipse.mat.parser.index.IIndexReader) preliminary.object2classId);
        indexManager.setReader(IndexManager.Index.A2SIZE, (org.eclipse.mat.parser.index.IIndexReader) preliminary.array2size);
        indexManager.setReader(IndexManager.Index.OUTBOUND, (org.eclipse.mat.parser.index.IIndexReader) preliminary.outbound);

        indexManager.setReader(IndexManager.Index.INBOUND, new DbOne2ManyIndex(
                objectCount,
                storage::getInboundReferences,
                key -> (key instanceof Number num ? storage.getObjectsByClassId(num.intValue()) : new int[0])
        ));

        indexManager.setReader(IndexManager.Index.DOMINATOR, new DbOne2OneIndex(
                objectCount,
                id -> storage.getDominatorId(id) + 2
        ));

        indexManager.setReader(IndexManager.Index.DOMINATED, new DbOne2ManyIndex(
                objectCount + 1,
                id -> storage.getImmediateDominatedIds(id - 1)
        ));

        indexManager.setReader(IndexManager.Index.O2RETAINED, new DbOne2LongIndex(
                objectCount,
                storage::getRetainedSize,
                id -> -1
        ));

        HashMapIntObject<HashMapIntObject<XGCRootInfo[]>> rootsPerThread = new HashMapIntObject<>();
        for (int threadId : preliminary.thread2objects2roots.getAllKeys()) {
            var objMap = preliminary.thread2objects2roots.get(threadId);
            var targetObjMap = new HashMapIntObject<XGCRootInfo[]>();
            for (int objId : objMap.getAllKeys()) {
                List<XGCRootInfo> list = objMap.get(objId);
                targetObjMap.put(objId, list.toArray(new XGCRootInfo[0]));
            }
            rootsPerThread.put(threadId, targetObjMap);
        }

        HashMapIntObject<XGCRootInfo[]> roots = new HashMapIntObject<>();
        for (int objId : preliminary.gcRoots.getAllKeys()) {
            List<XGCRootInfo> list = preliminary.gcRoots.get(objId);
            roots.put(objId, list.toArray(new XGCRootInfo[0]));
        }

        BitField arrayObjects = new BitField(objectCount);
        boolean[] arrayFlags = storage.getAllArrayFlags(objectCount);
        for (int i = 0; i < objectCount; i++) {
            if (arrayFlags[i]) arrayObjects.set(i);
        }

        HashMapIntObject<String> loaderLabels = new HashMapIntObject<>();
        int systemClassLoaderId = preliminary.identifiers.reverse(0L);
        loaderLabels.put(systemClassLoaderId, "<system class loader>");
        for (int key : preliminary.classesById.getAllKeys()) {
            ClassImpl c = preliminary.classesById.get(key);
            int clId = c.getClassLoaderId();
            if (!loaderLabels.containsKey(clId)) {
                if (clId == systemClassLoaderId || clId <= 0) {
                    loaderLabels.put(clId, "<system class loader>");
                } else {
                    loaderLabels.put(clId, "ClassLoader@" + Long.toHexString(preliminary.identifiers.get(clId)));
                }
            }
        }
        info.setNumberOfClassLoaders(loaderLabels.size());
        info.setUsedHeapSize(storage.getTotalHeapSize());

        DhpHeapObjectReader reader = new DhpHeapObjectReader();

        // Instantiate MAT's SnapshotImpl
        Constructor<SnapshotImpl> ctor = SnapshotImpl.class.getDeclaredConstructor(
                XSnapshotInfo.class,
                IObjectReader.class,
                HashMapIntObject.class,
                HashMapIntObject.class,
                HashMapIntObject.class,
                HashMapIntObject.class,
                BitField.class,
                IndexManager.class
        );
        ctor.setAccessible(true);
        SnapshotImpl snapshot = ctor.newInstance(
                info,
                reader,
                preliminary.classesById,
                roots,
                rootsPerThread,
                loaderLabels,
                arrayObjects,
                indexManager
        );

        reader.open(snapshot);

        return new SnapshotContext(snapshot, reader, storage);
    }

    record SnapshotContext(SnapshotImpl snapshot, DhpHeapObjectReader reader, HeapStorageEngine storage) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            reader.close();
            storage.close();
        }
    }

    @Test
    @DisplayName("MAT GeneralSnapshotTests: verify classes, objects, heap sizes, roots, and payloads")
    void testGeneralSnapshotInvariantsOnJdk6_64(@TempDir Path tempDir) throws Exception {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/sun_jdk6_18_x64.hprof");
        if (!dumpFile.exists()) return;

        try (SnapshotContext ctx = openDhpSnapshot(dumpFile, tempDir, "general_test")) {
            SnapshotImpl snapshot = ctx.snapshot();

            // 1. Total Classes invariant
            int nc = snapshot.getClasses().size();
            int nClasses = snapshot.getSnapshotInfo().getNumberOfClasses();
            assertEquals(nClasses, nc, "Total classes mismatch");

            // 2. Total Objects invariant
            int no = 0;
            for (IClass cls : snapshot.getClasses()) {
                no += cls.getNumberOfObjects();
            }
            int nObjects = snapshot.getSnapshotInfo().getNumberOfObjects();
            assertEquals(nObjects, no, "Total objects mismatch");

            // 3. Total Heap Size invariant
            long total = 0;
            for (IClass cls : snapshot.getClasses()) {
                total += snapshot.getHeapSize(cls.getObjectIds());
            }
            long usedHeap = snapshot.getSnapshotInfo().getUsedHeapSize();
            assertEquals(usedHeap, total, "Total heap size mismatch");

            // 4. Object sizes and class types invariant
            int sampleCount = 0;
            for (IClass cls : snapshot.getClasses()) {
                for (int o : cls.getObjectIds()) {
                    IObject obj = snapshot.getObject(o);
                    assertNotNull(obj);
                    assertEquals(snapshot.getHeapSize(o), obj.getUsedHeapSize());
                    assertEquals(cls, obj.getClazz(), "Object class must match class");
                    if (++sampleCount > 500) break;
                }
                if (sampleCount > 500) break;
            }

            // 5. Class loaders count invariant
            assertThat(snapshot.getSnapshotInfo().getNumberOfClassLoaders()).isGreaterThanOrEqualTo(1);

            // 6. GC Roots invariant
            int[] gcRoots = snapshot.getGCRoots();
            assertThat(gcRoots.length).isGreaterThan(100);

            // 7. Verify String payload reading, field resolution, hierarchy, and static fields
            Collection<IClass> stringClasses = snapshot.getClassesByName("java.lang.String", false);
            assertNotNull(stringClasses);
            assertFalse(stringClasses.isEmpty());
            IClass strClass = stringClasses.iterator().next();
            int[] strIds = strClass.getObjectIds();
            assertThat(strIds.length).isGreaterThan(0);

            // Verify Class Hierarchy on inspector/engine model
            assertNotNull(strClass.getSuperClass(), "java.lang.String must have superclass");
            assertEquals("java.lang.Object", strClass.getSuperClass().getName());
            assertTrue(strClass.doesExtend("java.lang.Object"));

            Collection<IClass> objClasses = snapshot.getClassesByName("java.lang.Object", false);
            assertNotNull(objClasses);
            assertFalse(objClasses.isEmpty());
            IClass objClass = objClasses.iterator().next();
            assertThat(objClass.getSubclasses()).isNotEmpty();
            assertThat(objClass.getAllSubclasses().size()).isGreaterThan(500);

            // Verify Static Fields population
            assertThat(strClass.getStaticFields()).isNotEmpty();
            boolean hasCaseInsensitive = strClass.getStaticFields().stream()
                    .anyMatch(f -> "CASE_INSENSITIVE_ORDER".equals(f.getName()) || "serialPersistentFields".equals(f.getName()) || "serialVersionUID".equals(f.getName()));
            assertTrue(hasCaseInsensitive, "java.lang.String must contain standard static fields");

            IObject strObj = snapshot.getObject(strIds[0]);
            assertThat(strObj).isInstanceOf(IInstance.class);
            IInstance strInst = (IInstance) strObj;
            assertThat(strInst.getFields()).isNotEmpty();

            // 8. Verify primitive array payload reading
            Collection<IClass> charClasses = snapshot.getClassesByName("char[]", false);
            assertNotNull(charClasses);
            assertFalse(charClasses.isEmpty());
            IClass charClass = charClasses.iterator().next();
            int[] charIds = charClass.getObjectIds();
            assertThat(charIds.length).isGreaterThan(0);

            IObject charObj = snapshot.getObject(charIds[0]);
            assertThat(charObj).isInstanceOf(PrimitiveArrayImpl.class);
            PrimitiveArrayImpl charArray = (PrimitiveArrayImpl) charObj;
            if (charArray.getLength() > 0) {
                Object chars = ctx.reader().readPrimitiveArrayContent(charArray, 0, Math.min(5, charArray.getLength()));
                assertThat(chars).isNotNull();
                assertThat(chars).isInstanceOf(char[].class);
            }

            // 9. Verify Class Histogram computation and retained size calculation
            org.eclipse.mat.snapshot.Histogram histogram = snapshot.getHistogram(new org.eclipse.mat.util.VoidProgressListener());
            assertThat(histogram).isNotNull();
            assertThat(histogram.getClassHistogramRecords()).isNotEmpty();
            assertThat(histogram.getClassLoaderHistogramRecords()).isNotEmpty();

            var firstRecord = histogram.getClassHistogramRecords().iterator().next();
            if (firstRecord.getNumberOfObjects() > 0) {
                long minRetained = firstRecord.calculateRetainedSize(snapshot, true, true, new org.eclipse.mat.util.VoidProgressListener());
                assertThat(minRetained).isNotZero();
            }
        }
    }

    @Test
    @DisplayName("MAT TestInstanceSizes: validate shallow sizes against jmap histogram")
    void testHistogramInstanceSizesJdk6_64(@TempDir Path tempDir) throws Exception {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/sun_jdk6_18_x64.hprof");
        File histFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/histogram_sun_jdk6_18_x64.txt");
        if (!dumpFile.exists() || !histFile.exists()) return;

        try (SnapshotContext ctx = openDhpSnapshot(dumpFile, tempDir, "histogram_test")) {
            SnapshotImpl snapshot = ctx.snapshot();

            try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(histFile), StandardCharsets.UTF_8))) {
                String line;
                int matchedClasses = 0;

                while ((line = in.readLine()) != null) {
                    StringTokenizer tokenizer = new StringTokenizer(line);
                    if (!tokenizer.hasMoreTokens()) continue;
                    String firstToken = tokenizer.nextToken();
                    if (firstToken.indexOf(':') != -1) {
                        firstToken = tokenizer.nextToken();
                    }
                    int numObjects = Integer.parseInt(firstToken);
                    long shallowSize = Long.parseLong(tokenizer.nextToken());
                    long instanceSize = shallowSize / numObjects;
                    String className = tokenizer.nextToken();
                    className = fixArrayName(className);

                    if (className.startsWith("<") || className.equals("java.lang.Class")) {
                        continue;
                    }

                    Collection<IClass> classes = snapshot.getClassesByName(className, false);
                    if (classes == null || classes.isEmpty()) {
                        continue;
                    }

                    IClass clazz = classes.iterator().next();
                    if (clazz.isArrayType()) {
                        if (numObjects == clazz.getNumberOfObjects()) {
                            int[] o = clazz.getObjectIds();
                            long actual = snapshot.getHeapSize(o);
                            assertEquals(shallowSize, actual, "Array class " + className + " total size mismatch");
                            matchedClasses++;
                        }
                    } else {
                        assertEquals(instanceSize, clazz.getHeapSizePerInstance(), "Class " + className + " instance size mismatch");
                        matchedClasses++;
                    }
                }

                assertThat(matchedClasses).isGreaterThan(50);
            }
        }
    }

    @Test
    @DisplayName("MAT DominatorTreeTest: validate Lengauer-Tarjan immediate dominators and retained sizes")
    void testDominatorTreeOnSunJdk5_64(@TempDir Path tempDir) throws Exception {
        File dumpFile = new File("/tmp/eclipse-mat/plugins/org.eclipse.mat.tests/dumps/sun_jdk5_64bit.hprof");
        if (!dumpFile.exists()) return;

        try (SnapshotContext ctx = openDhpSnapshot(dumpFile, tempDir, "dom_test")) {
            SnapshotImpl snapshot = ctx.snapshot();

            Collection<IClass> rClasses = snapshot.getClassesByName(
                    "org.eclipse.mat.tests.CreateSampleDump$DominatorTestData$R", false);
            assertNotNull(rClasses);
            assertFalse(rClasses.isEmpty());
            IClass rClass = rClasses.iterator().next();
            int rId = rClass.getObjectIds()[0];

            int[] dominated = snapshot.getImmediateDominatedIds(rId);
            int[] retainedSetR = getRetainedSet(snapshot, ctx.storage(), rId);

            // Assert root dominators are present and non-empty
            int[] rootDominated = snapshot.getImmediateDominatedIds(-1);
            assertThat(rootDominated.length).isGreaterThan(0);

            // Assertions from Eclipse MAT DominatorTreeTest.java
            assertEquals(8, dominated.length, "R should be immediate dominator of 8 objects");
            assertEquals(376L, snapshot.getRetainedHeapSize(rId), "R has unexpected retained size");
            assertEquals(13, retainedSetR.length, "R should retain 13 objects");

            Map<String, Set<String>> children = new HashMap<>();
            Set<String> set = new HashSet<>();
            set.add("$A"); set.add("$B"); set.add("$C"); set.add("$D");
            set.add("$E"); set.add("$H"); set.add("$I"); set.add("$K");
            children.put("$R", set);

            set = new HashSet<>();
            set.add("$F"); set.add("$G");
            children.put("$C", set);

            set = new HashSet<>();
            set.add("$L");
            children.put("$D", set);

            set = new HashSet<>();
            set.add("$J");
            children.put("$G", set);

            Map<String, String> parent = new HashMap<>();
            parent.put("$A", "$R"); parent.put("$B", "$R"); parent.put("$C", "$R");
            parent.put("$D", "$R"); parent.put("$E", "$R"); parent.put("$H", "$R");
            parent.put("$I", "$R"); parent.put("$K", "$R");
            parent.put("$F", "$C"); parent.put("$G", "$C");
            parent.put("$L", "$D");
            parent.put("$J", "$G");

            // Verify dominator relationships for the entire retained set
            for (int id : retainedSetR) {
                String name = name(id, snapshot);
                int dominatorId = snapshot.getImmediateDominatorId(id);
                if (!"$R".equals(name)) {
                    assertEquals(parent.get(name), name(dominatorId, snapshot), "Wrong parent of " + name);
                }

                int[] dominatedIds = snapshot.getImmediateDominatedIds(id);
                Set<String> childrenSet = children.get(name);
                if (childrenSet != null) {
                    for (int child : dominatedIds) {
                        assertTrue(childrenSet.contains(name(child, snapshot)),
                                "Unknown child of " + name + " -> " + name(child, snapshot));
                    }
                }
            }
        }
    }

    private static int[] getRetainedSet(SnapshotImpl snapshot, HeapStorageEngine storage, int rootId) throws Exception {
        Set<Integer> result = new HashSet<>();
        Deque<Integer> q = new ArrayDeque<>();
        result.add(rootId);
        q.add(rootId);

        while (!q.isEmpty()) {
            int curr = q.poll();
            int[] dominated = storage.getImmediateDominatedIds(curr);
            for (int ch : dominated) {
                if (result.add(ch)) {
                    q.add(ch);
                }
            }
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String name(int id, ISnapshot snapshot) throws SnapshotException {
        String nodeClass = snapshot.getClassOf(id).getName();
        return nodeClass.substring(nodeClass.length() - 2);
    }

    private static String fixArrayName(String className) {
        if (className.charAt(0) == '[') {
            Matcher matcher = PATTERN_OBJ_ARRAY.matcher(className);
            if (matcher.matches()) {
                int l = matcher.group(1).length();
                StringBuilder classNameBuilder = new StringBuilder(matcher.group(2));
                for (int ii = 0; ii < l; ii++) classNameBuilder.append("[]");
                return classNameBuilder.toString();
            }

            matcher = PATTERN_PRIMITIVE_ARRAY.matcher(className);
            if (matcher.matches()) {
                int count = matcher.group(1).length() - 1;
                String prim = "unknown[]";
                char signature = matcher.group(2).charAt(0);
                for (int ii = 0; ii < IPrimitiveArray.SIGNATURES.length; ii++) {
                    if (IPrimitiveArray.SIGNATURES[ii] == (byte) signature) {
                        prim = IPrimitiveArray.TYPE[ii];
                        break;
                    }
                }
                StringBuilder classNameBuilder = new StringBuilder(prim);
                for (int ii = 0; ii < count; ii++) classNameBuilder.append("[]");
                return classNameBuilder.toString();
            }
        }
        return className;
    }
}
