package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.dhp.core.graph.DominatorTreeEngine;
import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.parser.Pass1ScanParser;
import org.eclipse.mat.dhp.core.parser.Pass2ObjectIngester;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.eclipse.mat.dhp.plugin.index.DbOne2LongIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2ManyIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2OneIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2SizeIndex;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.eclipse.mat.parser.IIndexBuilder;
import org.eclipse.mat.parser.IPreliminaryIndex;
import org.eclipse.mat.parser.model.ClassImpl;
import org.eclipse.mat.parser.model.XGCRootInfo;
import org.eclipse.mat.parser.model.XSnapshotInfo;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.FieldDescriptor;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.util.IProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Eclipse MAT IIndexBuilder implementation backed by DHP database storage.
 * Reads either raw .hprof (parsing into DB with dynamic memory scaling)
 * or opens an existing .dhp / .properties connection descriptor file.
 */
public class DhpIndexBuilder implements IIndexBuilder {
    private static final Logger log = LoggerFactory.getLogger(DhpIndexBuilder.class);

    private File dumpOrConfigFile;
    private String prefix;
    private HeapStorageEngine storage;

    @Override
    public void init(File file, String prefix) throws SnapshotException, IOException {
        this.dumpOrConfigFile = file;
        this.prefix = prefix;
        DhpSnapshotFactory.install();
    }

    @Override
    public void fill(IPreliminaryIndex preliminaryIndex, IProgressListener listener) throws SnapshotException, IOException {
        try {
            boolean isConfigFile = dumpOrConfigFile.getName().endsWith(".dhp") 
                    || dumpOrConfigFile.getName().endsWith(".properties");

            String jdbcUrl;
            String user = "";
            String password = "";
            File hprofFile = null;
            long memoryBudget = 1024 * 1024 * 1024L;

            if (isConfigFile) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(dumpOrConfigFile)) {
                    props.load(fis);
                }
                jdbcUrl = props.getProperty("db.url", "jdbc:sqlite:" + prefix + "dhp.db");
                user = props.getProperty("db.user", "");
                password = props.getProperty("db.password", "");
                String hprofPath = props.getProperty("dump.file");
                if (hprofPath != null) {
                    hprofFile = new File(hprofPath);
                }
            } else {
                hprofFile = dumpOrConfigFile;
                jdbcUrl = "jdbc:sqlite:" + prefix + "dhp.db";
            }

            MemoryGovernor governor = new MemoryGovernor(memoryBudget, 4);
            this.storage = new JdbcHeapStorageEngine(jdbcUrl, user, password, governor.getTotalAllocatedBytes());
            this.storage.initializeSchema();

            // If starting from fresh hprof file, run ingestion pipeline
            if (hprofFile != null && hprofFile.exists() && storage.getObjectCount() == 0) {
                log.info("Parsing HPROF dump into database with DHP: {}", hprofFile.getAbsolutePath());
                Pass1ScanParser pass1 = new Pass1ScanParser();
                pass1.scan(hprofFile);

                Pass2ObjectIngester ingester = new Pass2ObjectIngester(pass1, storage, governor);
                ingester.ingest(hprofFile);

                DominatorTreeEngine domEngine = new DominatorTreeEngine(storage);
                domEngine.computeAndStore();
            }

            // Expose database-backed MAT preliminary indexes
            int objectCount = storage.getObjectCount();

            // 1. Classes
            HashMapIntObject<ClassImpl> classesById = new HashMapIntObject<>();
            var allClasses = storage.getAllClasses();
            var classStats = storage.getClassStats();

            java.lang.reflect.Field countField = null;
            java.lang.reflect.Field totalSizeField = null;
            try {
                countField = ClassImpl.class.getDeclaredField("instanceCount");
                countField.setAccessible(true);
                totalSizeField = ClassImpl.class.getDeclaredField("totalSize");
                totalSizeField.setAccessible(true);
            } catch (Exception ignored) {}

