package org.eclipse.mat.dhp.plugin.index;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.parser.index.IIndexReader;

import java.io.IOException;
import java.io.Serializable;
import java.sql.SQLException;

/**
 * Adapter converting DHP database queries into Eclipse MAT IOne2ManyObjectsIndex
 * (used for inbounds, outbounds, dominated sets, and class instance lookups).
 */
public class DbOne2ManyIndex implements IIndexReader.IOne2ManyObjectsIndex {

    @FunctionalInterface
    public interface ManyLookup {
        int[] get(int key) throws SQLException;
    }

    @FunctionalInterface
    public interface ManyObjectLookup {
        int[] getObjectsOf(Serializable key) throws SQLException;
    }

    private final int size;
    private final ManyLookup lookup;
    private final ManyObjectLookup objectsLookup;

    public DbOne2ManyIndex(int size, ManyLookup lookup) {
        this(size, lookup, null);
    }

    public DbOne2ManyIndex(int size, ManyLookup lookup, ManyObjectLookup objectsLookup) {
        this.size = size;
        this.lookup = lookup;
        this.objectsLookup = objectsLookup;
    }

    @Override
    public int[] get(int index) {
        try {
            return lookup.get(index);
        } catch (SQLException e) {
            throw new RuntimeException("Error getting array for id " + index, e);
        }
    }

    @Override
    public int[] getObjectsOf(Serializable key) throws SnapshotException, IOException {
        try {
            if (objectsLookup != null) {
                return objectsLookup.getObjectsOf(key);
            }
            if (key instanceof Number num) {
                return lookup.get(num.intValue());
            }
            return new int[0];
        } catch (SQLException e) {
            throw new SnapshotException("Error retrieving objects for key " + key, e);
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void unload() throws IOException {}

    @Override
    public void close() throws IOException {}

    @Override
    public void delete() {}
}
