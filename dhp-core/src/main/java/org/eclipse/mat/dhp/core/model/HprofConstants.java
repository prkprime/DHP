package org.eclipse.mat.dhp.core.model;

/**
 * Standard HPROF Record tags and constants according to JVM HPROF specification.
 */
public final class HprofConstants {
    private HprofConstants() {}

    public static final class Record {
        public static final int STRING_IN_UTF8 = 0x01;
        public static final int LOAD_CLASS = 0x02;
        public static final int UNLOAD_CLASS = 0x03;
        public static final int STACK_FRAME = 0x04;
        public static final int STACK_TRACE = 0x05;
        public static final int ALLOC_SITES = 0x06;
        public static final int HEAP_SUMMARY = 0x07;
        public static final int START_THREAD = 0x0a;
        public static final int END_THREAD = 0x0b;
        public static final int HEAP_DUMP = 0x0c;
        public static final int HEAP_DUMP_SEGMENT = 0x1c;
        public static final int HEAP_DUMP_END = 0x2c;
        public static final int CPU_SAMPLES = 0x0d;
        public static final int CONTROL_SETTINGS = 0x0e;
    }

    public static final class DumpSegment {
        public static final int ROOT_UNKNOWN = 0xff;
        public static final int ROOT_JNI_GLOBAL = 0x01;
        public static final int ROOT_JNI_LOCAL = 0x02;
        public static final int ROOT_JAVA_FRAME = 0x03;
        public static final int ROOT_NATIVE_STACK = 0x04;
        public static final int ROOT_STICKY_CLASS = 0x05;
        public static final int ROOT_THREAD_BLOCK = 0x06;
        public static final int ROOT_MONITOR_USED = 0x07;
        public static final int ROOT_THREAD_OBJECT = 0x08;
        public static final int CLASS_DUMP = 0x20;
        public static final int INSTANCE_DUMP = 0x21;
        public static final int OBJECT_ARRAY_DUMP = 0x22;
        public static final int PRIMITIVE_ARRAY_DUMP = 0x23;
    }

    public static final class Type {
        public static final int OBJECT = 2;
        public static final int BOOLEAN = 4;
        public static final int CHAR = 5;
        public static final int FLOAT = 6;
        public static final int DOUBLE = 7;
        public static final int BYTE = 8;
        public static final int SHORT = 9;
        public static final int INT = 10;
        public static final int LONG = 11;

        public static int sizeOf(int type, int idSize) {
            return switch (type) {
                case OBJECT -> idSize;
                case BOOLEAN, BYTE -> 1;
                case CHAR, SHORT -> 2;
                case FLOAT, INT -> 4;
                case DOUBLE, LONG -> 8;
                default -> throw new IllegalArgumentException("Unknown type: " + type);
            };
        }
    }
}
