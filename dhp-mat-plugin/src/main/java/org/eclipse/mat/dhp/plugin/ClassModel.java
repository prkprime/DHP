package org.eclipse.mat.dhp.plugin;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.eclipse.mat.parser.model.ClassImpl;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.FieldDescriptor;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.ObjectReference;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The classes of a snapshot as MAT's {@link ClassImpl} objects, built from the {@code dhp_classes} table.
 *
 * <p>MAT enumerates classes (histogram rows, {@code getClassesByName}, subclasses of a class) in the iteration order
 * of its own hash maps, so the map is rebuilt the way MAT builds it ({@link ClassOrder}) to show the same order.</p>
 */
record ClassModel(HashMapIntObject<ClassImpl> classes, int[] linkOrder, int systemLoaderId, int javaLangClassId) {

    /** The class a class-less object (for example an unknown type) is reported as. */
    int defaultClassId() {
        return javaLangClassId >= 0 ? javaLangClassId : (classes.size() > 0 ? classes.keys().next() : 0);
    }

    static ClassModel build(JdbcHeapStorageEngine storage) throws SQLException {
        var classStats = storage.getClassStats();
        java.lang.reflect.Field countField = null;
        java.lang.reflect.Field totalField = null;
        try {
            countField = ClassImpl.class.getDeclaredField("instanceCount");
            countField.setAccessible(true);
            totalField = ClassImpl.class.getDeclaredField("totalSize");
            totalField.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            countField = null; // histogram counts are then derived by MAT itself
        }

        int sysLoader = storage.getObjectIdByAddress(0L);
        HashMapIntObject<ClassImpl> raw = new HashMapIntObject<>();
        int javaLangClassId = -1;

        for (HeapRecords.ClassRecord cls : storage.getAllClasses()) {
            int id = cls.classObjId() >= 0 ? cls.classObjId() : storage.getObjectIdByAddress(cls.classId());
            if (id < 0) {
                continue;
            }
            int superId = cls.superClassObjId() >= 0 ? cls.superClassObjId()
                    : (cls.superClassId() != 0 ? storage.getObjectIdByAddress(cls.superClassId()) : -1);
            int loaderId = cls.classLoaderObjId() >= 0 ? cls.classLoaderObjId()
                    : (cls.classLoaderId() != 0 ? storage.getObjectIdByAddress(cls.classLoaderId()) : sysLoader);
            if (loaderId < 0) {
                loaderId = Math.max(sysLoader, 0);
            }

            FieldDescriptor[] fields = new FieldDescriptor[cls.fields().size()];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = new FieldDescriptor(cls.fields().get(i).name(), cls.fields().get(i).type());
            }
            List<Field> statics = new ArrayList<>(cls.staticFields().size());
            for (var sf : cls.staticFields()) {
                Object value = sf.value();
                if (sf.type() == IObject.Type.OBJECT && value instanceof Number n) {
                    // a null reference static is a plain null in MAT, not a reference to address 0
                    value = n.longValue() == 0 ? null : new ObjectReference(null, n.longValue());
                }
                statics.add(new Field(sf.name(), sf.type(), value));
            }

            ClassImpl c = new ClassImpl(cls.classId(), cls.name(), superId >= 0 ? cls.superClassId() : 0L,
                    cls.classLoaderId(), statics.toArray(new Field[0]), fields);
            c.setObjectId(id);
            c.setCacheEntry(Integer.valueOf(id)); // key of the class -> objects lookup (c2objects)
            c.setSuperClassIndex(superId >= 0 ? superId : -1);
            c.setClassLoaderIndex(loaderId);
            c.setHeapSizePerInstance(cls.instanceSize());
            c.setUsedHeapSize(cls.usedSize() > 0 ? cls.usedSize() : storage.getObjectUsedSize(id));
            var stat = classStats.get(id);
            if (stat != null && countField != null) {
                try {
                    countField.setInt(c, stat.instanceCount());
                    totalField.setLong(c, stat.totalSize());
                } catch (IllegalAccessException ignored) {
                    // counts are optional
                }
            }
            raw.put(id, c);
            if (javaLangClassId < 0 && org.eclipse.mat.snapshot.model.IClass.JAVA_LANG_CLASS.equals(cls.name())) {
                javaLangClassId = id;
            }
        }

        ClassOrder.Result ordered = ClassOrder.rebuild(raw, storage.getClassOrder());
        return new ClassModel(ordered.classes(), ordered.linkOrder(), sysLoader, javaLangClassId);
    }

    /** Sets the type of every class to {@code java.lang.Class} and registers it with its super class. */
    void link() {
        ClassImpl javaLangClass = javaLangClassId >= 0 ? classes.get(javaLangClassId) : null;
        if (javaLangClass != null) {
            for (int key : classes.getAllKeys()) {
                ClassImpl c = classes.get(key);
                if (c.getClazz() == null) {
                    c.setClassInstance(javaLangClass);
                }
            }
        }
        // subclasses are linked in MAT's order (its by-address map), which is what instanceof queries enumerate
        for (int key : linkOrder != null ? linkOrder : classes.getAllKeys()) {
            ClassImpl c = classes.get(key);
            if (c.getSuperClassId() >= 0) {
                ClassImpl superClass = classes.get(c.getSuperClassId());
                if (superClass != null) {
                    superClass.addSubClass(c);
                }
            }
        }
    }
}
