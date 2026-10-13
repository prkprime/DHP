package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
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
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MAT's {@link IObjectReader} for DHP snapshots. The database knows where every object starts in the HPROF file
 * (the file position column); this reader seeks there through {@link HprofMap} and decodes instance fields, array
 * headers and array contents on demand, so no object payload is ever kept in the database. It fails with a clear
 * message, instead of returning zeros, when the dump file has been moved away.
 */
public class DhpHeapObjectReader implements IObjectReader {

    private volatile HprofMap raf;
    /** Why object payloads cannot be read (null when the dump file is open). */
    private String dumpProblem;
    private int idSize = 8;
    private final java.util.concurrent.ConcurrentHashMap<Integer, FieldDescriptor[]> layouts = new java.util.concurrent.ConcurrentHashMap<>();
    private JdbcHeapStorageEngine posStorage;

    @Override
    public void open(ISnapshot snapshot) throws SnapshotException, IOException {
        org.eclipse.mat.snapshot.SnapshotInfo info = snapshot.getSnapshotInfo();
        String hprofPath = (String) info.getProperty("dhp.hprofPath");
        if (hprofPath == null || hprofPath.isEmpty()) {
            hprofPath = info.getPath();
        }
        File file = new File(hprofPath);
        DhpDescriptor descriptor = DhpDescriptor.isDescriptor(file) && file.exists() ? DhpDescriptor.read(file) : null;
        if (descriptor != null && descriptor.hprofFile() != null) {
            file = descriptor.hprofFile();
        }
        if (file.getName().toLowerCase().endsWith(".gz")) {
            // positions recorded during ingestion refer to the decompressed stream, which cannot be seeked here
            this.dumpProblem = "compressed heap dumps are not supported for object inspection, decompress " + file;
        } else if (!file.exists() || file.isDirectory()) {
            this.dumpProblem = "the HPROF dump " + file + " is not available";
        }
        if (dumpProblem == null) {
            this.raf = new HprofMap(file);
            this.idSize = info.getIdentifierSize() > 0 ? info.getIdentifierSize() : 8;
        }

        // the factory stores the connection in the snapshot info; a reader opened from a descriptor falls back to it
        String jdbcUrl = (String) info.getProperty("dhp.db.url");
        String user = (String) info.getProperty("dhp.db.user");
        String pass = (String) info.getProperty("dhp.db.password");
        if (jdbcUrl == null && DhpDescriptor.isDescriptor(new File(info.getPath())) && new File(info.getPath()).exists()) {
            DhpDescriptor d = DhpDescriptor.read(new File(info.getPath()));
            jdbcUrl = d.jdbcUrl();
            user = d.user();
            pass = d.password();
        }
        if (jdbcUrl != null && !jdbcUrl.isEmpty()) {
            try {
                this.posStorage = new JdbcHeapStorageEngine(jdbcUrl, user != null ? user : "", pass != null ? pass : "", 64L * 1024 * 1024);
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

        // where the object's record starts in the dump (0 for synthetic objects such as the system class loader)
        long filePos = 0L;
        if (posStorage != null && objectId >= 0) {
            try {
                filePos = posStorage.getObjectFilePosition(objectId);
            } catch (java.sql.SQLException e) {
                // never show default values for an object whose data could not be located
                throw new SnapshotException("Cannot look up where object " + objectId + " is stored: " + e.getMessage(), e);
            }
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
            long pos = filePos + 1 + idSize + 4 + idSize;
            int bytes = raf.getInt(pos);
            byte[] data = new byte[bytes];
            raf.copy(pos + 4, data, 0, bytes);
            int off = 0;
            for (FieldDescriptor fd : layout) {
                int type = fd.getType();
                Object val;
                switch (type) {
                    case IObject.Type.OBJECT -> {
                        long id = idSize == 4 ? be(data, off, 4) & 0xFFFFFFFFL : be(data, off, 8);
                        val = id == 0 ? null : new ObjectReference(snapshot, id);
                        off += idSize;
                    }
                    case IObject.Type.BOOLEAN -> { val = data[off] != 0; off += 1; }
                    case IObject.Type.BYTE -> { val = data[off]; off += 1; }
                    case IObject.Type.CHAR -> { val = (char) be(data, off, 2); off += 2; }
                    case IObject.Type.SHORT -> { val = (short) be(data, off, 2); off += 2; }
                    case IObject.Type.INT -> { val = (int) be(data, off, 4); off += 4; }
                    case IObject.Type.LONG -> { val = be(data, off, 8); off += 8; }
                    case IObject.Type.FLOAT -> { val = Float.intBitsToFloat((int) be(data, off, 4)); off += 4; }
                    case IObject.Type.DOUBLE -> { val = Double.longBitsToDouble(be(data, off, 8)); off += 8; }
                    default -> val = null;
                }
                instanceFields.add(new Field(fd.getName(), type, val));
            }
        } else {
            for (FieldDescriptor fd : layout) {
                int type = fd.getType();
                instanceFields.add(new Field(fd.getName(), type, defaultValue(type, snapshot)));
            }
        }

        ClassImpl classImpl = (ClassImpl) targetClass;
        if (snapshot.isClassLoader(objectId)) {
            return new ClassLoaderImpl(objectId, address, classImpl, instanceFields);
        } else {
            return new InstanceImpl(objectId, address, classImpl, instanceFields);
        }
    }

    /** Size in bytes of a slice of an array, or an error if one Java array cannot hold it. */
    private static int checkedBytes(int length, int elementSize) throws SnapshotException {
        long bytes = (long) length * elementSize;
        if (length < 0 || bytes > Integer.MAX_VALUE - 8) {
            throw new SnapshotException("Cannot read " + length + " elements of " + elementSize + " bytes at once");
        }
        return (int) bytes;
    }

    private static long be(byte[] d, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 8) | (d[off + i] & 0xFFL);
        }
        return v;
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

    private Object defaultValue(int type, ISnapshot snapshot) {
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
            byte[] buffer = new byte[checkedBytes(length, elementSize)];
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
            byte[] buffer = new byte[checkedBytes(length, idSize)];
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
