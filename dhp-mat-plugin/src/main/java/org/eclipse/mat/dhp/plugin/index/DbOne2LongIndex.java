package org.eclipse.mat.dhp.plugin.index;

import org.eclipse.mat.parser.index.IIndexReader;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Adapter converting DHP database queries into Eclipse MAT IOne2LongIndex
 * (used for objectId -> address and objectId -> retained size).
 */
public class DbOne2LongIndex implements IIndexReader.IOne2LongIndex {

    @FunctionalInterface
    public interface LongLookup {
        long get(int key) throws SQLException;
    }

    @FunctionalInterface
    public interface ReverseLookup {
        int reverse(long value) throws SQLException;
    }

    private final int size;
    private final LongLookup lookup;
    private final ReverseLookup reverseLookup;

    public DbOne2LongIndex(int size, LongLookup lookup, ReverseLookup reverseLookup) {
        this.size = size;
        this.lookup = lookup;
        this.reverseLookup = reverseLookup;
    }

    @Override
    public long get(int index) {
        try {
            return lookup.get(index);
        } catch (SQLException e) {
            throw new RuntimeException("Error getting long value for id " + index, e);
        }
    }

    @Override
    public int reverse(long value) {
        try {
            return reverseLookup.reverse(value);
        } catch (SQLException e) {
            throw new RuntimeException("Error reverse looking up address " + value, e);
        }
    }

    @Override
    public long[] getNext(int index, int length) {
        long[] result = new long[length];
        for (int i = 0; i < length; i++) {
            result[i] = get(index + i);
        }
        return result;
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
