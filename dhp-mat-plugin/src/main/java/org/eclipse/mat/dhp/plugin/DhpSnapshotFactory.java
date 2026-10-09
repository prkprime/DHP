package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.BitField;
import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.dhp.core.graph.DominatorTreeEngine;
import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.parser.Pass1ScanParser;
import org.eclipse.mat.dhp.core.parser.Pass2ObjectIngester;
import org.eclipse.mat.dhp.plugin.index.DbOne2LongIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2ManyIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2OneIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2SizeIndex;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.eclipse.mat.parser.IObjectReader;
import org.eclipse.mat.parser.index.IndexManager;
import org.eclipse.mat.parser.index.IIndexReader;
import org.eclipse.mat.parser.model.ClassImpl;
import org.eclipse.mat.parser.model.XGCRootInfo;
import org.eclipse.mat.parser.model.XSnapshotInfo;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.FieldDescriptor;
import org.eclipse.mat.snapshot.IOQLQuery;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.OQLParseException;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.SnapshotFormat;
import org.eclipse.mat.util.IProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.util.*;

/**
 * High-performance database-backed SnapshotFactory implementation for Eclipse MAT.
 * Opens .dhp and .properties connection descriptors directly against SQLite or PostgreSQL,
 * instantiating Eclipse MAT's SnapshotImpl with database-backed readers and ZERO files on disk.
 */
public class DhpSnapshotFactory implements SnapshotFactory.Implementation {
    private static final Logger log = LoggerFactory.getLogger(DhpSnapshotFactory.class);
    private static DhpSnapshotFactory installedInstance;

    private SnapshotFactory.Implementation delegate;
    private final Map<ISnapshot, DhpSnapshotContext> openSnapshots = new WeakHashMap<>();

