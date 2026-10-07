package org.eclipse.mat.dhp.plugin.index;

import org.eclipse.mat.parser.index.IIndexReader;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Adapter converting DHP database queries into Eclipse MAT IOne2OneIndex.
 */
public class DbOne2OneIndex implements IIndexReader.IOne2OneIndex {

    @FunctionalInterface
    public interface IntIntLookup {
        int get(int key) throws SQLException;
    }

    private final IntIntLookup lookup;
    private final int size;

    public DbOne2OneIndex(int size, IntIntLookup lookup) {
        this.size = size;
        this.lookup = lookup;
    }

    @Override
    public int get(int index) {
        try {
            return lookup.get(index);
        } catch (SQLException e) {
            throw new RuntimeException("Error querying 1-to-1 index for id " + index, e);
        }
    }

    @Override
    public int[] getAll(int[] index) {
        int[] result = new int[index.length];
        for (int i = 0; i < index.length; i++) {
            result[i] = get(index[i]);
        }
        return result;
    }

    @Override
    public int[] getNext(int index, int length) {
        int[] result = new int[length];
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
