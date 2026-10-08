package org.eclipse.mat.dhp.core.parser;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.eclipse.mat.dhp.core.model.HeapRecords;
import org.eclipse.mat.dhp.core.model.HprofConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<Long, Long> classSerialNumberToId = new HashMap<>();
    private final Map<Long, Long> classIdToNameId = new HashMap<>();
    private final Map<Long, HeapRecords.ClassRecord> classes = new HashMap<>();
    private final LongArrayList objectAddresses = new LongArrayList();
    private final List<HeapRecords.GcRootRecord> gcRoots = new ArrayList<>();
    private final Map<Long, Long> classToSuperClass = new HashMap<>();

    private HeapRecords.Header header;

    public void scan(File file) throws IOException {
        try (HprofBinaryReader reader = new HprofBinaryReader(file)) {
            this.header = reader.readHeader();
            int idSize = reader.getIdSize();

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

        // Ensure primitive array classes exist
        long pseudoId = -100L;
        for (String primType : new String[]{"boolean[]", "char[]", "float[]", "double[]", "byte[]", "short[]", "int[]", "long[]"}) {
            boolean found = false;
            for (HeapRecords.ClassRecord c : classes.values()) {
                if (primType.equals(c.name())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                long id = pseudoId--;
                classes.put(id, new HeapRecords.ClassRecord(
                        id,
                        0L,
                        0L,
                        primType,
                        0,
                        List.of(),
                        List.of()
                ));
            }
        }

        // Recalculate runtime instance sizes with exact header + field alignment (MAT parity)
        calculateRuntimeInstanceSizes(header.idSize());

        log.info("Pass 1 Completed: Parsed {} strings, {} classes, {} GC roots",
                strings.size(), classes.size(), gcRoots.size());
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
                    long jniGlobalRef = reader.readId();
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, jniGlobalRef, subTag, 0));
                }
                case HprofConstants.DumpSegment.ROOT_JNI_LOCAL -> {
                    long objAddr = reader.readId();
                    long threadSerNum = reader.readUnsignedInt();
                    reader.skipBytes(4); // frame number
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, threadSerNum));
                }
                case HprofConstants.DumpSegment.ROOT_JAVA_FRAME -> {
                    long objAddr = reader.readId();
                    long threadSerNum = reader.readUnsignedInt();
                    reader.skipBytes(4); // frame number
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, threadSerNum));
                }
                case HprofConstants.DumpSegment.ROOT_NATIVE_STACK -> {
                    long objAddr = reader.readId();
                    long threadSerNum = reader.readUnsignedInt();
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, threadSerNum));
                }
                case HprofConstants.DumpSegment.ROOT_STICKY_CLASS -> {
                    long objAddr = reader.readId();
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, 0));
                }
                case HprofConstants.DumpSegment.ROOT_THREAD_BLOCK -> {
                    long objAddr = reader.readId();
                    long threadSerNum = reader.readUnsignedInt();
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, threadSerNum));
                }
                case HprofConstants.DumpSegment.ROOT_MONITOR_USED -> {
                    long objAddr = reader.readId();
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, 0));
                }
                case HprofConstants.DumpSegment.ROOT_THREAD_OBJECT -> {
                    long objAddr = reader.readId();
                    long threadSerNum = reader.readUnsignedInt();
                    reader.skipBytes(4); // stack trace serial
                    gcRoots.add(new HeapRecords.GcRootRecord(objAddr, 0, subTag, threadSerNum));
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
                    reader.skipBytes(4); // stack trace
                    int arrayLength = reader.readInt();
                    long elementClassId = reader.readId();
                    reader.skipBytes((long) arrayLength * idSize);
                }
                case HprofConstants.DumpSegment.PRIMITIVE_ARRAY_DUMP -> {
                    long objAddr = reader.readId();
                    objectAddresses.add(objAddr);
                    reader.skipBytes(4); // stack trace
                    int arrayLength = reader.readInt();
                    int elementType = reader.readByte();
                    int elementSize = HprofConstants.Type.sizeOf(elementType, idSize);
                    reader.skipBytes((long) arrayLength * elementSize);
                }
                default -> throw new IOException("Unknown dump segment type: " + subTag + " at position " + reader.getPosition());
            }
        }
    }

    public LongArrayList getObjectAddresses() {
        return objectAddresses;
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
        String name = switch (elementType) {
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
        for (HeapRecords.ClassRecord c : classes.values()) {
            if (name.equals(c.name())) {
                return c.classId();
            }
        }
        return 0L;
    }

    private void calculateRuntimeInstanceSizes(int idSize) {
        int pointerSize = idSize;
        int refSize = idSize;
        int objectAlign = 8;

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
