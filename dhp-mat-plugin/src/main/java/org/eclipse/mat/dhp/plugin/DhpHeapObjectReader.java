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
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Eclipse MAT IObjectReader implementation backed by underlying HPROF dump file via RandomAccessFile
 * and indexed positions.
 */
public class DhpHeapObjectReader implements IObjectReader {

    private RandomAccessFile raf;
    private int idSize = 8;

    @Override
    public void open(ISnapshot snapshot) throws SnapshotException, IOException {
        String path = snapshot.getSnapshotInfo().getPath();
        File file = new File(path);
        if (file.exists() && !file.isDirectory()) {
            this.raf = new RandomAccessFile(file, "r");
            this.idSize = snapshot.getSnapshotInfo().getIdentifierSize();
            if (this.idSize <= 0) {
                this.idSize = 8;
            }
        }
    }

    @Override
    public synchronized IObject read(int objectId, ISnapshot snapshot) throws SnapshotException, IOException {
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
        }

        // If object is an array, return PrimitiveArrayImpl or ObjectArrayImpl with file offset
        if (targetClass.isArrayType()) {
            ClassImpl cImpl = (ClassImpl) targetClass;
            String name = cImpl.getName();
            int primType = -1;
            for (int i = 0; i < IPrimitiveArray.TYPE.length; i++) {
                if (IPrimitiveArray.TYPE[i].equals(name)) {
                    primType = i;
                    break;
                }
            }

            if (primType >= 0) {
                // Primitive array: header is [id: idSize][serial: 4][size: 4][type: 1]
                int arrayLen = 0;
                long dataPos = 0;
                if (raf != null && filePos > 0) {
                    raf.seek(filePos + 1 + idSize + 4);
                    arrayLen = raf.readInt();
                    dataPos = filePos + 1 + idSize + 4 + 4 + 1;
                }
                PrimitiveArrayImpl arr = new PrimitiveArrayImpl(objectId, address, cImpl, arrayLen, primType);
                arr.setInfo(dataPos);
                return arr;
            } else {
                // Object array: header is [id: idSize][serial: 4][size: 4][classId: idSize]
                int arrayLen = 0;
                long dataPos = 0;
                if (raf != null && filePos > 0) {
                    raf.seek(filePos + 1 + idSize + 4);
                    arrayLen = raf.readInt();
                    dataPos = filePos + 1 + idSize + 4 + 4 + idSize;
                }
                ObjectArrayImpl arr = new ObjectArrayImpl(objectId, address, cImpl, arrayLen);
                arr.setInfo(dataPos);
                return arr;
            }
        }

        // If no file stream available (or synthetic object), return shallow instance
        if (raf == null || address == 0) {
            ClassImpl cImpl = (ClassImpl) targetClass;
            return snapshot.isClassLoader(objectId)
                    ? new ClassLoaderImpl(objectId, address, cImpl, Collections.emptyList())
                    : new InstanceImpl(objectId, address, cImpl, Collections.emptyList());
        }

        // Class hierarchy in top-down order (Object -> SuperClass -> Class)
        List<IClass> hierarchy = resolveClassHierarchy(snapshot, targetClass);
        Collections.reverse(hierarchy); // Now base classes first, target class last

        List<Field> instanceFields = new ArrayList<>();
        if (raf != null && filePos > 0) {
            // Seek past INSTANCE_DUMP header: [id: idSize][serial: 4][classId: idSize][bytesFollowing: 4]
            // Note: filePos was recorded at segment start tag, so segment tag byte + header
            raf.seek(filePos + 1 + idSize + 4 + idSize + 4);
            for (IClass cls : hierarchy) {
                for (FieldDescriptor fd : cls.getFieldDescriptors()) {
                    int type = fd.getType();
                    Object val = readBinaryValue(type, snapshot);
                    instanceFields.add(new Field(fd.getName(), type, val));
                }
            }
        } else {
            for (IClass cls : hierarchy) {
                for (FieldDescriptor fd : cls.getFieldDescriptors()) {
                    int type = fd.getType();
                    Object val = readDefaultOrMockValue(type, snapshot);
                    instanceFields.add(new Field(fd.getName(), type, val));
                }
            }
        }

        ClassImpl classImpl = (ClassImpl) targetClass;
        if (snapshot.isClassLoader(objectId)) {
            return new ClassLoaderImpl(objectId, address, classImpl, instanceFields);
        } else {
            return new InstanceImpl(objectId, address, classImpl, instanceFields);
        }
    }

    private Object readBinaryValue(int type, ISnapshot snapshot) throws IOException {
        return switch (type) {
            case IObject.Type.OBJECT -> {
                long id = (idSize == 4) ? (raf.readInt() & 0xFFFFFFFFL) : raf.readLong();
                yield id == 0 ? null : new ObjectReference(snapshot, id);
            }
            case IObject.Type.BOOLEAN -> raf.readByte() != 0;
            case IObject.Type.BYTE -> raf.readByte();
            case IObject.Type.CHAR -> raf.readChar();
            case IObject.Type.SHORT -> raf.readShort();
            case IObject.Type.INT -> raf.readInt();
            case IObject.Type.LONG -> raf.readLong();
            case IObject.Type.FLOAT -> raf.readFloat();
            case IObject.Type.DOUBLE -> raf.readDouble();
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
    public synchronized Object readPrimitiveArrayContent(PrimitiveArrayImpl array, int offset, int length)
            throws IOException, SnapshotException {
        int type = array.getType();
        int elementSize = IPrimitiveArray.ELEMENT_SIZE[type];
        Object result = Array.newInstance(IPrimitiveArray.COMPONENT_TYPE[type], length);

        if (raf == null || length == 0) {
            return result;
        }

        Object info = array.getInfo();
        if (info instanceof Long filePos && filePos > 0) {
            long dataOffset = filePos + ((long) offset * elementSize);
            raf.seek(dataOffset);
            byte[] buffer = new byte[length * elementSize];
            raf.readFully(buffer);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buffer).order(java.nio.ByteOrder.BIG_ENDIAN);

            for (int i = 0; i < length; i++) {
                switch (type) {
                    case IObject.Type.BOOLEAN -> Array.set(result, i, bb.get() != 0);
                    case IObject.Type.BYTE -> Array.set(result, i, bb.get());
                    case IObject.Type.CHAR -> Array.set(result, i, bb.getChar());
                    case IObject.Type.SHORT -> Array.set(result, i, bb.getShort());
                    case IObject.Type.INT -> Array.set(result, i, bb.getInt());
                    case IObject.Type.LONG -> Array.set(result, i, bb.getLong());
                    case IObject.Type.FLOAT -> Array.set(result, i, bb.getFloat());
                    case IObject.Type.DOUBLE -> Array.set(result, i, bb.getDouble());
                }
            }
        }
        return result;
    }

    @Override
    public synchronized long[] readObjectArrayContent(ObjectArrayImpl array, int offset, int length)
            throws IOException, SnapshotException {
        long[] result = new long[length];
        if (raf == null || length == 0) {
            return result;
        }

        Object info = array.getInfo();
        if (info instanceof Long filePos && filePos > 0) {
            long dataOffset = filePos + ((long) offset * idSize);
            raf.seek(dataOffset);
            for (int i = 0; i < length; i++) {
                result[i] = (idSize == 4) ? (raf.readInt() & 0xFFFFFFFFL) : raf.readLong();
            }
        }
        return result;
    }

    @Override
    public <A> A getAddon(Class<A> addon) throws SnapshotException {
        return null;
    }

    @Override
    public synchronized void close() throws IOException {
        if (raf != null) {
            raf.close();
            raf = null;
        }
    }
}