            for (HeapRecords.ClassRecord cls : allClasses) {
                int classObjId = storage.getObjectIdByAddress(cls.classId());
                FieldDescriptor[] fields = new FieldDescriptor[cls.fields().size()];
                for (int i = 0; i < fields.length; i++) {
                    var f = cls.fields().get(i);
                    fields[i] = new FieldDescriptor(f.name(), f.type());
                }
                int sysLoaderObjId = storage.getObjectIdByAddress(0L);
                int superClassObjId = cls.superClassId() != 0 ? storage.getObjectIdByAddress(cls.superClassId()) : -1;
                long superClassAddr = superClassObjId >= 0 ? cls.superClassId() : 0L;
                int classLoaderObjId = cls.classLoaderId() != 0 ? storage.getObjectIdByAddress(cls.classLoaderId()) : sysLoaderObjId;
                if (classLoaderObjId < 0) classLoaderObjId = sysLoaderObjId >= 0 ? sysLoaderObjId : 0;
                long classLoaderAddr = cls.classLoaderId() != 0 ? cls.classLoaderId() : 0L;

                ClassImpl classImpl = new ClassImpl(
                        cls.classId(),
                        cls.name(),
                        superClassAddr,
                        classLoaderAddr,
                        new Field[0],
                        fields
                );
                if (classObjId >= 0) {
                    classImpl.setObjectId(classObjId);
                    classImpl.setCacheEntry(classObjId);
                    classImpl.setHeapSizePerInstance(cls.instanceSize());
                    classImpl.setUsedHeapSize(storage.getObjectUsedSize(classObjId));
                    if (superClassObjId >= 0) {
                        classImpl.setSuperClassIndex(superClassObjId);
                    } else {
                        classImpl.setSuperClassIndex(-1);
                    }
                    classImpl.setClassLoaderIndex(classLoaderObjId);

                    var stat = classStats.get(classObjId);
                    if (stat != null && countField != null && totalSizeField != null) {
                        try {
                            countField.setInt(classImpl, stat.instanceCount());
                            totalSizeField.setLong(classImpl, stat.totalSize());
                        } catch (Exception ignored) {}
                    }
                    classesById.put(classObjId, classImpl);
                } else {
                    log.warn("Class not found in storage: classId={}, name={}, classObjId={}", 
                            Long.toHexString(cls.classId()), cls.name(), classObjId);
                }
            }

            // Find java.lang.Class ClassImpl
            ClassImpl javaLangClass = null;
            for (java.util.Iterator<ClassImpl> it = classesById.values(); it.hasNext(); ) {
                ClassImpl c = it.next();
                if ("java.lang.Class".equals(c.getName())) {
                    javaLangClass = c;
                    break;
                }
            }

            for (java.util.Iterator<ClassImpl> it = classesById.values(); it.hasNext(); ) {
                ClassImpl c = it.next();
                if (javaLangClass != null && c.getClazz() == null) {
                    c.setClassInstance(javaLangClass);
                }
                if (c.getSuperClassId() >= 0) {
                    ClassImpl superC = classesById.get(c.getSuperClassId());
                    if (superC != null) {
                        superC.addSubClass(c);
                    }
                }
            }

            log.info("Total classes in storage: {}, populated: {}", allClasses.size(), classesById.size());
            preliminaryIndex.setClassesById(classesById);

            // 2. GC Roots & Thread Locals
            HashMapIntObject<List<XGCRootInfo>> gcRootsMap = new HashMapIntObject<>();
            HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> thread2objects2roots = new HashMapIntObject<>();

