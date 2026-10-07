package org.eclipse.mat.dhp.plugin.index;

import org.eclipse.mat.parser.index.IIndexReader;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Adapter converting DHP database queries into Eclipse MAT IOne2SizeIndex.
 */
public class DbOne2SizeIndex implements IIndexReader.IOne2SizeIndex {

    @FunctionalInterface
    public interface SizeLookup {
        long getSize(int key) throws SQLException;
    }

    private final int size;
    private final SizeLookup lookup;

    public DbOne2SizeIndex(int size, SizeLookup lookup) {
        this.size = size;
        this.lookup = lookup;
    }

    @Override
    public long getSize(int index) {
        try {
            return lookup.getSize(index);
        } catch (SQLException e) {
            throw new RuntimeException("Error getting size for id " + index, e);
        }
    }

    @Override
    public int get(int index) {
        return (int) getSize(index);
    }

    @Override
    public int[] getAll(int[] index) {
        int[] res = new int[index.length];
        for (int i = 0; i < index.length; i++) {
            res[i] = get(index[i]);
        }
        return res;
    }

    @Override
    public int[] getNext(int index, int length) {
        int[] res = new int[length];
        for (int i = 0; i < length; i++) {
            res[i] = get(index + i);
        }
        return res;
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
