package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.collect.HashMapLongObject;
import org.eclipse.mat.parser.model.ClassImpl;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the class map the way MAT's parser and garbage cleaner build it, so classes are enumerated in the same order
 * (histogram rows, {@code getClassesByName}, OQL over a class name that exists in several loaders, ...). The order is a
 * property of the open-addressing hash maps: class dump order into the by-address map, then the pre-cleanup ids, then the
 * final ids, and finally the rebuild MAT performs whenever it reads the index back.
 */
final class ClassOrder {
    private ClassOrder() {}

    /** The class map plus the order in which MAT links subclasses to their super class (iteration order of its by-address map). */
    record Result(HashMapIntObject<ClassImpl> classes, int[] linkOrder) {}

    /** @param order rows of {dump sequence, pre-cleanup id, address, final id or -1 if removed}; empty if the database predates them */
    @SuppressWarnings("unchecked")
    static Result rebuild(HashMapIntObject<ClassImpl> classes, List<long[]> order) {
        if (order.isEmpty()) {
            return new Result(classes, null);
        }
        try {
            HashMapLongObject<long[]> byAddress = new HashMapLongObject<>();
            for (long[] row : order) { // ordered by dump sequence
                byAddress.put(row[2], row);
            }
            HashMapIntObject<long[]> byOldId = new HashMapIntObject<>(byAddress.size());
            for (Iterator<long[]> it = byAddress.values(); it.hasNext();) {
                long[] row = it.next();
                byOldId.put((int) row[1], row);
            }
            int[] linkOrder = new int[classes.size()];
            int li = 0;
            for (Iterator<long[]> it = byAddress.values(); it.hasNext();) {
                long[] row = it.next();
                if (row[3] >= 0 && li < linkOrder.length) {
                    linkOrder[li++] = (int) row[3];
                }
            }
            // classes of unreachable objects are dropped before the ids are renumbered
            for (long[] row : order) {
                if (row[3] < 0) {
                    byOldId.remove((int) row[1]);
                }
            }
            HashMapIntObject<ClassImpl> byNewId = new HashMapIntObject<>(byOldId.size());
            for (Iterator<long[]> it = byOldId.values(); it.hasNext();) {
                long[] row = it.next();
                ClassImpl c = classes.get((int) row[3]);
                if (c == null) {
                    return new Result(classes, null);
                }
                byNewId.put((int) row[3], c);
            }
            if (byNewId.size() != classes.size()) {
                return new Result(classes, null);
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bos)) {
                out.writeObject(byNewId);
            }
            try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bos.toByteArray()))) {
                return new Result((HashMapIntObject<ClassImpl>) in.readObject(), li == linkOrder.length ? linkOrder : null);
            }
        } catch (java.io.IOException | ClassNotFoundException | RuntimeException e) {
            return new Result(classes, null);
        }
    }
}
