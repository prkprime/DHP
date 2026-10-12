package org.eclipse.mat.dhp.core.parser;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.eclipse.mat.dhp.core.memory.InPlaceLongSort;
import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.model.HprofConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pass 1 Parser: Reads UTF-8 strings, Class definitions, Stack frames,
 * calculates class instance sizes, and collects GC roots.
 */
public class Pass1ScanParser {
    private static final Logger log = LoggerFactory.getLogger(Pass1ScanParser.class);
    private static final Pattern PATTERN_OBJ_ARRAY = Pattern.compile("^(\\[+)L(.*);$");
    private static final Pattern PATTERN_PRIMITIVE_ARRAY = Pattern.compile("^(\\[+)(.)$");

    private final Map<Long, String> strings = new HashMap<>();
    private final Set<Long> referencedStringIds = new HashSet<>();
    private final Map<Long, Long> classSerialNumberToId = new HashMap<>();
    private final Map<Long, Long> classIdToNameId = new HashMap<>();
    private final Map<Long, HeapRecords.ClassRecord> classes = new HashMap<>();
    private final LongArrayList objectAddresses = new LongArrayList();
    private long[] sortedInstanceAddresses;
    private int uniqueInstanceCount;
    private final List<HeapRecords.GcRootRecord> gcRoots = new ArrayList<>();
    // Thread serial number -> thread object address (from ROOT_THREAD_OBJECT), as MAT's Pass1Parser tracks it.
    private final Map<Long, Long> threadSerialToAddress = new HashMap<>();

    // Stack traces for MAT's <prefix>threads file (thread overview, stack frames and local variables)
    private record StackFrameRec(String method, String signature, String sourceFile, long classSerialNumber, int lineNumber) {}
    private record StackTraceRec(long threadSerialNumber, long[] frameIds) {}
    private final Map<Long, String> classNameById = new HashMap<>();
    private final Map<Long, StackFrameRec> stackFrames = new HashMap<>();
    private final Map<Long, StackTraceRec> stackTraces = new java.util.LinkedHashMap<>();
    private final Map<Long, List<long[]>> threadLocals = new HashMap<>(); // thread serial -> {objectAddress, frameNumber}
    private final Map<Long, Long> classToSuperClass = new HashMap<>();

    private HeapRecords.Header header;

    // Heap layout detection, mirroring Eclipse MAT's HprofParserHandlerImpl / Pass1Parser:
    // references are 4 bytes when compressed oops are detected on a 64-bit dump, headers always hold an id-sized pointer,
    // and objects are aligned to the GCD of the address gaps (at least 8).
    private int refSize = 8;
    private int objectAlign = 8;
    private boolean foundCompressed;
    private final boolean[] requiredPrimitiveArrays = new boolean[16];
    private long previousArrayStart;
    private long previousArrayUncompressedEnd;

