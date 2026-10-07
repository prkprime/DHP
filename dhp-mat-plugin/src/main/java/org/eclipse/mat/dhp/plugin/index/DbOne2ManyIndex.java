package org.eclipse.mat.dhp.plugin.index;

import org.eclipse.mat.parser.index.IIndexReader;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Adapter converting DHP database queries into Eclipse MAT IOne2ManyIndex
 * (used for inbounds, outbounds, and dominated sets).
 */
public class DbOne2ManyIndex implements IIndexReader.IOne2ManyIndex {

    @FunctionalInterface
    public interface ManyLookup {
        int[] get(int key) throws SQLException;
    }

    private final int size;
    private final ManyLookup lookup;

    public DbOne2ManyIndex(int size, ManyLookup lookup) {
        this.size = size;
        this.lookup = lookup;
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