    public static Class<?> loadParserClass(String className) throws ClassNotFoundException {
        try {
            org.osgi.framework.Bundle bundle = org.osgi.framework.FrameworkUtil.getBundle(DhpSnapshotFactory.class);
            if (bundle != null && bundle.getBundleContext() != null) {
                for (org.osgi.framework.Bundle b : bundle.getBundleContext().getBundles()) {
                    if ("org.eclipse.mat.parser".equals(b.getSymbolicName())) {
                        return b.loadClass(className);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return Class.forName(className);
    }

    public DhpSnapshotFactory() {
        this(null);
    }

    public DhpSnapshotFactory(SnapshotFactory.Implementation delegate) {
        if (delegate != null) {
            this.delegate = delegate;
        } else {
            try {
                Class<?> cls = loadParserClass("org.eclipse.mat.parser.internal.SnapshotFactoryImpl");
                this.delegate = (SnapshotFactory.Implementation) cls.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                log.warn("Could not instantiate default SnapshotFactoryImpl: {}", t.getMessage());
            }
        }
    }

    public static synchronized void install() {
        try {
            java.lang.reflect.Field factoryField = SnapshotFactory.class.getDeclaredField("factory");
            factoryField.setAccessible(true);
            SnapshotFactory.Implementation current = (SnapshotFactory.Implementation) factoryField.get(null);
            if (!(current instanceof DhpSnapshotFactory)) {
                installedInstance = new DhpSnapshotFactory(current);
                factoryField.set(null, installedInstance);
                log.info("Successfully hooked DhpSnapshotFactory into Eclipse MAT SnapshotFactory!");
            }
        } catch (Throwable t) {
            log.debug("DhpSnapshotFactory hooking skipped or unavailable in current runtime: {}", t.getMessage());
        }
    }

    public static DhpSnapshotFactory getInstalledInstance() {
        return installedInstance;
    }

    private boolean isDhpFile(File file) {
        if (file == null) return false;
        return file.getName().toLowerCase().endsWith(".dhp");
    }

    @Override
    public ISnapshot openSnapshot(File file, Map<String, String> arguments, IProgressListener listener) throws SnapshotException {
        if (!isDhpFile(file)) {
            if (delegate != null) {
                return delegate.openSnapshot(file, arguments, listener);
            }
            throw new SnapshotException("No delegate factory available to open " + file);
        }

        log.info("Opening DHP heap dump descriptor: {}", file.getAbsolutePath());
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            }

            File absFile = file.getAbsoluteFile();
            File parentDir = absFile.getParentFile();
            String baseName = absFile.getName();
            int p = baseName.lastIndexOf('.');
            String prefixName = p >= 0 ? baseName.substring(0, p) : baseName;
            String prefix = new File(parentDir, prefixName + ".").getAbsolutePath().replace('\\', '/');
            String defaultDb = new File(parentDir, prefixName + ".dhp.db").getAbsolutePath().replace('\\', '/');

            String jdbcUrl = props.getProperty("db.url", "jdbc:sqlite:" + defaultDb);
            if (jdbcUrl.startsWith("jdbc:sqlite:") && !jdbcUrl.startsWith("jdbc:sqlite::memory:")) {
                String sub = jdbcUrl.substring("jdbc:sqlite:".length());
                File dbFile = new File(sub);
                if (!dbFile.isAbsolute() && parentDir != null) {
                    jdbcUrl = "jdbc:sqlite:" + new File(parentDir, sub).getAbsolutePath().replace('\\', '/');
                } else if (dbFile.isAbsolute()) {
                    jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath().replace('\\', '/');
                }
            }
            String user = props.getProperty("db.user", "");
            String password = props.getProperty("db.password", "");
            String hprofPath = props.getProperty("dump.file");
            File hprofFile = null;
            if (hprofPath != null && !hprofPath.isBlank()) {
                File candidate = new File(hprofPath);
                if (!candidate.isAbsolute() && parentDir != null) {
                    candidate = new File(parentDir, hprofPath);
                }
                hprofFile = candidate;
            }
            long memoryBudget = 1024 * 1024 * 1024L;
            String memStr = props.getProperty("memory.budget");
            if (memStr != null) {
                try {
                    memoryBudget = Long.parseLong(memStr);
                } catch (Exception ignored) {}
            }

            MemoryGovernor governor = new MemoryGovernor(memoryBudget, 4);
            JdbcHeapStorageEngine storage = new JdbcHeapStorageEngine(jdbcUrl, user, password, governor.getTotalAllocatedBytes());
            if (!storage.hasExistingTables()) {
                storage.initializeSchema();
            }

            // Ingest on the fly if tables are empty
            if (storage.getObjectCount() == 0 && hprofFile != null && hprofFile.exists()) {
                log.info("Target database is empty. Streaming HPROF dump into database with DHP: {}", hprofFile.getAbsolutePath());
                Pass1ScanParser pass1 = new Pass1ScanParser();
                pass1.scan(hprofFile);

                try (Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor)) {
                    ingester.ingest(hprofFile);
                }

                DominatorTreeEngine domEngine = new DominatorTreeEngine(storage);
                domEngine.computeAndStore();
            }

            int objectCount = storage.getObjectCount();
            List<HeapRecords.ClassRecord> allClasses = storage.getAllClasses();
            long totalHeapSize = storage.getTotalHeapSize();

            XSnapshotInfo info = new XSnapshotInfo();
            info.setPath(hprofFile != null ? hprofFile.getAbsolutePath() : file.getAbsolutePath());
            info.setPrefix(prefix);
            info.setProperty("$heapFormat", "dhp");
            info.setProperty("dhp.hprofPath", hprofFile != null ? hprofFile.getAbsolutePath() : "");
            info.setProperty("dhp.db.url", jdbcUrl);
            info.setProperty("dhp.db.user", user);
            info.setProperty("dhp.db.password", password);
            info.setNumberOfObjects(objectCount);
            info.setNumberOfClasses(allClasses.size());
            info.setUsedHeapSize(totalHeapSize);
            try {
                String idSizeStr = storage.getSnapshotInfo("idSize");
                if (idSizeStr != null) {
                    info.setIdentifierSize(Integer.parseInt(idSizeStr));
                }
            } catch (Exception ignored) {}

            // Populate class cache
            HashMapIntObject<ClassImpl> classesById = new HashMapIntObject<>();
            ClassImpl javaLangClass = null;
            var classStats = storage.getClassStats();
            java.lang.reflect.Field countField = null;
            java.lang.reflect.Field totalSizeField = null;
            try {
                countField = ClassImpl.class.getDeclaredField("instanceCount");
                countField.setAccessible(true);
                totalSizeField = ClassImpl.class.getDeclaredField("totalSize");
                totalSizeField.setAccessible(true);
            } catch (Exception ignored) {}

            int sysLoaderObjId = storage.getObjectIdByAddress(0L);
            it.unimi.dsi.fastutil.ints.IntArrayList bootstrapClassIds = new it.unimi.dsi.fastutil.ints.IntArrayList();
            int finalJavaLangClassObjId = -1;

            for (HeapRecords.ClassRecord cls : allClasses) {
                int classObjId = cls.classObjId() >= 0 ? cls.classObjId() : storage.getObjectIdByAddress(cls.classId());
                if (classObjId < 0) continue;
                int superClassObjId = cls.superClassObjId() >= 0 ? cls.superClassObjId() : ((cls.superClassId() != 0) ? storage.getObjectIdByAddress(cls.superClassId()) : -1);
                int classLoaderObjId = cls.classLoaderObjId() >= 0 ? cls.classLoaderObjId() : ((cls.classLoaderId() != 0) ? storage.getObjectIdByAddress(cls.classLoaderId()) : sysLoaderObjId);
                if (classLoaderObjId < 0) classLoaderObjId = sysLoaderObjId >= 0 ? sysLoaderObjId : 0;

                if (cls.classLoaderId() == 0L) {
                    bootstrapClassIds.add(classObjId);
                }
                if (finalJavaLangClassObjId < 0 && "java.lang.Class".equals(cls.name())) {
                    finalJavaLangClassObjId = classObjId;
                }

                List<FieldDescriptor> fds = new ArrayList<>();
                for (var f : cls.fields()) {
                    fds.add(new FieldDescriptor(f.name(), f.type()));
                }

                List<Field> staticFields = new ArrayList<>();
                for (var sf : cls.staticFields()) {
                    Object val = (sf.type() == 2 && sf.value() instanceof Number n)
                            ? new org.eclipse.mat.snapshot.model.ObjectReference(null, n.longValue())
                            : sf.value();
                    staticFields.add(new Field(sf.name(), sf.type(), val));
                }

                long superClassAddr = superClassObjId >= 0 ? cls.superClassId() : 0L;
                long classLoaderAddr = cls.classLoaderId() != 0 ? cls.classLoaderId() : 0L;

                ClassImpl c = new ClassImpl(
                        cls.classId(),
                        cls.name(),
                        superClassAddr,
                        classLoaderAddr,
                        staticFields.toArray(new Field[0]),
                        fds.toArray(new FieldDescriptor[0])
                );
                c.setObjectId(classObjId);
                c.setCacheEntry(classObjId);
                if (superClassObjId >= 0) {
                    c.setSuperClassIndex(superClassObjId);
                } else {
                    c.setSuperClassIndex(-1);
                }
                c.setClassLoaderIndex(classLoaderObjId);
                c.setHeapSizePerInstance(cls.instanceSize());
                long usedSize = cls.usedSize() > 0 ? cls.usedSize() : storage.getObjectUsedSize(classObjId);
                c.setUsedHeapSize(usedSize);

                var stat = classStats.get(classObjId);
                if (stat != null && countField != null && totalSizeField != null) {
                    try {
                        countField.setInt(c, stat.instanceCount());
                        totalSizeField.setLong(c, stat.totalSize());
                    } catch (Exception ignored) {}
                }

                classesById.put(classObjId, c);

                if ("java.lang.Class".equals(cls.name())) {
                    javaLangClass = c;
                }
            }

            if (javaLangClass != null) {
                for (int key : classesById.getAllKeys()) {
                    classesById.get(key).setClassInstance(javaLangClass);
                }
            }

            for (int key : classesById.getAllKeys()) {
                ClassImpl c = classesById.get(key);
                if (c.getSuperClassId() >= 0) {
                    ClassImpl superC = classesById.get(c.getSuperClassId());
                    if (superC != null) {
                        superC.addSubClass(c);
                    }
                }
            }

            // Populate GC roots
            HashMapIntObject<List<XGCRootInfo>> gcRootsMap = new HashMapIntObject<>();
            HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> thread2objects2roots = new HashMapIntObject<>();
            for (HeapRecords.GcRootRecord root : storage.getGcRoots()) {
                int objId = root.objectId() >= 0 ? root.objectId() : storage.getObjectIdByAddress(root.objectAddress());
                if (objId >= 0) {
                    if (root.threadAddress() != 0) {
                        int threadObjId = root.threadObjectId() >= 0 ? root.threadObjectId() : storage.getObjectIdByAddress(root.threadAddress());
                        if (threadObjId >= 0) {
                            var objMap = thread2objects2roots.get(threadObjId);
                            if (objMap == null) {
                                objMap = new HashMapIntObject<>();
                                thread2objects2roots.put(threadObjId, objMap);
                            }
                            List<XGCRootInfo> list = objMap.get(objId);
                            if (list == null) {
                                list = new ArrayList<>();
                                objMap.put(objId, list);
                            }
                            var xgc = new XGCRootInfo(root.objectAddress(), root.referrerAddress(), root.rootType());
                            xgc.setObjectId(objId);
                            xgc.setContextId(threadObjId);
                            list.add(xgc);
                        }
                    } else {
                        List<XGCRootInfo> list = gcRootsMap.get(objId);
                        if (list == null) {
                            list = new ArrayList<>();
                            gcRootsMap.put(objId, list);
                        }
                        var xgc = new XGCRootInfo(root.objectAddress(), root.referrerAddress(), root.rootType());
                        xgc.setObjectId(objId);
                        list.add(xgc);
                    }
                }
            }

            if (sysLoaderObjId >= 0 && !gcRootsMap.containsKey(sysLoaderObjId)) {
                List<XGCRootInfo> list = new ArrayList<>();
                var xgc = new XGCRootInfo(0L, 0L, 1);
                xgc.setObjectId(sysLoaderObjId);
                list.add(xgc);
                gcRootsMap.put(sysLoaderObjId, list);
            }

            for (HeapRecords.ClassRecord cls : allClasses) {
                if (cls.classLoaderId() == 0L) {
                    int classObjId = cls.classObjId() >= 0 ? cls.classObjId() : storage.getObjectIdByAddress(cls.classId());
                    if (classObjId >= 0 && !gcRootsMap.containsKey(classObjId)) {
                        List<XGCRootInfo> list = new ArrayList<>();
                        var xgc = new XGCRootInfo(cls.classId(), 0L, 2);
                        xgc.setObjectId(classObjId);
                        list.add(xgc);
                        gcRootsMap.put(classObjId, list);
                    }
                }
            }

            HashMapIntObject<XGCRootInfo[]> roots = new HashMapIntObject<>();
            for (int objId : gcRootsMap.getAllKeys()) {
                roots.put(objId, gcRootsMap.get(objId).toArray(new XGCRootInfo[0]));
            }

            HashMapIntObject<HashMapIntObject<XGCRootInfo[]>> rootsPerThread = new HashMapIntObject<>();
            for (int threadId : thread2objects2roots.getAllKeys()) {
                var objMap = thread2objects2roots.get(threadId);
                var targetObjMap = new HashMapIntObject<XGCRootInfo[]>();
                for (int objId : objMap.getAllKeys()) {
                    targetObjMap.put(objId, objMap.get(objId).toArray(new XGCRootInfo[0]));
                }
                rootsPerThread.put(threadId, targetObjMap);
            }

            info.setNumberOfGCRoots(roots.size());

            // Build loader labels
            HashMapIntObject<String> loaderLabels = new HashMapIntObject<>();
            loaderLabels.put(sysLoaderObjId >= 0 ? sysLoaderObjId : 0, "<system class loader>");
            for (int key : classesById.getAllKeys()) {
                ClassImpl c = classesById.get(key);
                int clId = c.getClassLoaderId();
                if (!loaderLabels.containsKey(clId)) {
                    if (clId == sysLoaderObjId || clId <= 0) {
                        loaderLabels.put(clId, "<system class loader>");
                    } else {
                        loaderLabels.put(clId, "ClassLoader@" + Long.toHexString(storage.getObjectAddress(clId)));
                    }
                }
            }
            info.setNumberOfClassLoaders(loaderLabels.size());

            // BitField array objects
            BitField arrayObjects = new BitField(objectCount);
            storage.populateArrayBitField(arrayObjects::set);

            int defaultClassId = finalJavaLangClassObjId >= 0 ? finalJavaLangClassObjId : (classesById.size() > 0 ? classesById.keys().next() : 0);
            int[] bootstrapClassIdsArray = bootstrapClassIds.toIntArray();

            IndexManager indexManager = new IndexManager();
            indexManager.setReader(IndexManager.Index.IDENTIFIER, new DbOne2LongIndex(
                    objectCount,
                    storage::getObjectAddress,
                    storage::getObjectIdByAddress
            ));

            indexManager.setReader(IndexManager.Index.O2CLASS, new DbOne2OneIndex(
                    objectCount,
                    id -> {
                        int cid = (int) storage.getObjectClassId(id);
                        if (cid < 0 || !classesById.containsKey(cid)) {
                            return defaultClassId;
                        }
                        return cid;
                    }
            ));

            indexManager.setReader(IndexManager.Index.INBOUND, new DbOne2ManyIndex(
                    objectCount,
                    storage::getInboundReferences,
                    key -> {
                        if (key instanceof Number num) {
                            return storage.getObjectsByClassId(num.intValue());
                        }
                        if (key instanceof org.eclipse.mat.snapshot.model.IClass clsKey) {
                            return storage.getObjectsByClassId(clsKey.getObjectId());
                        }
                        return new int[0];
                    }
            ));

            indexManager.setReader(IndexManager.Index.OUTBOUND, new DbOne2ManyIndex(
                    objectCount,
                    id -> {
                        int classId = (int) storage.getObjectClassId(id);
                        if (id == sysLoaderObjId) {
                            int[] orig = storage.getOutboundReferences(id);
                            it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet set = new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet();
                            if (classId >= 0) set.add(classId);
                            for (int x : orig) set.add(x);
                            for (int x : bootstrapClassIdsArray) set.add(x);
                            return set.toIntArray();
                        }
                        int[] refs = storage.getOutboundReferences(id);
                        if (classId >= 0) {
                            if (refs.length == 0 || refs[0] != classId) {
                                int[] newRefs = new int[refs.length + 1];
                                newRefs[0] = classId;
                                System.arraycopy(refs, 0, newRefs, 1, refs.length);
                                return newRefs;
                            }
                        }
                        return refs;
                    }
            ));

            indexManager.setReader(IndexManager.Index.A2SIZE, new DbOne2SizeIndex(
                    objectCount,
                    id -> (id >= 0 && id < objectCount && arrayObjects.get(id)) ? storage.getObjectUsedSize(id) : 0L
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
                    storage::getObjectIdByRetainedSize
            ));

            // Prevent writing i2sv2.index by providing RetainedSizeCache with /dev/null or safe temp backed storage
            try {
                Class<?> rscClass = loadParserClass("org.eclipse.mat.parser.internal.snapshot.RetainedSizeCache");
                Constructor<?> rscCtor = rscClass.getConstructor(File.class);
                File rscFile = new File("/dev/null").exists() ? new File("/dev/null") : File.createTempFile("dhp_rsc", ".tmp");
                rscFile.deleteOnExit();
                Object rsc = rscCtor.newInstance(rscFile);
                indexManager.setReader(IndexManager.Index.I2RETAINED, (IIndexReader) rsc);
            } catch (Throwable t) {
                log.warn("Could not instantiate RetainedSizeCache: {}", t.getMessage());
            }

            DhpHeapObjectReader reader = new DhpHeapObjectReader();

            Class<?> snapshotImplClass = loadParserClass("org.eclipse.mat.parser.internal.SnapshotImpl");
            Constructor<?> ctor = snapshotImplClass.getDeclaredConstructor(
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
            ISnapshot snapshot = (ISnapshot) ctor.newInstance(
                    info,
                    reader,
                    classesById,
                    roots,
                    rootsPerThread,
                    loaderLabels,
                    arrayObjects,
                    indexManager
            );

            java.lang.reflect.Field domField = snapshotImplClass.getDeclaredField("dominatorTreeCalculated");
            domField.setAccessible(true);
            domField.setBoolean(snapshot, true);

            reader.open(snapshot);

            openSnapshots.put(snapshot, new DhpSnapshotContext(snapshot, reader, storage));
            log.info("DHP Snapshot successfully opened with ZERO index files on disk for {}", file.getName());
            return snapshot;

        } catch (Exception e) {
            log.error("Failed to open DHP snapshot for {}: {}", file.getAbsolutePath(), e.getMessage(), e);
            throw new SnapshotException("Failed to open DHP snapshot", e);
        }
    }

    @Override
    public void dispose(ISnapshot snapshot) {
        DhpSnapshotContext ctx = openSnapshots.remove(snapshot);
        if (ctx != null) {
            try {
                ctx.close();
            } catch (Exception ignored) {}
        } else if (delegate != null) {
            delegate.dispose(snapshot);
        }
    }

    @Override
    public IOQLQuery createQuery(String queryString) throws OQLParseException, SnapshotException {
        if (delegate != null) {
            return delegate.createQuery(queryString);
        }
        try {
            Class<?> oqlClass = loadParserClass("org.eclipse.mat.parser.internal.oql.OQLQueryImpl");
            return (IOQLQuery) oqlClass.getConstructor(String.class).newInstance(queryString);
        } catch (Exception e) {
            throw new SnapshotException("Failed to instantiate OQL query", e);
        }
    }

    @Override
    public List<SnapshotFormat> getSupportedFormats() {
        List<SnapshotFormat> list = new ArrayList<>();
        list.add(new SnapshotFormat("Dynamic Heap Parser (.dhp)", new String[] { "dhp" }));
        if (delegate != null) {
            list.addAll(delegate.getSupportedFormats());
        }
        return list;
    }

    record DhpSnapshotContext(ISnapshot snapshot, DhpHeapObjectReader reader, JdbcHeapStorageEngine storage) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            reader.close();
            storage.close();
        }
    }
}
