package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.BitField;
import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.eclipse.mat.parser.IObjectReader;
import org.eclipse.mat.parser.index.IndexManager;
import org.eclipse.mat.parser.index.IIndexReader;
import org.eclipse.mat.parser.model.ClassImpl;
import org.eclipse.mat.parser.model.XGCRootInfo;
import org.eclipse.mat.parser.model.XSnapshotInfo;
import org.eclipse.mat.snapshot.IOQLQuery;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.OQLParseException;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.SnapshotFormat;
import org.eclipse.mat.util.IProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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

    @Override
    public ISnapshot openSnapshot(File file, Map<String, String> arguments, IProgressListener listener) throws SnapshotException {
        if (!DhpDescriptor.isDescriptor(file)) {
            if (delegate != null) {
                return delegate.openSnapshot(file, arguments, listener);
            }
            throw new SnapshotException("No delegate factory available to open " + file);
        }

        log.info("Opening DHP heap dump descriptor: {}", file.getAbsolutePath());
        JdbcHeapStorageEngine storage = null;   // for cleanup if opening fails half way
        DhpHeapObjectReader reader = null;
        try {
            DhpDescriptor descriptor = DhpDescriptor.read(file);
            File hprofFile = descriptor.hprofFile();
            storage = DhpIngestion.openOrIngest(descriptor);

            int objectCount = storage.getObjectCount();
            ClassModel model = ClassModel.build(storage);
            model.link();
            final HashMapIntObject<ClassImpl> classesById = model.classes();

            XSnapshotInfo info = new XSnapshotInfo();
            info.setPath(hprofFile != null ? hprofFile.getAbsolutePath() : file.getAbsolutePath());
            info.setPrefix(descriptor.prefix());
            info.setProperty("$heapFormat", "hprof"); // what MAT reports for an HPROF dump (DHP specifics live in the dhp.* properties)
            info.setProperty("dhp.hprofPath", hprofFile != null ? hprofFile.getAbsolutePath() : "");
            info.setProperty("dhp.db.url", descriptor.jdbcUrl());
            info.setProperty("dhp.db.user", descriptor.user());
            info.setProperty("dhp.db.password", descriptor.password());
            info.setNumberOfObjects(objectCount);
            info.setNumberOfClasses(classesById.size());
            info.setUsedHeapSize(storage.getTotalHeapSize());
            SnapshotProps.apply(info, storage);
            try {
                String idSizeStr = storage.getSnapshotInfo("idSize");
                if (idSizeStr != null) {
                    info.setIdentifierSize(Integer.parseInt(idSizeStr));
                }
            } catch (Exception ignored) {
                // MAT falls back to 8-byte identifiers
            }

            // GC roots and the {@code <Java Local>} references of threads, built like MAT builds them (also its map order)
            RootMaps rootMaps = RootMaps.build(storage.getGcRoots());
            HashMapIntObject<XGCRootInfo[]> roots = rootMaps.roots;
            HashMapIntObject<HashMapIntObject<XGCRootInfo[]>> rootsPerThread = rootMaps.rootsPerThread;
            info.setNumberOfGCRoots(roots.size());

            final int sysLoaderObjId = model.systemLoaderId();
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

            BitField arrayObjects = DhpIndexes.arrayObjects(storage, objectCount);

            IndexManager indexManager = new IndexManager();
            indexManager.setReader(IndexManager.Index.IDENTIFIER, DhpIndexes.identifiers(storage, objectCount));
            indexManager.setReader(IndexManager.Index.O2CLASS, DhpIndexes.objectToClass(storage, objectCount, model));
            indexManager.setReader(IndexManager.Index.INBOUND, DhpIndexes.inbound(storage, objectCount));
            indexManager.setReader(IndexManager.Index.OUTBOUND, DhpIndexes.outbound(storage, objectCount, sysLoaderObjId));
            indexManager.setReader(IndexManager.Index.A2SIZE, DhpIndexes.arraySizes(storage, objectCount, arrayObjects));
            indexManager.setReader(IndexManager.Index.DOMINATOR, DhpIndexes.dominator(storage, objectCount));
            indexManager.setReader(IndexManager.Index.DOMINATED, DhpIndexes.dominated(storage, objectCount));
            indexManager.setReader(IndexManager.Index.O2RETAINED, DhpIndexes.retained(storage, objectCount));

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

            reader = new DhpHeapObjectReader();

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

            // Let MAT derive the class loader labels (class specific names), the used heap size and the counts exactly
            // like it does for snapshots it parsed itself. Needs the reader, hence after open().
            try {
                java.lang.reflect.Method calc = snapshotImplClass.getDeclaredMethod("calculateLoaderLabels");
                calc.setAccessible(true);
                calc.invoke(snapshot);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("Could not compute class loader labels through MAT: {}", cause.toString());
                System.err.println("[DHP] Could not compute class loader labels through MAT: " + cause);
                cause.printStackTrace();
            }

            final JdbcHeapStorageEngine opened = storage;
            openSnapshots.put(snapshot, new DhpSnapshotContext(reader, opened));
            log.info("DHP Snapshot successfully opened with ZERO index files on disk for {}", file.getName());
            return snapshot;

        } catch (Exception e) {
            log.error("Failed to open DHP snapshot for {}: {}", file.getAbsolutePath(), e.getMessage(), e);
            // do not leak the database connections and the dump file handle of a snapshot that never opened
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (storage != null) {
                try { storage.close(); } catch (Exception ignored) {}
            }
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

    /** What must be released with a snapshot. It must not reference the snapshot: it is the value of a weak map keyed by it. */
    record DhpSnapshotContext(DhpHeapObjectReader reader, JdbcHeapStorageEngine storage) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                reader.close();
            } finally {
                storage.close(); // also when closing the reader failed
            }
        }
    }
}
