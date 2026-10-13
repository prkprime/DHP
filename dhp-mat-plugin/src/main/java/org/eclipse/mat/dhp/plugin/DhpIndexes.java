package org.eclipse.mat.dhp.plugin;

import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import org.eclipse.mat.collect.BitField;
import org.eclipse.mat.dhp.plugin.index.DbOne2LongIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2ManyIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2OneIndex;
import org.eclipse.mat.dhp.plugin.index.DbOne2SizeIndex;
import org.eclipse.mat.dhp.storage.jdbc.JdbcHeapStorageEngine;
import org.eclipse.mat.snapshot.model.IClass;

import java.sql.SQLException;

/**
 * MAT index readers backed by the DHP database. Both entry points (the snapshot factory and the index builder) use these,
 * so a lookup behaves the same wherever MAT asks.
 */
final class DhpIndexes {
    private DhpIndexes() {}

    /** object id <-> address. */
    static DbOne2LongIndex identifiers(JdbcHeapStorageEngine storage, int objectCount) {
        return new DbOne2LongIndex(objectCount, storage::getObjectAddress, storage::getObjectIdByAddress);
    }

    /** object id -> class object id; objects whose class is unknown are reported as {@code defaultClassId}. */
    static DbOne2OneIndex objectToClass(JdbcHeapStorageEngine storage, int objectCount, ClassModel model) {
        final int fallback = model.defaultClassId();
        return new DbOne2OneIndex(objectCount, id -> {
            int cid = (int) storage.getObjectClassId(id);
            return cid < 0 || !model.classes().containsKey(cid) ? fallback : cid;
        });
    }

    /**
     * object id -> referenced objects. MAT lists the class of an object first; the stored lists already do, only objects
     * ingested by an older version and the artificial system class loader need the class prepended.
     */
    static DbOne2ManyIndex outbound(JdbcHeapStorageEngine storage, int objectCount, int systemLoaderId) {
        return new DbOne2ManyIndex(objectCount, id -> {
            int classId = (int) storage.getObjectClassId(id);
            int[] refs = storage.getOutboundReferences(id);
            if (id == systemLoaderId) {
                IntLinkedOpenHashSet set = new IntLinkedOpenHashSet();
                if (classId >= 0) {
                    set.add(classId);
                }
                for (int x : refs) {
                    set.add(x);
                }
                return set.toIntArray();
            }
            if (classId >= 0 && (refs.length == 0 || refs[0] != classId)) {
                int[] withClass = new int[refs.length + 1];
                withClass[0] = classId;
                System.arraycopy(refs, 0, withClass, 1, refs.length);
                return withClass;
            }
            return refs;
        });
    }

    /** object id -> referrers; the "key" lookup (a class or class object id) lists the instances of that class. */
    static DbOne2ManyIndex inbound(JdbcHeapStorageEngine storage, int objectCount) {
        return new DbOne2ManyIndex(objectCount, storage::getInboundReferences, key -> {
            if (key instanceof Number num) {
                return storage.getObjectsByClassId(num.intValue());
            }
            if (key instanceof IClass clazz) {
                return storage.getObjectsByClassId(clazz.getObjectId());
            }
            return new int[0];
        });
    }

    /** Which objects are arrays (MAT only keeps sizes for those). */
    static BitField arrayObjects(JdbcHeapStorageEngine storage, int objectCount) throws SQLException {
        BitField arrays = new BitField(objectCount);
        storage.populateArrayBitField(arrays::set);
        return arrays;
    }

    /** array object id -> size in bytes (0 for everything else). */
    static DbOne2SizeIndex arraySizes(JdbcHeapStorageEngine storage, int objectCount, BitField arrayObjects) {
        return new DbOne2SizeIndex(objectCount,
                id -> id >= 0 && id < objectCount && arrayObjects.get(id) ? storage.getObjectUsedSize(id) : 0L);
    }

    /** object id -> immediate dominator (+2: MAT numbers the virtual root 1 and objects from 2). */
    static DbOne2OneIndex dominator(JdbcHeapStorageEngine storage, int objectCount) {
        return new DbOne2OneIndex(objectCount, id -> storage.getDominatorId(id) + 2);
    }

    /** dominator -> dominated objects, in MAT's order (retained size, descending). Index 0 is unused, 1 the virtual root. */
    static DbOne2ManyIndex dominated(JdbcHeapStorageEngine storage, int objectCount) {
        return new DbOne2ManyIndex(objectCount + 1, id -> storage.getImmediateDominatedIds(id - 1));
    }

    /** object id <-> retained size. */
    static DbOne2LongIndex retained(JdbcHeapStorageEngine storage, int objectCount) {
        return new DbOne2LongIndex(objectCount, storage::getRetainedSize, storage::getObjectIdByRetainedSize);
    }
}
