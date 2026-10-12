package org.eclipse.mat.dhp.core.parser;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.eclipse.mat.dhp.core.graph.HeapGraph;
import org.eclipse.mat.dhp.core.memory.IAddressToIdMap;
import org.eclipse.mat.dhp.core.memory.IObjectMembershipSet;
import org.eclipse.mat.dhp.core.memory.MemoryGovernor;
import org.eclipse.mat.dhp.core.memory.SortedAddressToIdMap;
import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.model.HprofConstants;
import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pass 2 stream parser.
 *
 * <p>Streams object instances and arrays from the HPROF file into an in-memory {@link HeapGraph} (primitive columns
 * plus a compact edge log) and then hands the finished graph to the storage engine in a few bulk writes. Nothing is
 * written to the database per object, which removes the per-row JDBC cost that used to dominate ingestion.</p>
 */
public class Pass2ObjectIngester implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Pass2ObjectIngester.class);
    private static final VarHandle INT_BE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    private static final int ARRAY_BLOCK = 8192;

    private final Pass1ScanParser pass1;
    private final HeapStorageEngine storage;
    private final MemoryGovernor governor;

    private IAddressToIdMap addressToId;
    private HeapGraph graph;

    /** Per-class precomputed field layout. */
    private static final class ClassLayout {
        final HeapRecords.ClassRecord record;          // null if the class was never described
        final List<HeapRecords.ClassRecord> hierarchy; // class first, then its super classes
        final int assignedClassObjId;
        final int[] refOffsets;                        // byte offsets of OBJECT fields (complete instance data)
        final int totalFieldBytes;

        ClassLayout(HeapRecords.ClassRecord record, List<HeapRecords.ClassRecord> hierarchy,
                    int assignedClassObjId, int[] refOffsets, int totalFieldBytes) {
            this.record = record;
            this.hierarchy = hierarchy;
            this.assignedClassObjId = assignedClassObjId;
            this.refOffsets = refOffsets;
            this.totalFieldBytes = totalFieldBytes;
        }
    }

    public Pass2ObjectIngester(Pass1ScanParser pass1, HeapStorageEngine storage, MemoryGovernor governor) {
        this.pass1 = pass1;
        this.storage = storage;
        this.governor = governor;
        this.addressToId = new SortedAddressToIdMap(new Long2IntOpenHashMap(), new long[0], 0);
    }

    public HeapGraph ingest(File file) throws IOException, SQLException {
        log.info("Starting Pass 2 Object Ingestion...");
        long tStart = System.currentTimeMillis();

        // Save metadata & classes from Pass 1
        storage.saveSnapshotInfo("version", pass1.getHeader().version());
        storage.saveSnapshotInfo("idSize", String.valueOf(pass1.getHeader().idSize()));
        storage.saveSnapshotInfo("creationTime", String.valueOf(pass1.getHeader().creationTime()));
        List<HeapRecords.GcRootRecord> allRoots = new ArrayList<>(pass1.getGcRoots());
        // MAT fallback: when the dump marks no sticky (system) classes at all, classes of the system loader are roots
        boolean foundSystemClasses = false;
        java.util.Set<Long> rootedAddresses = new java.util.HashSet<>();
        for (var r : allRoots) {
            rootedAddresses.add(r.objectAddress());
            if (r.rootType() == HprofConstants.DumpSegment.ROOT_STICKY_CLASS) {
                foundSystemClasses = true;
            }
        }
        if (!foundSystemClasses) {
            for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
                if (cls.classLoaderId() == 0L && !cls.name().endsWith("[]") && cls.classId() > 0
                        && !rootedAddresses.contains(cls.classId())) {
                    allRoots.add(new HeapRecords.GcRootRecord(cls.classId(), 0L, HprofConstants.DumpSegment.ROOT_STICKY_CLASS, 0L));
                }
            }
        }

        int idSize = pass1.getHeader().idSize();
        final int refSize = pass1.getRefSize();       // 4 with compressed oops, like MAT detects
        final int objAlign = pass1.getObjectAlign();  // GCD of address gaps, at least 8

        // Object IDs follow MAT: position in the sorted array of all object addresses (address 0 = artificial system
        // class loader first, class objects interleaved with instances). Pass 1 delivers that array.
        long javaLangClassAddress = 0L;
        long classLoaderClassAddress = 0L;
        long classLoaderInstanceSize = 0;
        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            if ("java.lang.Class".equals(cls.name())) {
                javaLangClassAddress = cls.classId();
            }
            if ("java.lang.ClassLoader".equals(cls.name())) {
                classLoaderClassAddress = cls.classId();
                classLoaderInstanceSize = cls.instanceSize();
            }
        }
        if (classLoaderInstanceSize == 0) classLoaderInstanceSize = 4L * idSize;

        long[] allIdentifiers = pass1.getSortedInstanceAddresses();
        int identifierCount = pass1.getUniqueInstanceCount();
        File tempDir = file.getAbsoluteFile().getParentFile();

        this.addressToId = governor.createAddressMap(
                new Long2IntOpenHashMap(),
                allIdentifiers,
                identifierCount,
                0,
                tempDir
        );
        pass1.releaseSortedInstanceAddresses();

        int totalExpectedObjects = addressToId.size();
        final int systemClassLoaderObjId = addressToId.get(0L);
        final java.util.BitSet isClass = new java.util.BitSet(totalExpectedObjects);
        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            int cid = addressToId.get(cls.classId());
            if (cid >= 0) {
                isClass.set(cid);
            }
        }

        int javaLangClassObjId = javaLangClassAddress != 0 ? addressToId.get(javaLangClassAddress) : -1;
        int classLoaderClassObjId = (classLoaderClassAddress != 0 && addressToId.get(classLoaderClassAddress) >= 0)
                ? addressToId.get(classLoaderClassAddress)
                : (javaLangClassObjId != -1 ? javaLangClassObjId : 0);

        IObjectMembershipSet writtenObjects = governor.createMembershipSet(totalExpectedObjects);
        IObjectMembershipSet processedClassDumps = governor.createMembershipSet(totalExpectedObjects);

        // Resolve GC roots now that the address map exists (no per-root database lookups later)
        List<HeapRecords.GcRootRecord> resolvedRoots = new ArrayList<>(allRoots.size());
        for (var r : allRoots) {
            resolvedRoots.add(new HeapRecords.GcRootRecord(
                    addressToId.get(r.objectAddress()),
                    r.objectAddress(),
                    r.referrerAddress(),
                    r.rootType(),
                    r.threadAddress(),
                    addressToId.get(r.threadAddress()),
                    addressToId.get(r.objectAddress()),
                    addressToId.get(r.threadAddress())));
        }

        // Stack-local roots of known threads are not roots: the thread object references them (MAT semantics)
        it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList> threadLocals = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
        for (var r : resolvedRoots) {
            if (r.threadAddress() != 0 && r.threadObjectId() >= 0 && r.objectId() >= 0) {
                var list = threadLocals.computeIfAbsent(r.threadObjectId(), k -> new it.unimi.dsi.fastutil.ints.IntArrayList());
                if (!list.contains(r.objectId())) {
                    list.add(r.objectId());
                }
            }
        }

        HeapGraph graph = new HeapGraph(totalExpectedObjects, 0, addressToId);
        graph.setGcRoots(resolvedRoots);
        this.graph = graph;

        // Class statistics keyed by class object ID
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap classCounts = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap classTotalSizes = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();

        int[] edgeScratch = new int[1024];

        writtenObjects.add(systemClassLoaderObjId);
        if (classLoaderClassObjId >= 0) {
            classCounts.addTo(classLoaderClassObjId, 1);
            classTotalSizes.addTo(classLoaderClassObjId, classLoaderInstanceSize);
        }

        graph.setObject(systemClassLoaderObjId, classLoaderClassObjId, classLoaderInstanceSize, 0L, false);
        {
            int sysLen = 0;
            int needed = 1 + pass1.getClasses().size();
            if (edgeScratch.length < needed) edgeScratch = new int[needed];
            if (classLoaderClassObjId >= 0) {
                edgeScratch[sysLen++] = classLoaderClassObjId;
            }
            // Like MAT, the artificial system class loader only references the ClassLoader class; classes point
            // at it, not the other way round.
            graph.setEdges(systemClassLoaderObjId, edgeScratch, sysLen);
        }

        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            int classObjId = addressToId.get(cls.classId());
            int classTypeObjId = javaLangClassObjId != -1 ? javaLangClassObjId : classObjId;
            long staticFieldsSize = 0;
            for (var sf : cls.staticFields()) {
                staticFieldsSize += (sf.type() == HprofConstants.Type.OBJECT) ? refSize : HprofConstants.Type.sizeOf(sf.type(), idSize);
            }
            long size = alignUpToX(staticFieldsSize, objAlign); // MAT default: statics only (no java.lang.Class instance size)
            writtenObjects.add(classObjId);
            classCounts.addTo(classTypeObjId, 1);
            classTotalSizes.addTo(classTypeObjId, size);
            graph.setObject(classObjId, classTypeObjId, size, 0L, false);
        }

        log.info("Pass 2 Pre-registration complete: classes={}, instances={}, totalExpectedObjects={}",
                pass1.getClasses().size(), identifierCount, totalExpectedObjects);

        // Primitive array class lookups, resolved once per element type
        int[] primClassObjIds = new int[16];
        Arrays.fill(primClassObjIds, Integer.MIN_VALUE);

        Long2ObjectOpenHashMap<ClassLayout> layouts = new Long2ObjectOpenHashMap<>();
        long lastClassAddr = Long.MIN_VALUE;
        ClassLayout lastLayout = null;

        byte[] bytesScratch = new byte[4096];

        try (HprofBinaryReader reader = new HprofBinaryReader(file)) {
            reader.readHeader(); // skip header

            while (true) {
                int tag = reader.readByte();
                if (tag == -1) break;

                long timeStamp = reader.readUnsignedInt();
                long length = reader.readUnsignedInt();

                if (tag == HprofConstants.Record.HEAP_DUMP || tag == HprofConstants.Record.HEAP_DUMP_SEGMENT) {
                    long segmentEnd = reader.getPosition() + length;

                    while (reader.getPosition() < segmentEnd) {
                        int subTag = reader.readByte();
                        if (subTag == -1) break;

                        switch (subTag) {
                            case HprofConstants.DumpSegment.ROOT_UNKNOWN,
                                 HprofConstants.DumpSegment.ROOT_STICKY_CLASS,
                                 HprofConstants.DumpSegment.ROOT_MONITOR_USED -> reader.skipBytes(idSize);

                            case HprofConstants.DumpSegment.ROOT_JNI_GLOBAL -> reader.skipBytes(idSize * 2L);

                            case HprofConstants.DumpSegment.ROOT_NATIVE_STACK,
                                 HprofConstants.DumpSegment.ROOT_THREAD_BLOCK -> reader.skipBytes(idSize + 4L);

                            case HprofConstants.DumpSegment.ROOT_THREAD_OBJECT,
                                 HprofConstants.DumpSegment.ROOT_JNI_LOCAL,
                                 HprofConstants.DumpSegment.ROOT_JAVA_FRAME -> reader.skipBytes(idSize + 8L);

                            case HprofConstants.DumpSegment.CLASS_DUMP -> {
                                long classId = reader.readId();
                                reader.skipBytes(4); // stack trace serial
                                long superClassId = reader.readId();
                                long classLoaderId = reader.readId();
                                reader.skipBytes(idSize * 4L); // signers, pd, res1, res2
                                int instanceSize = reader.readInt();

                                int cpCount = reader.readUnsignedShort();
                                for (int i = 0; i < cpCount; i++) {
                                    reader.skipBytes(2); // cp index
                                    int t = reader.readByte();
                                    reader.skipBytes(HprofConstants.Type.sizeOf(t, idSize));
                                }

                                int sfCount = reader.readUnsignedShort();
                                int classObjId = addressToId.get(classId);
                                boolean isFirstClassDump = (classObjId >= 0 && !processedClassDumps.contains(classObjId));
                                if (isFirstClassDump) {
                                    processedClassDumps.add(classObjId);
                                }
                                if (edgeScratch.length < sfCount + 3) {
                                    edgeScratch = new int[sfCount + 3];
                                }
                                int len = 0;
                                if (classObjId != -1 && isFirstClassDump) {
                                    if (javaLangClassObjId != -1) {
                                        edgeScratch[len++] = javaLangClassObjId;
                                    }
                                    if (superClassId != 0) {
                                        int superId = addressToId.get(superClassId);
                                        if (superId >= 0) {
                                            edgeScratch[len++] = superId;
                                        }
                                    }
                                    int clId = (classLoaderId != 0) ? addressToId.get(classLoaderId) : -1;
                                    if (clId < 0) {
                                        clId = systemClassLoaderObjId;
                                    }
                                    edgeScratch[len++] = clId;
                                }
                                for (int i = 0; i < sfCount; i++) {
                                    reader.skipBytes(idSize); // fieldNameId
                                    int t = reader.readByte();
                                    if (t == HprofConstants.Type.OBJECT) {
                                        long refAddr = reader.readId();
                                        if (refAddr != 0 && classObjId != -1 && isFirstClassDump) {
                                            int targetId = addressToId.get(refAddr);
                                            if (targetId >= 0) {
                                                edgeScratch[len++] = targetId;
                                            }
                                        }
                                    } else {
                                        reader.skipBytes(HprofConstants.Type.sizeOf(t, idSize));
                                    }
                                }
                                if (classObjId != -1 && isFirstClassDump) {
                                    graph.setEdges(classObjId, edgeScratch, len);
                                }

                                int ifCount = reader.readUnsignedShort();
                                reader.skipBytes((long) ifCount * (idSize + 1));
                            }

                            case HprofConstants.DumpSegment.INSTANCE_DUMP -> {
                                long objPos = reader.getPosition() - 1;
                                long objAddr = reader.readId();
                                reader.skipBytes(4); // stack trace
                                long classAddr = reader.readId();
                                int bytesFollow = reader.readInt();

                                ClassLayout layout;
                                if (classAddr == lastClassAddr) {
                                    layout = lastLayout;
                                } else {
                                    layout = layouts.get(classAddr);
                                    if (layout == null) {
                                        layout = buildLayout(classAddr, idSize, javaLangClassObjId, isClass);
                                        layouts.put(classAddr, layout);
                                    }
                                    lastClassAddr = classAddr;
                                    lastLayout = layout;
                                }

                                int objId = addressToId.get(objAddr);
                                boolean skip = objId == -1 || writtenObjects.contains(objId);
                                boolean needBytes = !skip && layout.refOffsets.length > 0;
                                byte[] instanceBytes = null;
                                if (needBytes) {
                                    if (bytesScratch.length < bytesFollow) {
                                        bytesScratch = new byte[Math.max(bytesFollow, bytesScratch.length * 2)];
                                    }
                                    reader.readFully(bytesScratch, bytesFollow);
                                    instanceBytes = bytesScratch;
                                } else {
                                    reader.skipBytes(bytesFollow);
                                }
                                if (skip) {
                                    continue;
                                }

                                writtenObjects.add(objId);
                                int assignedClassId = layout.assignedClassObjId;
                                HeapRecords.ClassRecord clsRecord = layout.record;
                                long usedSize = (clsRecord != null && clsRecord.instanceSize() > 0)
                                        ? clsRecord.instanceSize()
                                        : alignUpToX(bytesFollow + (long) idSize + refSize, objAlign);
                                classCounts.addTo(assignedClassId, 1);
                                classTotalSizes.addTo(assignedClassId, usedSize);
                                graph.setObject(objId, assignedClassId, usedSize, objPos, false);

                                int refCapacity = 1 + layout.refOffsets.length;
                                if (edgeScratch.length < refCapacity) {
                                    edgeScratch = new int[Math.max(refCapacity, edgeScratch.length * 2)];
                                }
                                int len = 0;
                                if (assignedClassId >= 0) {
                                    edgeScratch[len++] = assignedClassId;
                                }

                                if (instanceBytes != null) {
                                    if (layout.totalFieldBytes <= bytesFollow) {
                                        // Fast path: complete instance data, precomputed reference offsets
                                        int[] offsets = layout.refOffsets;
                                        for (int k = 0; k < offsets.length; k++) {
                                            long refAddr = idSize == 8
                                                    ? (long) LONG_BE.get(instanceBytes, offsets[k])
                                                    : ((int) INT_BE.get(instanceBytes, offsets[k])) & 0xFFFFFFFFL;
                                            if (refAddr != 0L) {
                                                int targetId = addressToId.get(refAddr);
                                                if (targetId >= 0) {
                                                    edgeScratch[len++] = targetId;
                                                }
                                            }
                                        }
                                    } else {
                                        // Truncated instance data: replicate the exact field walk of the original parser
                                        int offset = 0;
                                        for (HeapRecords.ClassRecord cls : layout.hierarchy) {
                                            for (HeapRecords.FieldDescriptor field : cls.fields()) {
                                                int fieldType = field.type();
                                                int fieldSize = HprofConstants.Type.sizeOf(fieldType, idSize);
                                                if (offset + fieldSize > bytesFollow) {
                                                    break;
                                                }
                                                if (fieldType == HprofConstants.Type.OBJECT) {
                                                    long refAddr = readIdFromBytes(instanceBytes, offset, idSize);
                                                    if (refAddr != 0L) {
                                                        int targetId = addressToId.get(refAddr);
                                                        if (targetId >= 0) {
                                                            edgeScratch[len++] = targetId;
                                                        }
                                                    }
                                                }
                                                offset += fieldSize;
                                            }
                                        }
                                    }
                                }
                                var locals = threadLocals.get(objId);
                                if (locals != null) {
                                    if (edgeScratch.length < len + locals.size()) {
                                        edgeScratch = Arrays.copyOf(edgeScratch, len + locals.size());
                                    }
                                    for (int k = 0; k < locals.size(); k++) {
                                        edgeScratch[len++] = locals.getInt(k);
                                    }
                                }
                                graph.setEdges(objId, edgeScratch, len);
                            }

                            case HprofConstants.DumpSegment.OBJECT_ARRAY_DUMP -> {
                                long objPos = reader.getPosition() - 1;
                                long objAddr = reader.readId();
                                reader.skipBytes(4); // stack trace
                                int arrayLength = reader.readInt();
                                long elementClassAddr = reader.readId();

                                int objId = addressToId.get(objAddr);
                                if (objId == -1 || writtenObjects.contains(objId)) {
                                    reader.skipBytes((long) arrayLength * idSize);
                                    continue;
                                }

                                writtenObjects.add(objId);
                                int ec = addressToId.get(elementClassAddr);
                                int assignedClassId = (ec >= 0 && isClass.get(ec)) ? ec : 0;

                                long usedSize = alignUpToX((long) idSize + refSize + 4 + (long) arrayLength * refSize, objAlign);
                                classCounts.addTo(assignedClassId, 1);
                                classTotalSizes.addTo(assignedClassId, usedSize);
                                graph.setObject(objId, assignedClassId, usedSize, objPos, true);

                                int len = 0;
                                if (assignedClassId >= 0) {
                                    edgeScratch[len++] = assignedClassId;
                                }

                                // Read references in blocks to bound the transient byte buffer; the edge scratch grows
                                // with the references actually found, not with the declared length (sparse arrays).
                                int remaining = arrayLength;
                                while (remaining > 0) {
                                    int block = Math.min(remaining, ARRAY_BLOCK);
                                    if (edgeScratch.length < len + block + 1) {
                                        edgeScratch = Arrays.copyOf(edgeScratch, Math.max(len + block + 1, edgeScratch.length * 2));
                                    }
                                    int blockBytes = block * idSize;
                                    if (bytesScratch.length < blockBytes) {
                                        bytesScratch = new byte[Math.max(blockBytes, bytesScratch.length * 2)];
                                    }
                                    reader.readFully(bytesScratch, blockBytes);
                                    if (idSize == 8) {
                                        for (int i = 0, p = 0; i < block; i++, p += 8) {
                                            long refAddr = (long) LONG_BE.get(bytesScratch, p);
                                            if (refAddr != 0) {
                                                int targetId = addressToId.get(refAddr);
                                                if (targetId >= 0) {
                                                    edgeScratch[len++] = targetId;
                                                }
                                            }
                                        }
                                    } else {
                                        for (int i = 0, p = 0; i < block; i++, p += 4) {
                                            long refAddr = ((int) INT_BE.get(bytesScratch, p)) & 0xFFFFFFFFL;
                                            if (refAddr != 0) {
                                                int targetId = addressToId.get(refAddr);
                                                if (targetId >= 0) {
                                                    edgeScratch[len++] = targetId;
                                                }
                                            }
                                        }
                                    }
                                    remaining -= block;
                                }
                                graph.setEdges(objId, edgeScratch, len);
                                if (edgeScratch.length > (1 << 20)) {
                                    edgeScratch = new int[1024];
                                }
                            }

                            case HprofConstants.DumpSegment.PRIMITIVE_ARRAY_DUMP -> {
                                long objPos = reader.getPosition() - 1;
                                long objAddr = reader.readId();
                                reader.skipBytes(4); // stack trace
                                int arrayLength = reader.readInt();
                                int elementType = reader.readByte();
                                int elementSize = HprofConstants.Type.sizeOf(elementType, idSize);
                                reader.skipBytes((long) arrayLength * elementSize);

                                int objId = addressToId.get(objAddr);
                                if (objId == -1 || writtenObjects.contains(objId)) {
                                    continue;
                                }

                                writtenObjects.add(objId);
                                int assignedClassId;
                                if (elementType >= 0 && elementType < primClassObjIds.length) {
                                    assignedClassId = primClassObjIds[elementType];
                                    if (assignedClassId == Integer.MIN_VALUE) {
                                        int pid = addressToId.get(pass1.getPrimitiveArrayClassId(elementType));
                                        assignedClassId = (pid >= 0 && isClass.get(pid)) ? pid : 0;
                                        primClassObjIds[elementType] = assignedClassId;
                                    }
                                } else {
                                    int pid = addressToId.get(pass1.getPrimitiveArrayClassId(elementType));
                                    assignedClassId = (pid >= 0 && isClass.get(pid)) ? pid : 0;
                                }

                                long usedSize = alignUpToX(alignUpToX((long) idSize + refSize + 4, refSize) + (long) arrayLength * elementSize, objAlign);
                                classCounts.addTo(assignedClassId, 1);
                                classTotalSizes.addTo(assignedClassId, usedSize);
                                graph.setObject(objId, assignedClassId, usedSize, objPos, true);

                                edgeScratch[0] = assignedClassId;
                                graph.setEdges(objId, edgeScratch, 1);
                            }

                            default -> throw new IOException("Unknown subtag: " + subTag);
                        }
                    }
                } else {
                    reader.skipBytes(length);
                }
            }
        }
        long tParsed = System.currentTimeMillis();
        log.info("Pass 2 parse complete in {} ms: {} objects, {} edges", tParsed - tStart,
                writtenObjects.size(), graph.edgeCount());

        // Old object ID -> class record, for statistics and the unreachable-objects histogram
        it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<HeapRecords.ClassRecord> classByOldId = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
        for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
            int oid = addressToId.get(cls.classId());
            if (oid >= 0) {
                classByOldId.put(oid, cls);
            }
        }

        // MAT's default: objects that cannot be reached from a GC root are removed and the remaining IDs are
        // renumbered (-Ddhp.keep.unreachable=true keeps them instead, as MAT does with keep_unreachable_objects).
        StringBuilder unreachableHistogram = new StringBuilder();
        if (!Boolean.getBoolean("dhp.keep.unreachable")) {
            int[] rootIds = rootIdsOf(resolvedRoots, totalExpectedObjects);
            java.util.BitSet reachable = new java.util.BitSet(totalExpectedObjects);
            int reachableCount = org.eclipse.mat.dhp.core.graph.UnreachableRoots.mark(graph, totalExpectedObjects, reachable, rootIds, rootIds.length);
            if (reachableCount < totalExpectedObjects) {
                it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap removedCount = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
                it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap removedSize = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
                for (int old = reachable.nextClearBit(0); old >= 0 && old < totalExpectedObjects; old = reachable.nextClearBit(old + 1)) {
                    int cid = graph.classObjId(old);
                    long sz = graph.usedSize(old);
                    classCounts.addTo(cid, -1);
                    classTotalSizes.addTo(cid, -sz);
                    removedCount.addTo(cid, 1);
                    removedSize.addTo(cid, sz);
                }
                // MAT lists the records in the iteration order of a java.util.HashMap<Integer, ...> sized for all objects
                // (bucket = spread hash of the old class id); ties among equally sized rows are shown in that order
                int tableSize = totalExpectedObjects <= 1 ? 1 : Integer.highestOneBit(totalExpectedObjects - 1) << 1;
                long[] byBucket = new long[removedCount.size()];
                int bi = 0;
                for (int cid : removedCount.keySet()) {
                    int bucket = (cid ^ (cid >>> 16)) & (tableSize - 1);
                    byBucket[bi++] = ((long) bucket << 32) | cid;
                }
                Arrays.sort(byBucket);
                for (long packed : byBucket) {
                    int cid = (int) packed;
                    HeapRecords.ClassRecord cr = classByOldId.get(cid);
                    unreachableHistogram.append(cr != null ? cr.classId() : 0L).append(',')
                            .append(removedCount.get(cid)).append(',').append(removedSize.get(cid)).append(',')
                            .append(cr != null ? cr.name() : "unknown").append('\n');
                }
                log.info("Removing {} unreachable objects (MAT default) ...", totalExpectedObjects - reachableCount);
                graph.compact(reachable);
            }
        }
        final int finalObjectCount = graph.objectCount();

        // Resolved GC roots with the final object IDs
        List<HeapRecords.GcRootRecord> finalRoots = new ArrayList<>(resolvedRoots.size());
        for (var r : resolvedRoots) {
            int oid = r.objectId() >= 0 ? graph.newIdOf(r.objectId()) : -1;
            int tid = r.threadObjectId() >= 0 ? graph.newIdOf(r.threadObjectId()) : -1;
            finalRoots.add(new HeapRecords.GcRootRecord(oid, r.objectAddress(), r.referrerAddress(), r.rootType(), r.threadAddress(), tid,
                    r.oldObjectId() >= 0 ? r.oldObjectId() : r.objectId(), r.oldThreadObjectId() >= 0 ? r.oldThreadObjectId() : r.threadObjectId()));
        }
        graph.setGcRoots(finalRoots);

        // Save precomputed class stats and pre-resolved class hierarchy
        try {
            List<HeapRecords.ClassRecord> keptClasses = new ArrayList<>(pass1.getClasses().size());
            for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
                if (graph.newIdOf(addressToId.get(cls.classId())) >= 0) {
                    keptClasses.add(cls);
                }
            }
            storage.saveClasses(keptClasses);
            List<long[]> classOrder = new ArrayList<>(pass1.getClasses().size());
            for (HeapRecords.ClassRecord cls : pass1.getClasses().values()) {
                int oldId = addressToId.get(cls.classId());
                classOrder.add(new long[]{classOrder.size(), oldId, cls.classId(), graph.newIdOf(oldId)});
            }
            storage.saveClassOrder(classOrder);
            storage.insertGcRootsBatch(finalRoots);

            List<HeapStorageEngine.ClassStats> statsList = new ArrayList<>();
            long totalHeap = 0;
            int[] statClassIds = classCounts.keySet().toIntArray();
            Arrays.sort(statClassIds);
            for (int cid : statClassIds) {
                if (classCounts.get(cid) > 0) {
                    statsList.add(new HeapStorageEngine.ClassStats(graph.newIdOf(cid), classCounts.get(cid), classTotalSizes.get(cid)));
                }
                totalHeap += classTotalSizes.get(cid);
            }
            storage.saveClassStats(statsList);

            List<HeapStorageEngine.ResolvedClassMetadata> resolvedClasses = new ArrayList<>(keptClasses.size());
            for (HeapRecords.ClassRecord cls : keptClasses) {
                int classObjId = graph.newIdOf(addressToId.get(cls.classId()));
                // MAT looks the (null) super address up like any other: address 0 is object 0, the artificial class loader
                int superObjId = graph.newIdOf(addressToId.get(cls.superClassId()));
                int loaderObjId = graph.newIdOf(cls.classLoaderId() != 0 ? addressToId.get(cls.classLoaderId()) : systemClassLoaderObjId);
                long staticFieldsSize = 0;
                for (var sf : cls.staticFields()) {
                    staticFieldsSize += (sf.type() == HprofConstants.Type.OBJECT) ? refSize : HprofConstants.Type.sizeOf(sf.type(), idSize);
                }
                long size = alignUpToX(staticFieldsSize, objAlign); // MAT default: statics only (no java.lang.Class instance size)
                resolvedClasses.add(new HeapStorageEngine.ResolvedClassMetadata(cls.classId(), classObjId, superObjId, loaderObjId, size));
            }
            storage.updateClassesMetadata(resolvedClasses);

            storage.saveSnapshotInfo("totalHeapSize", String.valueOf(totalHeap));
            storage.saveSnapshotInfo("numberOfOutboundReferences", String.valueOf(graph.edgeCount()));
            storage.saveSnapshotInfo("refSize", String.valueOf(refSize));
            storage.saveSnapshotInfo("objectAlign", String.valueOf(objAlign));
            storage.saveSnapshotInfo("unreachableHistogram", unreachableHistogram.toString());

            storage.saveObjects(graph);
            graph.normalizeEdges();
            storage.saveOutboundEdges(graph);
            storage.finishIngestion();
        } catch (SQLException e) {
            throw new IOException("Failed to persist ingested heap graph", e);
        }
        try {
            String dumpName = file.getName();
            int dot = dumpName.lastIndexOf('.');
            File threads = new File(file.getAbsoluteFile().getParentFile(), (dot > 0 ? dumpName.substring(0, dot) : dumpName) + ".threads");
            if (pass1.writeThreadsFile(threads)) {
                log.info("Wrote thread stacks to {}", threads);
            }
        } catch (IOException e) {
            log.warn("Could not write thread stacks: {}", e.getMessage());
        }
        graph.releaseObjectColumns();
        graph.releaseUsedSizes();
        log.info("Pass 2 Completed in {} ms (parse {} ms, persist {} ms): objects={}",
                System.currentTimeMillis() - tStart, tParsed - tStart, System.currentTimeMillis() - tParsed,
                writtenObjects.size());
        return graph;
    }

    private static int[] rootIdsOf(List<HeapRecords.GcRootRecord> roots, int n) {
        java.util.BitSet seen = new java.util.BitSet(n);
        it.unimi.dsi.fastutil.ints.IntArrayList list = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (var r : roots) {
            int id = r.objectId();
            if (id >= 0 && id < n && r.threadAddress() == 0 && !seen.get(id)) {
                seen.set(id);
                list.add(id);
            }
        }
        return list.toIntArray();
    }

    private ClassLayout buildLayout(long classAddr, int idSize, int javaLangClassObjId, java.util.BitSet isClass) {
        HeapRecords.ClassRecord record = pass1.getClasses().get(classAddr);
        List<HeapRecords.ClassRecord> hierarchy = new ArrayList<>();
        HeapRecords.ClassRecord curr = record;
        while (curr != null) {
            hierarchy.add(curr);
            if (curr.superClassId() == 0L) break;
            curr = pass1.getClasses().get(curr.superClassId());
        }
        int assigned = addressToId.get(classAddr);
        if (assigned < 0 || !isClass.get(assigned)) {
            assigned = javaLangClassObjId != -1 ? javaLangClassObjId : 0;
        }
        int offset = 0;
        int refs = 0;
        for (HeapRecords.ClassRecord cls : hierarchy) {
            for (HeapRecords.FieldDescriptor f : cls.fields()) {
                if (f.type() == HprofConstants.Type.OBJECT) refs++;
            }
        }
        int[] refOffsets = new int[refs];
        int r = 0;
        for (HeapRecords.ClassRecord cls : hierarchy) {
            for (HeapRecords.FieldDescriptor f : cls.fields()) {
                if (f.type() == HprofConstants.Type.OBJECT) {
                    refOffsets[r++] = offset;
                }
                offset += HprofConstants.Type.sizeOf(f.type(), idSize);
            }
        }
        return new ClassLayout(record, hierarchy, assigned, refOffsets, offset);
    }

    private static long readIdFromBytes(byte[] bytes, int offset, int idSize) {
        long id = 0L;
        for (int i = 0; i < idSize; i++) {
            id = (id << 8) | (bytes[offset + i] & 0xFFL);
        }
        return id;
    }

    private static long alignUpToX(long n, int x) {
        long r = n % x;
        return r == 0 ? n : n + x - r;
    }

    public IAddressToIdMap getAddressToId() {
        return addressToId;
    }

    /** The graph built by the last {@link #ingest(File)} call. */
    public HeapGraph getGraph() {
        return graph;
    }

    @Override
    public void close() {
        if (addressToId != null) {
            addressToId.close();
        }
    }
}