    public void scan(File file) throws IOException {
        try (HprofBinaryReader reader = new HprofBinaryReader(file)) {
            this.header = reader.readHeader();
            int idSize = reader.getIdSize();
            this.refSize = idSize;

            while (true) {
                int tag = reader.readByte();
                if (tag == -1) {
                    break;
                }
                long timeStamp = reader.readUnsignedInt();
                long length = reader.readUnsignedInt();
                long recordStartPos = reader.getPosition();

                switch (tag) {
                    case HprofConstants.Record.STRING_IN_UTF8 -> {
                        long stringId = reader.readId();
                        int stringBytesLength = (int) (length - idSize);
                        byte[] bytes = reader.readBytes(stringBytesLength);
                        String str = new String(bytes, StandardCharsets.UTF_8);
                        strings.put(stringId, str);
                    }
                    case HprofConstants.Record.LOAD_CLASS -> {
                        long classSerNum = reader.readUnsignedInt();
                        long classId = reader.readId();
                        reader.skipBytes(4); // stack trace serial
                        long nameId = reader.readId();
                        classSerialNumberToId.put(classSerNum, classId);
                        classIdToNameId.put(classId, nameId);
                        referencedStringIds.add(nameId);
                        String rawClassName = strings.get(nameId);
                        if (rawClassName != null) {
                            int paren = rawClassName.indexOf('(');
                            classNameById.put(classId, paren < 0 ? rawClassName.replace('/', '.')
                                    : rawClassName.substring(0, paren).replace('/', '.') + rawClassName.substring(paren));
                        }
                    }
                    case HprofConstants.Record.UNLOAD_CLASS -> {
                        long classSerNum = reader.readUnsignedInt();
                        reader.skipBytes(length - 4);
                        Long unloaded = classSerialNumberToId.get(classSerNum);
                        if (unloaded != null) {
                            classNameById.remove(unloaded); // like MAT: only active classes keep a name
                        }
                    }
                    case HprofConstants.Record.STACK_FRAME -> {
                        long frameId = reader.readId();
                        long methodName = reader.readId();
                        long methodSig = reader.readId();
                        long srcFile = reader.readId();
                        long classSerNum = reader.readUnsignedInt();
                        int lineNr = reader.readInt();
                        stackFrames.put(frameId, new StackFrameRec(stringConstant(methodName), stringConstant(methodSig),
                                stringConstant(srcFile), classSerNum, lineNr));
                    }
                    case HprofConstants.Record.STACK_TRACE -> {
                        long traceSerial = reader.readUnsignedInt();
                        long threadSerial = reader.readUnsignedInt();
                        long frameCount = reader.readUnsignedInt();
                        long[] frameIds = new long[(int) frameCount];
                        for (int i = 0; i < frameCount; i++) {
                            frameIds[i] = reader.readId();
                        }
                        stackTraces.put(traceSerial, new StackTraceRec(threadSerial, frameIds));
                    }
                    case HprofConstants.Record.HEAP_DUMP, HprofConstants.Record.HEAP_DUMP_SEGMENT -> {
                        scanHeapDumpSegment(reader, length, idSize);
                    }
                    default -> {
                        reader.skipBytes(length);
                    }
                }
            }
        }

        // Register any classes declared via LOAD_CLASS without explicit CLASS_DUMP
        for (Map.Entry<Long, Long> entry : classIdToNameId.entrySet()) {
            long classId = entry.getKey();
            if (!classes.containsKey(classId)) {
                String rawName = strings.get(entry.getValue());
                String className = formatClassName(rawName);
                classes.put(classId, new HeapRecords.ClassRecord(
                        classId,
                        0L,
                        0L,
                        className,
                        0,
                        List.of(),
                        List.of()
                ));
            }
        }

        // Prune strings map to keep only referenced names, freeing potentially millions of strings
        int totalStringsFound = strings.size();
        Map<Long, String> retained = new HashMap<>(referencedStringIds.size());
        for (Long sid : referencedStringIds) {
            String val = strings.get(sid);
            if (val != null) {
                retained.put(sid, val);
            }
        }
        strings.clear();
        strings.putAll(retained);
        referencedStringIds.clear();

        // Sort and deduplicate instance object addresses in-place to compact memory and prepare binary search index.
        // Uses InPlaceLongSort (0 auxiliary allocations) to completely avoid tryMergeRuns OOM.
        if (!objectAddresses.isEmpty()) {
            long[] raw = objectAddresses.elements();
            int rawSize = objectAddresses.size();
            InPlaceLongSort.sort(raw, 0, rawSize, Runtime.getRuntime().availableProcessors());
            int unique = 0;
            for (int i = 0; i < rawSize; i++) {
                long addr = raw[i];
                if (addr != 0L && !classes.containsKey(addr) && (unique == 0 || addr != raw[unique - 1])) {
                    raw[unique++] = addr;
                }
            }
            this.uniqueInstanceCount = unique;
            this.sortedInstanceAddresses = raw;
        } else {
            this.uniqueInstanceCount = 0;
            this.sortedInstanceAddresses = new long[0];
        }

        // Object alignment (needs the sorted addresses), then runtime instance sizes with exact header + field
        // alignment (MAT parity)
        this.objectAlign = calculateAlignment();
        createRequiredFakeClasses();
        calculateRuntimeInstanceSizes(header.idSize());
        mergeAllIdentifiers();

        log.info("Pass 1 Completed: Parsed {} strings, {} classes, {} unique objects, {} GC roots",
                totalStringsFound, classes.size(), uniqueInstanceCount, gcRoots.size());
    }

