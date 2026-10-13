package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.eclipse.mat.parser.IIndexBuilder;
import org.eclipse.mat.parser.IPreliminaryIndex;
import org.eclipse.mat.parser.model.XGCRootInfo;
import org.eclipse.mat.parser.model.XSnapshotInfo;
import org.eclipse.mat.util.IProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Eclipse MAT IIndexBuilder implementation backed by DHP database storage.
 * Reads either raw .hprof (parsing into DB with dynamic memory scaling)
 * or opens an existing .dhp / .properties connection descriptor file.
 */
public class DhpIndexBuilder implements IIndexBuilder {
    private static final Logger log = LoggerFactory.getLogger(DhpIndexBuilder.class);

    private File dumpOrConfigFile;
    private String prefix;
    private JdbcHeapStorageEngine storage;

    @Override
    public void init(File file, String prefix) throws SnapshotException, IOException {
        this.dumpOrConfigFile = file;
        this.prefix = prefix;
        DhpSnapshotFactory.install();
    }

    @Override
    public void fill(IPreliminaryIndex preliminaryIndex, IProgressListener listener) throws SnapshotException, IOException {
        try {
            DhpDescriptor descriptor = DhpDescriptor.read(dumpOrConfigFile);
            File hprofFile = descriptor.hprofFile();
            this.storage = DhpIngestion.openOrIngest(descriptor);
            int objectCount = storage.getObjectCount();

            ClassModel model = ClassModel.build(storage);
            model.link();
            log.info("Classes in the database: {}", model.classes().size());
            preliminaryIndex.setClassesById(model.classes());

            // GC roots and the locals of threads (MAT's cleaner re-maps them when it removes unreachable objects)
            HashMapIntObject<List<XGCRootInfo>> gcRootsMap = new HashMapIntObject<>();
            HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> thread2objects2roots = new HashMapIntObject<>();
            for (var root : storage.getGcRoots()) {
                int objId = root.objectId() >= 0 ? root.objectId() : storage.getObjectIdByAddress(root.objectAddress());
                if (objId < 0) {
                    continue;
                }
                if (root.threadAddress() != 0) {
                    int threadObjId = root.threadObjectId() >= 0 ? root.threadObjectId() : storage.getObjectIdByAddress(root.threadAddress());
                    if (threadObjId >= 0) {
                        // like MAT: the referrer of a stack local is its thread object
                        var xgc = new XGCRootInfo(root.objectAddress(), root.threadAddress(), RootTypes.toMat(root.rootType()));
                        xgc.setObjectId(objId);
                        xgc.setContextId(threadObjId);
                        var locals = thread2objects2roots.get(threadObjId);
                        if (locals == null) {
                            thread2objects2roots.put(threadObjId, locals = new HashMapIntObject<>());
                        }
                        var list = locals.get(objId);
                        if (list == null) {
                            locals.put(objId, list = new ArrayList<>());
                        }
                        list.add(xgc);
                    }
                } else {
                    var xgc = new XGCRootInfo(root.objectAddress(), root.referrerAddress(), RootTypes.toMat(root.rootType()));
                    xgc.setObjectId(objId);
                    var list = gcRootsMap.get(objId);
                    if (list == null) {
                        gcRootsMap.put(objId, list = new ArrayList<>());
                    }
                    list.add(xgc);
                }
            }
            preliminaryIndex.setGcRoots(gcRootsMap);
            preliminaryIndex.setThread2objects2roots(thread2objects2roots);

            preliminaryIndex.setIdentifiers(DhpIndexes.identifiers(storage, objectCount));
            preliminaryIndex.setObject2classId(DhpIndexes.objectToClass(storage, objectCount, model));
            preliminaryIndex.setOutbound(DhpIndexes.outbound(storage, objectCount, model.systemLoaderId()));
            preliminaryIndex.setArray2size(DhpIndexes.arraySizes(storage, objectCount, DhpIndexes.arrayObjects(storage, objectCount)));

            XSnapshotInfo info = preliminaryIndex.getSnapshotInfo();
            if (info != null) {
                if (hprofFile != null) {
                    info.setPath(hprofFile.getAbsolutePath());
                    info.setProperty("dhp.hprofPath", hprofFile.getAbsolutePath());
                }
                info.setProperty("dhp.db.url", descriptor.jdbcUrl());
                info.setProperty("dhp.db.user", descriptor.user());
                info.setProperty("dhp.db.password", descriptor.password());
                info.setNumberOfObjects(objectCount);
                info.setNumberOfClasses(model.classes().size());
                info.setNumberOfGCRoots(gcRootsMap.size());
                try {
                    String idSizeStr = storage.getSnapshotInfo("idSize");
                    if (idSizeStr != null) {
                        info.setIdentifierSize(Integer.parseInt(idSizeStr));
                    }
                    info.setUsedHeapSize(storage.getTotalHeapSize());
                    SnapshotProps.apply(info, storage);
                } catch (Exception ignored) {
                    // optional metadata
                }
            }
            log.info("DHP preliminary indexes populated for Eclipse MAT.");
        } catch (SQLException e) {
            throw new SnapshotException("Database error populating preliminary indexes", e);
        }
    }

    @Override
    public void clean(int[] purgedMapping, IProgressListener listener) throws IOException {
        log.info("GarbageCleaner cleaned reachable object set.");
    }

    public JdbcHeapStorageEngine getStorage() {
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
