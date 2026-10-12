package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.collect.HashMapLongObject;
import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.model.HprofConstants;
import org.eclipse.mat.parser.model.XGCRootInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Builds the GC root structures the way MAT's HPROF parser and garbage cleaner do (HprofParserHandlerImpl.addGCRoot /
 * fillIn / map2ids and GarbageCleaner.fix), with MAT's own hash maps. The iteration order of those maps is visible in
 * MAT (GC roots list, the {@code <Java Local>} references of a thread), and it depends on the insertion order, the
 * addresses and the object IDs before and after removing unreachable objects, so the same sequence of operations has to
 * be replayed.
 */
final class RootMaps {
    final HashMapIntObject<XGCRootInfo[]> roots;
    final HashMapIntObject<HashMapIntObject<XGCRootInfo[]>> rootsPerThread;

    private RootMaps(HashMapIntObject<XGCRootInfo[]> roots, HashMapIntObject<HashMapIntObject<XGCRootInfo[]>> rootsPerThread) {
        this.roots = roots;
        this.rootsPerThread = rootsPerThread;
    }

    /** @param records GC roots in the order the dump reported them */
    static RootMaps build(List<HeapRecords.GcRootRecord> records) {
        // address -> id before / after removing unreachable objects (identical when nothing was removed)
        Map<Long, Integer> oldId = new HashMap<>();
        Map<Integer, Integer> oldToNew = new HashMap<>();
        HashMapLongObject<List<XGCRootInfo>> gcRoots = new HashMapLongObject<>(200);
        HashMapLongObject<HashMapLongObject<List<XGCRootInfo>>> threadAddressToLocals = new HashMapLongObject<>();
        List<HeapRecords.GcRootRecord> unreachable = new ArrayList<>();

        for (HeapRecords.GcRootRecord r : records) {
            if (r.rootType() == HprofConstants.ROOT_UNREACHABLE) {
                unreachable.add(r);
                continue;
            }
            int o = r.oldObjectId() >= 0 ? r.oldObjectId() : r.objectId();
            oldId.putIfAbsent(r.objectAddress(), o);
            if (o >= 0 && r.objectId() >= 0) oldToNew.put(o, r.objectId());
            if (r.threadAddress() != 0) {
                int t = r.oldThreadObjectId() >= 0 ? r.oldThreadObjectId() : r.threadObjectId();
                oldId.putIfAbsent(r.threadAddress(), t);
                if (t >= 0 && r.threadObjectId() >= 0) oldToNew.put(t, r.threadObjectId());
            }
            int type = RootTypes.toMat(r.rootType());
            if (r.threadAddress() != 0) {
                HashMapLongObject<List<XGCRootInfo>> locals = threadAddressToLocals.get(r.threadAddress());
                if (locals == null) {
                    locals = new HashMapLongObject<>();
                    threadAddressToLocals.put(r.threadAddress(), locals);
                }
                List<XGCRootInfo> list = locals.get(r.objectAddress());
                if (list == null) {
                    list = new ArrayList<>(1);
                    locals.put(r.objectAddress(), list);
                }
                list.add(new XGCRootInfo(r.objectAddress(), r.threadAddress(), type));
            } else {
                List<XGCRootInfo> list = gcRoots.get(r.objectAddress());
                if (list == null) {
                    list = new ArrayList<>(3);
                    gcRoots.put(r.objectAddress(), list);
                }
                list.add(new XGCRootInfo(r.objectAddress(), 0, type));
            }
        }

        // fillIn(): map addresses to the IDs of the first pass
        HashMapIntObject<List<XGCRootInfo>> rootsOld = map2ids(gcRoots, oldId);
        // markUnreachableAsGCRoots(): the artificial roots are put into the same map afterwards
        for (HeapRecords.GcRootRecord r : unreachable) {
            XGCRootInfo x = new XGCRootInfo(r.objectAddress(), 0, RootTypes.toMat(r.rootType()));
            x.setObjectId(r.objectId());
            rootsOld.put(r.objectId(), List.of(x));
            oldToNew.put(r.objectId(), r.objectId());
        }
        HashMapIntObject<HashMapIntObject<List<XGCRootInfo>>> thread2objects2roots = new HashMapIntObject<>();
        for (Iterator<HashMapLongObject.Entry<HashMapLongObject<List<XGCRootInfo>>>> it = threadAddressToLocals.entries(); it.hasNext();) {
            HashMapLongObject.Entry<HashMapLongObject<List<XGCRootInfo>>> e = it.next();
            Integer threadId = oldId.get(e.getKey());
            if (threadId != null && threadId >= 0) {
                HashMapIntObject<List<XGCRootInfo>> objects2roots = map2ids(e.getValue(), oldId);
                if (!objects2roots.isEmpty()) thread2objects2roots.put(threadId, objects2roots);
            }
        }

        // GarbageCleaner: renumber with the new IDs, in the iteration order of the old maps
        HashMapIntObject<XGCRootInfo[]> roots = fix(rootsOld, oldToNew);
        HashMapIntObject<HashMapIntObject<XGCRootInfo[]>> rootsPerThread = new HashMapIntObject<>();
        for (org.eclipse.mat.collect.IteratorInt it = thread2objects2roots.keys(); it.hasNext();) {
            int threadId = it.next();
            Integer fixed = oldToNew.get(threadId);
            if (fixed == null || fixed < 0) continue;
            rootsPerThread.put(fixed, fix(thread2objects2roots.get(threadId), oldToNew));
        }
        return roundTrip(new RootMaps(roots, rootsPerThread));
    }

