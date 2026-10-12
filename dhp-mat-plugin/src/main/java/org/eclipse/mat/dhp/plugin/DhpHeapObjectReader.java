package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.parser.IObjectReader;
import org.eclipse.mat.parser.model.AbstractObjectImpl;
import org.eclipse.mat.parser.model.ClassImpl;
import org.eclipse.mat.parser.model.ClassLoaderImpl;
import org.eclipse.mat.parser.model.InstanceImpl;
import org.eclipse.mat.parser.model.ObjectArrayImpl;
import org.eclipse.mat.parser.model.PrimitiveArrayImpl;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.FieldDescriptor;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.snapshot.model.ObjectReference;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Eclipse MAT IObjectReader implementation backed by underlying HPROF dump file via RandomAccessFile
 * and indexed positions.
 */
public class DhpHeapObjectReader implements IObjectReader {

    private volatile HprofMap raf;
    /** Why object payloads cannot be read (null when the dump file is open). */
    private String dumpProblem;
    private int idSize = 8;
    private final java.util.concurrent.ConcurrentHashMap<Integer, FieldDescriptor[]> layouts = new java.util.concurrent.ConcurrentHashMap<>();
    private org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine posStorage;

    @Override
    public void open(ISnapshot snapshot) throws SnapshotException, IOException {
        String hprofPath = (String) snapshot.getSnapshotInfo().getProperty("dhp.hprofPath");
        if (hprofPath == null || hprofPath.isEmpty()) {
            hprofPath = snapshot.getSnapshotInfo().getPath();
        }
        File file = new File(hprofPath);
        File baseDir = file.getParentFile();
        if (file.exists() && file.getName().toLowerCase().endsWith(".dhp")) {
            try (FileInputStream fis = new FileInputStream(file)) {
                Properties props = new Properties();
                props.load(fis);
                String dumpPath = props.getProperty("dump.file");
                if (dumpPath != null && !dumpPath.isBlank()) {
                    File candidate = new File(dumpPath);
                    if (!candidate.isAbsolute() && baseDir != null) {
                        candidate = new File(baseDir, dumpPath);
                    }
                    file = candidate;
                }
            } catch (Exception ignored) {}
        }
        if (file.getName().toLowerCase().endsWith(".gz")) {
            // positions recorded during ingestion refer to the decompressed stream, which cannot be seeked here
            this.dumpProblem = "compressed heap dumps are not supported for object inspection, decompress " + file;
        } else if (!file.exists() || file.isDirectory()) {
            this.dumpProblem = "the HPROF dump " + file + " is not available";
        }
        if (dumpProblem == null) {
            this.raf = new HprofMap(file);
            this.idSize = snapshot.getSnapshotInfo().getIdentifierSize();
            if (this.idSize <= 0) {
                this.idSize = 8;
            }
        }

        String jdbcUrl = (String) snapshot.getSnapshotInfo().getProperty("dhp.db.url");
        String user = (String) snapshot.getSnapshotInfo().getProperty("dhp.db.user");
        String pass = (String) snapshot.getSnapshotInfo().getProperty("dhp.db.password");
        if (jdbcUrl == null && snapshot.getSnapshotInfo().getPath().toLowerCase().endsWith(".dhp")) {
            File propFile = new File(snapshot.getSnapshotInfo().getPath());
            if (propFile.exists()) {
                try (FileInputStream fis = new FileInputStream(propFile)) {
                    Properties props = new Properties();
                    props.load(fis);
                    jdbcUrl = props.getProperty("db.url");
                    user = props.getProperty("db.user", "");
                    pass = props.getProperty("db.password", "");
                } catch (Exception ignored) {}
            }
        }
        if (jdbcUrl != null && !jdbcUrl.isEmpty()) {
            if (jdbcUrl.startsWith("jdbc:sqlite:") && !jdbcUrl.startsWith("jdbc:sqlite::memory:")) {
                String sub = jdbcUrl.substring("jdbc:sqlite:".length());
                File dbFile = new File(sub);
                if (!dbFile.isAbsolute() && baseDir != null) {
                    jdbcUrl = "jdbc:sqlite:" + new File(baseDir, sub).getAbsolutePath();
                }
            }
            try {
                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException ignored) {}
                this.posStorage = new org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine(
                        jdbcUrl, user != null ? user : "", pass != null ? pass : "", 64L * 1024 * 1024);
                System.out.println("[DHP-Reader] Successfully connected to database: " + jdbcUrl);
            } catch (Exception e) {
                System.err.println("[DHP-Reader] Could not connect to database " + jdbcUrl + ": " + e.getMessage());
            }
        }
    }

    private void requireDump(long filePos) throws SnapshotException {
        if (raf == null && filePos > 0) {
            throw new SnapshotException("Cannot read object data: " + (dumpProblem != null ? dumpProblem : "the HPROF dump is not open"));
        }
    }

    @Override
    public IObject read(int objectId, ISnapshot snapshot) throws SnapshotException, IOException {
        long address = snapshot.mapIdToAddress(objectId);
        IClass targetClass = snapshot.getClassOf(objectId);
        if (targetClass == null) {
            return new InstanceImpl(objectId, address, null, Collections.emptyList());
        }

        // Retrieve file position index if available
        LongLookup posLookup = (LongLookup) snapshot.getSnapshotInfo().getProperty("dhp.o2pos");
        long filePos = 0L;
        if (posLookup != null) {
            try {
                filePos = posLookup.get(objectId);
            } catch (Exception ignored) {}
        } else if (posStorage != null && objectId >= 0) {
            try {
                filePos = posStorage.getObjectFilePosition(objectId);
            } catch (Exception ignored) {}
        }

        // If object is an array, return PrimitiveArrayImpl or ObjectArrayImpl with file offset
        if (targetClass.isArrayType()) {
            ClassImpl cImpl = (ClassImpl) targetClass;
            String name = cImpl.getName();
            int primType = -1;
            for (int i = 0; i < IPrimitiveArray.TYPE.length; i++) {
                if (name.equals(IPrimitiveArray.TYPE[i])) {
                    primType = i;
                    break;
                }
            }

            if (primType >= 0) {
                // Primitive array: header is [id: idSize][serial: 4][size: 4][type: 1]
                int arrayLen = 0;
                long dataPos = 0;
                requireDump(filePos);
                if (raf != null && filePos > 0) {
                    arrayLen = raf.getInt(filePos + 1 + idSize + 4);
                    dataPos = filePos + 1 + idSize + 4 + 4 + 1;
                }
                PrimitiveArrayImpl arr = new PrimitiveArrayImpl(objectId, address, cImpl, arrayLen, primType);
                arr.setInfo(dataPos);
                return arr;
            } else {
                // Object array: header is [id: idSize][serial: 4][size: 4][classId: idSize]
                int arrayLen = 0;
                long dataPos = 0;
                requireDump(filePos);
                if (raf != null && filePos > 0) {
                    arrayLen = raf.getInt(filePos + 1 + idSize + 4);
                    dataPos = filePos + 1 + idSize + 4 + 4 + idSize;
                }
                ObjectArrayImpl arr = new ObjectArrayImpl(objectId, address, cImpl, arrayLen);
                arr.setInfo(dataPos);
                return arr;
            }
        }

        // If no file stream available (or synthetic object), return shallow instance
        requireDump(filePos);
        if (raf == null || address == 0) {
            ClassImpl cImpl = (ClassImpl) targetClass;
            return snapshot.isClassLoader(objectId)
                    ? new ClassLoaderImpl(objectId, address, cImpl, Collections.emptyList())
                    : new InstanceImpl(objectId, address, cImpl, Collections.emptyList());
        }

        // Field layout in standard MAT order (target class first, followed by superclasses), cached per class
        FieldDescriptor[] layout = layouts.get(targetClass.getObjectId());
        if (layout == null) {
            List<FieldDescriptor> flat = new ArrayList<>();
            for (IClass cls : resolveClassHierarchy(snapshot, targetClass)) {
                flat.addAll(cls.getFieldDescriptors());
            }
            layout = flat.toArray(new FieldDescriptor[0]);
            layouts.put(targetClass.getObjectId(), layout);
        }

        List<Field> instanceFields = new ArrayList<>(layout.length);
        if (raf != null && filePos > 0) {
            // INSTANCE_DUMP: [tag: 1][id: idSize][serial: 4][classId: idSize][bytesFollowing: 4][field values]
            long pos = filePos + 1 + idSize + 4 + idSize + 4;
            for (FieldDescriptor fd : layout) {
                int type = fd.getType();
                instanceFields.add(new Field(fd.getName(), type, readBinaryValue(type, pos, snapshot)));
                pos += type == IObject.Type.OBJECT ? idSize : IPrimitiveArray.ELEMENT_SIZE[type];
            }
        } else {
            for (FieldDescriptor fd : layout) {
                int type = fd.getType();
                instanceFields.add(new Field(fd.getName(), type, readDefaultOrMockValue(type, snapshot)));
            }
        }

        ClassImpl classImpl = (ClassImpl) targetClass;
        if (snapshot.isClassLoader(objectId)) {
            return new ClassLoaderImpl(objectId, address, classImpl, instanceFields);
        } else {
            return new InstanceImpl(objectId, address, classImpl, instanceFields);
        }
    }

    private Object readBinaryValue(int type, long pos, ISnapshot snapshot) throws IOException {
        return switch (type) {
            case IObject.Type.OBJECT -> {
                long id = (idSize == 4) ? (raf.getInt(pos) & 0xFFFFFFFFL) : raf.getLong(pos);
                yield id == 0 ? null : new ObjectReference(snapshot, id);
            }
            case IObject.Type.BOOLEAN -> raf.getByte(pos) != 0;
            case IObject.Type.BYTE -> raf.getByte(pos);
            case IObject.Type.CHAR -> raf.getChar(pos);
            case IObject.Type.SHORT -> raf.getShort(pos);
            case IObject.Type.INT -> raf.getInt(pos);
            case IObject.Type.LONG -> raf.getLong(pos);
            case IObject.Type.FLOAT -> Float.intBitsToFloat(raf.getInt(pos));
            case IObject.Type.DOUBLE -> Double.longBitsToDouble(raf.getLong(pos));
            default -> null;
        };
    }

    @FunctionalInterface
    public interface LongLookup extends java.io.Serializable {
        long get(int id) throws Exception;
    }

    private List<IClass> resolveClassHierarchy(ISnapshot snapshot, IClass clazz) throws SnapshotException {
        List<IClass> list = new ArrayList<>();
        list.add(clazz);
        while (clazz.hasSuperClass()) {
            clazz = (IClass) snapshot.getObject(clazz.getSuperClassId());
            if (clazz == null) break;
            list.add(clazz);
        }
        return list;
    }

    private Object readDefaultOrMockValue(int type, ISnapshot snapshot) {
        return switch (type) {
            case IObject.Type.BOOLEAN -> Boolean.FALSE;
            case IObject.Type.BYTE -> (byte) 0;
            case IObject.Type.CHAR -> '\0';
            case IObject.Type.SHORT -> (short) 0;
            case IObject.Type.INT -> 0;
            case IObject.Type.LONG -> 0L;
            case IObject.Type.FLOAT -> 0.0f;
            case IObject.Type.DOUBLE -> 0.0d;
            case IObject.Type.OBJECT -> null;
            default -> null;
        };
    }

    @Override
    public Object readPrimitiveArrayContent(PrimitiveArrayImpl array, int offset, int length)
            throws IOException, SnapshotException {
        int type = array.getType();
        int elementSize = IPrimitiveArray.ELEMENT_SIZE[type];
        Object result = Array.newInstance(IPrimitiveArray.COMPONENT_TYPE[type], length);
        HprofMap map = raf;

        if (map == null && length > 0 && array.getInfo() instanceof Long pos && pos > 0) {
            requireDump(pos);
        }
        if (map == null || length == 0) {
            return result;
        }

        if (array.getInfo() instanceof Long filePos && filePos > 0) {
            byte[] buffer = new byte[length * elementSize];
            map.copy(filePos + (long) offset * elementSize, buffer, 0, buffer.length);
            if (type == IObject.Type.BYTE) {
                return buffer;
            }
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buffer); // big endian, like HPROF
            switch (type) {
                case IObject.Type.BOOLEAN -> {
                    boolean[] a = (boolean[]) result;
                    for (int i = 0; i < length; i++) a[i] = buffer[i] != 0;
                }
                case IObject.Type.CHAR -> bb.asCharBuffer().get((char[]) result);
                case IObject.Type.SHORT -> bb.asShortBuffer().get((short[]) result);
                case IObject.Type.INT -> bb.asIntBuffer().get((int[]) result);
                case IObject.Type.LONG -> bb.asLongBuffer().get((long[]) result);
                case IObject.Type.FLOAT -> bb.asFloatBuffer().get((float[]) result);
                case IObject.Type.DOUBLE -> bb.asDoubleBuffer().get((double[]) result);
                default -> { }
            }
        }
        return result;
    }

    @Override
    public long[] readObjectArrayContent(ObjectArrayImpl array, int offset, int length)
            throws IOException, SnapshotException {
        long[] result = new long[length];
        HprofMap map = raf;
        if (map == null && length > 0 && array.getInfo() instanceof Long pos && pos > 0) {
            requireDump(pos);
        }
        if (map == null || length == 0) {
            return result;
        }

        if (array.getInfo() instanceof Long filePos && filePos > 0) {
            byte[] buffer = new byte[length * idSize];
            map.copy(filePos + (long) offset * idSize, buffer, 0, buffer.length);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buffer);
            if (idSize == 4) {
                for (int i = 0; i < length; i++) result[i] = bb.getInt() & 0xFFFFFFFFL;
            } else {
                bb.asLongBuffer().get(result);
            }
        }
        return result;
    }

    @Override
    public <A> A getAddon(Class<A> addon) throws SnapshotException {
        return null;
    }

    @Override
    public void close() throws IOException {
        HprofMap map = raf;
        raf = null;
        if (map != null) {
            map.close();
        }
        if (posStorage != null) {
            try {
                posStorage.close();
            } catch (Exception ignored) {}
            posStorage = null;
        }
    }
}
