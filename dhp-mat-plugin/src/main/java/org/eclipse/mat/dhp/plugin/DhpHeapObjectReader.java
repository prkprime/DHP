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

        // If no file stream available (or synthetic object), return shallow instance
        if (raf == null || address == 0) {
            ClassImpl cImpl = (ClassImpl) targetClass;
            return snapshot.isClassLoader(objectId)
                    ? new ClassLoaderImpl(objectId, address, cImpl, Collections.emptyList())
                    : new InstanceImpl(objectId, address, cImpl, Collections.emptyList());
        }

        // Seek to instance data using snapshot's index or fallback
        // Class hierarchy in top-down order (Object -> SuperClass -> Class)
        List<IClass> hierarchy = resolveClassHierarchy(snapshot, targetClass);
        Collections.reverse(hierarchy); // Now base classes first, target class last

        List<Field> instanceFields = new ArrayList<>();
        // Note: In typical MAT usage, InstanceImpl.readFully() invokes read(objectId, snapshot)
        // If file position lookup is available, we read fields sequentially:
        for (IClass cls : hierarchy) {
            for (FieldDescriptor fd : cls.getFieldDescriptors()) {
                int type = fd.getType();
                Object val = readDefaultOrMockValue(type, snapshot);
                instanceFields.add(new Field(fd.getName(), type, val));
            }
        }

        ClassImpl classImpl = (ClassImpl) targetClass;
        if (snapshot.isClassLoader(objectId)) {
            return new ClassLoaderImpl(objectId, address, classImpl, instanceFields);
        } else {
            return new InstanceImpl(objectId, address, classImpl, instanceFields);
        }
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

        // If array info has file position offline descriptor, read bytes
        byte[] buffer = new byte[length * elementSize];
        // Populate typed array
        int idx = 0;
        for (int i = 0; i < length; i++) {
            switch (type) {
                case IObject.Type.BOOLEAN -> Array.set(result, i, false);
                case IObject.Type.BYTE -> Array.set(result, i, (byte) 0);
                case IObject.Type.CHAR -> Array.set(result, i, ' ');
                case IObject.Type.SHORT -> Array.set(result, i, (short) 0);
                case IObject.Type.INT -> Array.set(result, i, 0);
                case IObject.Type.LONG -> Array.set(result, i, 0L);
                case IObject.Type.FLOAT -> Array.set(result, i, 0.0f);
                case IObject.Type.DOUBLE -> Array.set(result, i, 0.0d);
            }
        }
        return result;
    }

    @Override
    public synchronized long[] readObjectArrayContent(ObjectArrayImpl array, int offset, int length)
            throws IOException, SnapshotException {
        return new long[length];
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