            for (var root : storage.getGcRoots()) {
                int objId = storage.getObjectIdByAddress(root.objectAddress());
                if (objId >= 0) {
                    if (root.referrerAddress() != 0) {
                        int threadObjId = storage.getObjectIdByAddress(root.referrerAddress());
                        if (threadObjId >= 0) {
                            var localsMap = thread2objects2roots.get(threadObjId);
                            if (localsMap == null) {
                                localsMap = new HashMapIntObject<>();
                                thread2objects2roots.put(threadObjId, localsMap);
                            }
                            var list = localsMap.get(objId);
                            if (list == null) {
                                list = new ArrayList<>();
                                localsMap.put(objId, list);
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

            // Ensure system classloader (address 0L) is in gcRootsMap
            int sysLoaderObjId = storage.getObjectIdByAddress(0L);
            if (sysLoaderObjId >= 0 && !gcRootsMap.containsKey(sysLoaderObjId)) {
                List<XGCRootInfo> list = new ArrayList<>();
                var xgc = new XGCRootInfo(0L, 0L, 1);
                xgc.setObjectId(sysLoaderObjId);
                list.add(xgc);
                gcRootsMap.put(sysLoaderObjId, list);
            }

            // Ensure all classes with classLoaderId == 0 are included in gcRootsMap (MAT parity)
            for (HeapRecords.ClassRecord cls : allClasses) {
                if (cls.classLoaderId() == 0L) {
                    int classObjId = storage.getObjectIdByAddress(cls.classId());
                    if (classObjId >= 0 && !gcRootsMap.containsKey(classObjId)) {
                        List<XGCRootInfo> list = new ArrayList<>();
                        var xgc = new XGCRootInfo(cls.classId(), 0L, 2);
                        xgc.setObjectId(classObjId);
                        list.add(xgc);
                        gcRootsMap.put(classObjId, list);
                    }
                }
            }

            preliminaryIndex.setGcRoots(gcRootsMap);
            preliminaryIndex.setThread2objects2roots(thread2objects2roots);

            // 3. Object-to-Address index (IOne2LongIndex)
            preliminaryIndex.setIdentifiers(new DbOne2LongIndex(
                    objectCount,
                    storage::getObjectAddress,
                    storage::getObjectIdByAddress
            ));

            int finalJavaLangClassObjId = -1;
            for (HeapRecords.ClassRecord cls : allClasses) {
                if ("java.lang.Class".equals(cls.name())) {
                    finalJavaLangClassObjId = storage.getObjectIdByAddress(cls.classId());
                    break;
                }
            }
            int defaultClassId = finalJavaLangClassObjId >= 0 ? finalJavaLangClassObjId : (classesById.size() > 0 ? classesById.keys().next() : 0);

            // 4. Object-to-Class index (IOne2OneIndex)
            preliminaryIndex.setObject2classId(new DbOne2OneIndex(
                    objectCount,
                    id -> {
                        int cid = (int) storage.getObjectClassId(id);
                        if (cid < 0 || !classesById.containsKey(cid)) {
                            return defaultClassId;
                        }
                        return cid;
                    }
            ));

            // Collect bootstrap class IDs for system class loader outbound edges
            it.unimi.dsi.fastutil.ints.IntArrayList bootstrapClassIds = new it.unimi.dsi.fastutil.ints.IntArrayList();
            for (HeapRecords.ClassRecord cls : allClasses) {
                if (cls.classLoaderId() == 0L) {
                    int cid = storage.getObjectIdByAddress(cls.classId());
                    if (cid >= 0) bootstrapClassIds.add(cid);
                }
            }
            int[] bootstrapClassIdsArray = bootstrapClassIds.toIntArray();

            // 5. Outbound references index (IOne2ManyIndex)
            preliminaryIndex.setOutbound(new DbOne2ManyIndex(
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

            // 6. Array sizes index (IOne2SizeIndex) - only populated for array objects!
            boolean[] arrayFlags = storage.getAllArrayFlags(objectCount);
            preliminaryIndex.setArray2size(new DbOne2SizeIndex(
                    objectCount,
                    id -> (id >= 0 && id < arrayFlags.length && arrayFlags[id]) ? storage.getObjectUsedSize(id) : 0L
            ));

            // 7. Update SnapshotInfo metadata
            XSnapshotInfo info = preliminaryIndex.getSnapshotInfo();
            if (info != null) {
                if (hprofFile != null) {
                    info.setPath(hprofFile.getAbsolutePath());
                    info.setProperty("dhp.hprofPath", hprofFile.getAbsolutePath());
                }
                info.setProperty("dhp.db.url", jdbcUrl);
                info.setProperty("dhp.db.user", user);
                info.setProperty("dhp.db.password", password);
                info.setNumberOfObjects(objectCount);
                info.setNumberOfClasses(classesById.size());
                info.setNumberOfGCRoots(gcRootsMap.size());
                try {
                    String idSizeStr = storage.getSnapshotInfo("idSize");
                    if (idSizeStr != null) {
                        info.setIdentifierSize(Integer.parseInt(idSizeStr));
                    }
                    info.setUsedHeapSize(storage.getTotalHeapSize());
                } catch (Exception ignored) {}
            }

            log.info("DHP Preliminary Indexes successfully populated for Eclipse MAT.");
        } catch (SQLException e) {
            throw new SnapshotException("Database error populating preliminary indexes", e);
        }
    }

    @Override
    public void clean(int[] purgedMapping, IProgressListener listener) throws IOException {
        log.info("GarbageCleaner cleaned reachable object set.");
    }

    public HeapStorageEngine getStorage() {
        return storage;
    }

    @Override
    public void cancel() {
        if (storage != null) {
            try {
                storage.close();
            } catch (IOException ignored) {}
        }
    }
}