    private void scanHeapDumpSegment(HprofBinaryReader reader, long segmentLength, int idSize) throws IOException {
        long endPosition = reader.getPosition() + segmentLength;

        while (reader.getPosition() < endPosition) {
            int subTag = reader.readByte();
            if (subTag == -1) break;

            switch (subTag) {
                case HprofConstants.DumpSegment.ROOT_UNKNOWN -> {
                    long objAddr = reader.readId();
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, 0));
                }
                case HprofConstants.DumpSegment.ROOT_JNI_GLOBAL -> {
                    long objAddr = reader.readId();
                    reader.readId(); // JNI global reference handle: MAT ignores it (root context stays empty)
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, 0));
                }
                case HprofConstants.DumpSegment.ROOT_JNI_LOCAL -> {
                    long objAddr = reader.readId();
                    long threadSerNum = reader.readUnsignedInt();
                    int frameNumber = reader.readInt();
                    threadLocals.computeIfAbsent(threadSerNum, k -> new ArrayList<>()).add(new long[]{objAddr, frameNumber});
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, threadAddressOf(threadSerNum)));
                }
                case HprofConstants.DumpSegment.ROOT_JAVA_FRAME -> {
                    long objAddr = reader.readId();
                    long threadSerNum = reader.readUnsignedInt();
                    int frameNumber = reader.readInt();
                    threadLocals.computeIfAbsent(threadSerNum, k -> new ArrayList<>()).add(new long[]{objAddr, frameNumber});
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, threadAddressOf(threadSerNum)));
                }
                case HprofConstants.DumpSegment.ROOT_NATIVE_STACK -> {
                    long objAddr = reader.readId();
                    long threadSerNum = reader.readUnsignedInt();
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, threadAddressOf(threadSerNum)));
                }
                case HprofConstants.DumpSegment.ROOT_STICKY_CLASS -> {
                    long objAddr = reader.readId();
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, 0));
                }
                case HprofConstants.DumpSegment.ROOT_THREAD_BLOCK -> {
                    long objAddr = reader.readId();
                    long threadSerNum = reader.readUnsignedInt();
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, threadAddressOf(threadSerNum)));
                }
                case HprofConstants.DumpSegment.ROOT_MONITOR_USED -> {
                    long objAddr = reader.readId();
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, 0));
                }
                case HprofConstants.DumpSegment.ROOT_THREAD_OBJECT -> {
                    long objAddr = reader.readId();
                    long threadSerNum = reader.readUnsignedInt();
                    reader.skipBytes(4); // stack trace serial
                    threadSerialToAddress.put(threadSerNum, objAddr);
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, 0));
                }
                case HprofConstants.DumpSegment.CLASS_DUMP -> {
                    long classId = reader.readId();
                    reader.skipBytes(4); // stack trace serial
                    long superClassId = reader.readId();
                    long classLoaderId = reader.readId();
                    reader.skipBytes(idSize * 4L); // signers, protection domain, reserved1, reserved2
                    int instanceSize = reader.readInt();

                    // constant pool
                    int cpCount = reader.readUnsignedShort();
                    for (int i = 0; i < cpCount; i++) {
                        reader.skipBytes(2); // index
                        int cpType = reader.readByte();
                        reader.skipBytes(HprofConstants.Type.sizeOf(cpType, idSize));
                    }

                    // static fields
                    int staticCount = reader.readUnsignedShort();
                    List<HeapRecords.StaticFieldRecord> staticFields = new ArrayList<>(staticCount);
                    for (int i = 0; i < staticCount; i++) {
                        long fieldNameId = reader.readId();
                        referencedStringIds.add(fieldNameId);
                        int fieldType = reader.readByte();
                        Object val = switch (fieldType) {
                            case HprofConstants.Type.OBJECT -> reader.readId();
                            case HprofConstants.Type.BOOLEAN -> reader.readBoolean();
                            case HprofConstants.Type.CHAR -> reader.readChar();
                            case HprofConstants.Type.FLOAT -> reader.readFloat();
                            case HprofConstants.Type.DOUBLE -> reader.readDouble();
                            case HprofConstants.Type.BYTE -> (byte) reader.readByte();
                            case HprofConstants.Type.SHORT -> reader.readShort();
                            case HprofConstants.Type.INT -> reader.readInt();
                            case HprofConstants.Type.LONG -> reader.readLong();
                            default -> {
                                reader.skipBytes(HprofConstants.Type.sizeOf(fieldType, idSize));
                                yield null;
                            }
                        };
                        String fieldName = strings.getOrDefault(fieldNameId, "field_" + fieldNameId);
                        staticFields.add(new HeapRecords.StaticFieldRecord(fieldName, fieldType, val));
                    }

                    // instance fields
                    int fieldCount = reader.readUnsignedShort();
                    List<HeapRecords.FieldDescriptor> fields = new ArrayList<>(fieldCount);
                    for (int i = 0; i < fieldCount; i++) {
                        long fieldNameId = reader.readId();
                        referencedStringIds.add(fieldNameId);
                        int fieldType = reader.readByte();
                        String fieldName = strings.getOrDefault(fieldNameId, "field_" + fieldNameId);
                        fields.add(new HeapRecords.FieldDescriptor(fieldName, fieldType));
                    }

                    classToSuperClass.put(classId, superClassId);
                    Long nameId = classIdToNameId.get(classId);
                    String rawName = nameId != null ? strings.get(nameId) : null;
                    String className = rawName != null ? formatClassName(rawName) : ("Class@" + Long.toHexString(classId));

                    classes.put(classId, new HeapRecords.ClassRecord(
                            classId,
                            superClassId,
                            classLoaderId,
                            className,
                            instanceSize,
                            fields,
                            staticFields
                    ));
                }
                case HprofConstants.DumpSegment.INSTANCE_DUMP -> {
                    long objAddr = reader.readId();
                    objectAddresses.add(objAddr);
                    reader.skipBytes(4); // stack trace
                    long classId = reader.readId();
                    int bytesFollow = reader.readInt();
                    reader.skipBytes(bytesFollow);
                }
                case HprofConstants.DumpSegment.OBJECT_ARRAY_DUMP -> {
                    long objAddr = reader.readId();
                    objectAddresses.add(objAddr);
                    // MAT's compressed-oops heuristic: an array starting inside the span the previous array would
                    // occupy with 8-byte references means references are really 4 bytes wide.
                    if (!foundCompressed && idSize == 8 && objAddr > previousArrayStart && objAddr < previousArrayUncompressedEnd) {
                        refSize = 4;
                        foundCompressed = true;
                    }
                    reader.skipBytes(4); // stack trace
                    int arrayLength = reader.readInt();
                    long elementClassId = reader.readId();
                    reader.skipBytes((long) arrayLength * idSize);
                    previousArrayStart = objAddr;
                    previousArrayUncompressedEnd = objAddr + 16 + (long) arrayLength * 8;
                }
                case HprofConstants.DumpSegment.PRIMITIVE_ARRAY_DUMP -> {
                    long objAddr = reader.readId();
                    objectAddresses.add(objAddr);
                    reader.skipBytes(4); // stack trace
                    int arrayLength = reader.readInt();
                    int elementType = reader.readByte();
                    int elementSize = HprofConstants.Type.sizeOf(elementType, idSize);
                    reader.skipBytes((long) arrayLength * elementSize);
                    requiredPrimitiveArrays[elementType] = true;
                }
                default -> throw new IOException("Unknown dump segment type: " + subTag + " at position " + reader.getPosition());
            }
        }
    }

    private static String primitiveArrayName(int elementType) {
        return switch (elementType) {
            case HprofConstants.Type.BOOLEAN -> "boolean[]";
            case HprofConstants.Type.CHAR -> "char[]";
            case HprofConstants.Type.FLOAT -> "float[]";
            case HprofConstants.Type.DOUBLE -> "double[]";
            case HprofConstants.Type.BYTE -> "byte[]";
            case HprofConstants.Type.SHORT -> "short[]";
            case HprofConstants.Type.INT -> "int[]";
            case HprofConstants.Type.LONG -> "long[]";
            default -> "unknown[]";
        };
    }

    private boolean hasClassNamed(String name) {
        for (HeapRecords.ClassRecord c : classes.values()) {
            if (name.equals(c.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mirrors MAT's createRequiredFakeClasses: java.lang.Class / java.lang.ClassLoader when the dump lacks them and the
     * array classes of the primitive array types in use, placed at the first free multiples of the object alignment.
     */
    private void createRequiredFakeClasses() {
        long jlo = 0L;
        for (HeapRecords.ClassRecord c : classes.values()) {
            if ("java.lang.Object".equals(c.name())) {
                jlo = c.classId();
                break;
            }
        }
        long next = 0;
        java.util.List<String> names = new ArrayList<>(List.of("java.lang.Class", "java.lang.ClassLoader"));
        for (int type = HprofConstants.Type.BOOLEAN; type <= HprofConstants.Type.LONG; type++) {
            if (requiredPrimitiveArrays[type]) {
                names.add(primitiveArrayName(type));
            }
        }
        for (String name : names) {
            if (hasClassNamed(name)) {
                continue;
            }
            do {
                next += objectAlign;
            } while (classes.containsKey(next)
                    || (uniqueInstanceCount > 0 && java.util.Arrays.binarySearch(sortedInstanceAddresses, 0, uniqueInstanceCount, next) >= 0));
            classes.put(next, new HeapRecords.ClassRecord(next, jlo, 0L, name, 0, List.of(), List.of()));
        }
    }

    /**
     * Turns the sorted instance addresses into the identifier array of the whole heap, exactly as MAT numbers objects:
     * the artificial system class loader (address 0) followed by every object, class objects included, in address order.
     * Object ID = position in this array.
     */
    private void mergeAllIdentifiers() {
        long[] cls = classes.keySet().stream().mapToLong(Long::longValue).filter(a -> a > 0).sorted().toArray();
        int ni = uniqueInstanceCount;
        long[] inst = sortedInstanceAddresses;
        int total = ni + cls.length + 1;
        if (inst.length < total) {
            inst = java.util.Arrays.copyOf(inst, total);
        }
        int i = ni - 1;
        int j = cls.length - 1;
        int k = total - 1;
        while (j >= 0 || i >= 0) {
            if (j < 0 || (i >= 0 && inst[i] > cls[j])) {
                inst[k--] = inst[i--];
            } else {
                inst[k--] = cls[j--];
            }
        }
        inst[0] = 0L; // system class loader
        this.sortedInstanceAddresses = inst;
        this.uniqueInstanceCount = total;
    }

    private String stringConstant(long id) {
        if (id == 0L) {
            return "";
        }
        String v = strings.get(id);
        return v != null ? v : "Unresolved Name 0x" + Long.toHexString(id);
    }

    private String formatFrame(StackFrameRec f) {
        Long classId = classSerialNumberToId.get(f.classSerialNumber());
        String className = classId == null ? "<UNKNOWN CLASS>" : classNameById.get(classId);
        String loc;
        int line = f.lineNumber();
        if (line > 0) {
            loc = f.sourceFile() + ":" + line;
        } else if (line == 0 || line == -1) {
            loc = f.sourceFile() != null ? f.sourceFile() : "Unknown Source";
        } else if (line == -2) {
            loc = (f.sourceFile() != null ? f.sourceFile() : "") + "(Compiled Code)";
        } else if (line == -3) {
            loc = (f.sourceFile() != null ? f.sourceFile() : "") + "(Native Method)";
        } else {
            loc = "";
        }
        return "  at " + className + "." + f.method() + f.signature() + " (" + loc + ")";
    }

    /**
     * Writes the stack traces in the format of MAT's own HPROF parser (<prefix>threads), which MAT reads to show
     * thread stacks and the local variables of each frame. Nothing is written for dumps without call stacks.
     *
     * @return true if a file was written
     */
    public boolean writeThreadsFile(File out) throws IOException {
        // MAT: one stack trace with an empty stack is always reported, even if the dump has no call stacks
        if (stackTraces.size() <= 1) {
            return false;
        }
        try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(out), StandardCharsets.UTF_8))) {
            for (StackTraceRec trace : stackTraces.values()) {
                Long tid = threadSerialToAddress.get(trace.threadSerialNumber());
                if (tid == null) {
                    continue;
                }
                w.print("Thread 0x" + Long.toHexString(tid) + "\n");
                StringBuilder stack = new StringBuilder();
                for (long frameId : trace.frameIds()) {
                    StackFrameRec f = stackFrames.get(frameId);
                    if (f != null) {
                        stack.append(formatFrame(f)).append("\r\n");
                    }
                }
                w.print(stack + "\n");
                w.print("  locals:\n");
                List<long[]> locals = threadLocals.get(trace.threadSerialNumber());
                if (locals != null) {
                    for (long[] l : locals) {
                        w.print("    objectId=0x" + Long.toHexString(l[0]) + ", line=" + (int) l[1] + "\n");
                    }
                }
                w.print("\n");
            }
        }
        return true;
    }

    /**
     * Address of the thread object for a thread serial number, or 0 when that thread has not been seen yet.
     * Like MAT, roots that belong to a known thread (stack locals) are not GC roots themselves: the thread object
     * references them instead. Roots of unknown threads stay plain roots.
     */
    private long threadAddressOf(long threadSerialNumber) {
        Long addr = threadSerialToAddress.get(threadSerialNumber);
        return addr != null ? addr : 0L;
    }

    /**
     * GCD of the gaps between consecutive object addresses (class objects included), clamped to [8, 256]; the same
     * computation as MAT's HprofParserHandlerImpl.calculateAlignment().
     */
    private int calculateAlignment() {
        long[] classAddrs = classes.keySet().stream().mapToLong(Long::longValue).filter(a -> a > 0).sorted().toArray();
        long[] inst = sortedInstanceAddresses;
        int ni = uniqueInstanceCount;
        int i = 0, c = 0;
        long prev = 0;
        long align = 0;
        while (i < ni || c < classAddrs.length) {
            long next;
            if (c >= classAddrs.length || (i < ni && inst[i] < classAddrs[c])) {
                next = inst[i++];
            } else {
                next = classAddrs[c++];
            }
            long diff = next - prev;
            prev = next;
            if (next == diff) continue;
            if (align == 0) {
                align = diff;
            } else {
                long mx = Math.max(align, diff);
                long mn = Math.min(align, diff);
                long d = mx % mn;
                while (d != 0) {
                    mx = mn;
                    mn = d;
                    d = mx % mn;
                }
                align = mn;
                if (align <= 8) break;
            }
        }
        return Math.max((int) Math.min(align, 256), 8);
    }

    /** Size of (possibly compressed) references in the heap, as detected like MAT does. */
    public int getRefSize() {
        return refSize;
    }

    /** Alignment between successive objects, as detected like MAT does. */
    public int getObjectAlign() {
        return objectAlign;
    }

    public LongArrayList getObjectAddresses() {
        if (sortedInstanceAddresses != null && sortedInstanceAddresses.length > 0) {
            return LongArrayList.wrap(sortedInstanceAddresses);
        }
        return objectAddresses;
    }

    public long[] getSortedInstanceAddresses() {
        return sortedInstanceAddresses != null ? sortedInstanceAddresses : new long[0];
    }

    public int getUniqueInstanceCount() {
        return uniqueInstanceCount;
    }

    public void releaseSortedInstanceAddresses() {
        this.sortedInstanceAddresses = null;
        this.objectAddresses.clear();
        this.objectAddresses.trim();
    }

    public HeapRecords.Header getHeader() {
        return header;
    }

    public Map<Long, String> getStrings() {
        return strings;
    }

    public Map<Long, HeapRecords.ClassRecord> getClasses() {
        return classes;
    }

    public List<HeapRecords.GcRootRecord> getGcRoots() {
        return gcRoots;
    }

    public String formatClassName(String rawName) {
        if (rawName == null) return "UnknownClass";
        int methodArgs = rawName.indexOf('(');
        String className;
        if (methodArgs < 0) {
            className = rawName.replace('/', '.');
        } else {
            className = rawName.substring(0, methodArgs).replace('/', '.') + rawName.substring(methodArgs);
        }
        if (className.startsWith("[")) {
            Matcher matcher = PATTERN_OBJ_ARRAY.matcher(className);
            if (matcher.matches()) {
                int l = matcher.group(1).length();
                StringBuilder sb = new StringBuilder(matcher.group(2).replace('/', '.'));
                for (int k = 0; k < l; k++) sb.append("[]");
                className = sb.toString();
            } else {
                Matcher primMatcher = PATTERN_PRIMITIVE_ARRAY.matcher(className);
                if (primMatcher.matches()) {
                    int count = primMatcher.group(1).length();
                    char sig = primMatcher.group(2).charAt(0);
                    String primType = switch (sig) {
                        case 'Z' -> "boolean";
                        case 'C' -> "char";
                        case 'F' -> "float";
                        case 'D' -> "double";
                        case 'B' -> "byte";
                        case 'S' -> "short";
                        case 'I' -> "int";
                        case 'J' -> "long";
                        default -> "unknown";
                    };
                    StringBuilder sb = new StringBuilder(primType);
                    for (int k = 0; k < count; k++) sb.append("[]");
                    className = sb.toString();
                }
            }
        }
        return className;
    }

    public long getPrimitiveArrayClassId(int elementType) {
        String name = primitiveArrayName(elementType);
        for (HeapRecords.ClassRecord c : classes.values()) {
            if (name.equals(c.name())) {
                return c.classId();
            }
        }
        return 0L;
    }

    private void calculateRuntimeInstanceSizes(int idSize) {
        int pointerSize = idSize;
        int refSize = this.refSize;
        int objectAlign = this.objectAlign;

        Map<Long, Integer> calculatedSizes = new HashMap<>();
        for (HeapRecords.ClassRecord cls : new ArrayList<>(classes.values())) {
            int size = calculateInstanceSize(cls, pointerSize, refSize, objectAlign, calculatedSizes);
            classes.put(cls.classId(), new HeapRecords.ClassRecord(
                    cls.classId(),
                    cls.superClassId(),
                    cls.classLoaderId(),
                    cls.name(),
                    size,
                    cls.fields(),
                    cls.staticFields()
            ));
        }
    }

    private int calculateInstanceSize(HeapRecords.ClassRecord cls, int pointerSize, int refSize, int objectAlign, Map<Long, Integer> cache) {
        if (cls.name().endsWith("[]")) {
            return refSize;
        }
        if (cache.containsKey(cls.classId())) {
            return cache.get(cls.classId());
        }
        int unaligned = calculateSizeRecursive(cls, pointerSize, refSize, cache);
        int aligned = alignUpToX(unaligned, objectAlign);
        cache.put(cls.classId(), aligned);
        return aligned;
    }

    private int calculateSizeRecursive(HeapRecords.ClassRecord cls, int pointerSize, int refSize, Map<Long, Integer> cache) {
        if (cls.superClassId() == 0L) {
            return pointerSize + refSize; // Object header: mark word + klass pointer
        }
        HeapRecords.ClassRecord superClass = classes.get(cls.superClassId());
        int ownFieldsSize = 0;
        for (HeapRecords.FieldDescriptor field : cls.fields()) {
            ownFieldsSize += sizeOfField(field.type(), refSize);
        }
        int superSize = (superClass != null && superClass.classId() != cls.classId())
                ? calculateSizeRecursive(superClass, pointerSize, refSize, cache)
                : (pointerSize + refSize);
        return alignUpToX(ownFieldsSize + superSize, refSize);
    }

    private static int sizeOfField(int type, int refSize) {
        if (type == HprofConstants.Type.OBJECT) {
            return refSize;
        }
        return switch (type) {
            case HprofConstants.Type.BOOLEAN, HprofConstants.Type.BYTE -> 1;
            case HprofConstants.Type.CHAR, HprofConstants.Type.SHORT -> 2;
            case HprofConstants.Type.FLOAT, HprofConstants.Type.INT -> 4;
            case HprofConstants.Type.DOUBLE, HprofConstants.Type.LONG -> 8;
            default -> refSize;
        };
    }

    private static int alignUpToX(int n, int x) {
        int r = n % x;
        return r == 0 ? n : n + x - r;
    }
}