    /**
     * MAT writes these maps into its index file and reads them back whenever it opens a snapshot that was parsed before; its
     * collections rebuild themselves while deserializing, which changes their iteration order. That is the order users see
     * (the GC roots list, the {@code <Java Local>} references of a thread), so apply the same round trip.
     */
    @SuppressWarnings("unchecked")
    private static RootMaps roundTrip(RootMaps in) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bos)) {
                out.writeObject(in.roots);
                out.writeObject(in.rootsPerThread);
            }
            try (java.io.ObjectInputStream inp = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bos.toByteArray()))) {
                return new RootMaps((HashMapIntObject<XGCRootInfo[]>) inp.readObject(),
                        (HashMapIntObject<HashMapIntObject<XGCRootInfo[]>>) inp.readObject());
            }
        } catch (java.io.IOException | ClassNotFoundException e) {
            return in;
        }
    }

    private static HashMapIntObject<List<XGCRootInfo>> map2ids(HashMapLongObject<List<XGCRootInfo>> source, Map<Long, Integer> oldId) {
        HashMapIntObject<List<XGCRootInfo>> sink = new HashMapIntObject<>();
        for (Iterator<HashMapLongObject.Entry<List<XGCRootInfo>>> it = source.entries(); it.hasNext();) {
            HashMapLongObject.Entry<List<XGCRootInfo>> entry = it.next();
            Integer idx = oldId.get(entry.getKey());
            if (idx != null && idx >= 0) {
                for (Iterator<XGCRootInfo> roots = entry.getValue().iterator(); roots.hasNext();) {
                    XGCRootInfo root = roots.next();
                    root.setObjectId(idx);
                    if (root.getContextAddress() != 0) {
                        Integer contextId = oldId.get(root.getContextAddress());
                        if (contextId == null || contextId < 0) roots.remove();
                        else root.setContextId(contextId);
                    }
                }
                sink.put(idx, entry.getValue());
            }
        }
        return sink;
    }

    private static HashMapIntObject<XGCRootInfo[]> fix(HashMapIntObject<List<XGCRootInfo>> roots, Map<Integer, Integer> map) {
        HashMapIntObject<XGCRootInfo[]> answer = new HashMapIntObject<>(roots.size());
        for (Iterator<List<XGCRootInfo>> it = roots.values(); it.hasNext();) {
            List<XGCRootInfo> r = it.next();
            if (r.isEmpty()) continue;
            XGCRootInfo[] a = new XGCRootInfo[r.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = r.get(i);
                Integer newObj = map.get(a[i].getObjectId());
                a[i].setObjectId(newObj != null ? newObj : -1);
                if (a[i].getContextAddress() != 0) {
                    Integer newCtx = map.get(a[i].getContextId());
                    a[i].setContextId(newCtx != null ? newCtx : -1);
                }
            }
            answer.put(a[0].getObjectId(), a);
        }
        return answer;
    }
}
