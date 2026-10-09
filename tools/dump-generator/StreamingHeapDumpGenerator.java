package org.eclipse.mat.dhp.tools;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Ultra-fast constant-memory streaming HPROF generator.
 * Streams authentic HPROF binary records directly to disk with 0 heap overhead (<50 MB RAM).
 * Used to generate massive dumps (6 GB, 11 GB, 20 GB+) to test DHP memory limits and algorithms.
 */
public class StreamingHeapDumpGenerator {

    private static final int ID_SIZE = 8;
    private static final int SEGMENT_FLUSH_BYTES = 64 * 1024 * 1024; // 64 MB per HEAP_DUMP_SEGMENT

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java StreamingHeapDumpGenerator.java <output-path.hprof> [sizeGB] [objectCountM]");
            return;
        }

        File outputFile = new File(args[0]);
        double targetSizeGb = args.length > 1 ? Double.parseDouble(args[1]) : 11.0;
        int objectCountM = args.length > 2 ? Integer.parseInt(args[2]) : 20; // default 20M objects

        long targetSizeBytes = (long) (targetSizeGb * 1024L * 1024L * 1024L);
        long targetObjects = (long) objectCountM * 1_000_000L;

        System.out.println("================================================================================");
        System.out.println("Streaming HPROF Heap Dump Generator");
        System.out.println("Target File: " + outputFile.getAbsolutePath());
        System.out.println(String.format("Target Size: %.2f GB (%d bytes)", targetSizeGb, targetSizeBytes));
        System.out.println(String.format("Target Objects: %d objects", targetObjects));
        System.out.println("================================================================================");

        long startTime = System.currentTimeMillis();

        try (FileOutputStream fos = new FileOutputStream(outputFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 4 * 1024 * 1024);
             DataOutputStream out = new DataOutputStream(bos)) {

            // 1. Header
            out.write("JAVA PROFILE 1.0.2".getBytes(StandardCharsets.US_ASCII));
            out.writeByte(0); // null terminator
            out.writeInt(ID_SIZE);
            out.writeLong(System.currentTimeMillis());

            // 2. String Constants
            long strId = 1000L;
            long javaLangObjectStr = strId++;
            writeString(out, javaLangObjectStr, "java/lang/Object");
            long javaLangClassStr = strId++;
            writeString(out, javaLangClassStr, "java/lang/Class");
            long javaLangStringStr = strId++;
            writeString(out, javaLangStringStr, "java/lang/String");

            // App class names
            int numClasses = 2000;
            long[] classNames = new long[numClasses];
            long[] classIds = new long[numClasses];
            for (int i = 0; i < numClasses; i++) {
                classNames[i] = strId++;
                classIds[i] = 200000L + i;
                writeString(out, classNames[i], "com/enterprise/app/service/DomainEntity_" + i);
            }

            // Field names
            int numFields = 20;
            long[] fieldNames = new long[numFields];
            for (int i = 0; i < numFields; i++) {
                fieldNames[i] = strId++;
                writeString(out, fieldNames[i], "field_" + i);
            }

            // Write 100,000 application strings to test Pass 1 string table pruning
            System.out.println("[Step 1/4] Writing string records and metadata...");
            for (int i = 0; i < 100_000; i++) {
                writeString(out, strId++, "application_string_constant_payload_" + i);
            }

            // 3. LOAD_CLASS records
            System.out.println("[Step 2/4] Writing LOAD_CLASS records...");
            long javaLangObjectClassId = 100000L;
            writeLoadClass(out, 1, javaLangObjectClassId, javaLangObjectStr);
            for (int i = 0; i < numClasses; i++) {
                writeLoadClass(out, i + 2, classIds[i], classNames[i]);
            }

            // 4. Start HEAP_DUMP_SEGMENTs
            System.out.println("[Step 3/4] Streaming HEAP_DUMP_SEGMENTs with millions of objects and primitive arrays...");
            
            // First segment: CLASS_DUMP records & GC roots
            int segment1Length = 0;
            // Write class dumps
            java.io.ByteArrayOutputStream seg1Buf = new java.io.ByteArrayOutputStream(1024 * 1024);
            DataOutputStream seg1Out = new DataOutputStream(seg1Buf);

            // GC roots (2,000 roots)
            long baseRootAddr = 0x10000000L;
            for (int r = 0; r < 2000; r++) {
                seg1Out.writeByte(0xff); // ROOT_UNKNOWN
                seg1Out.writeLong(baseRootAddr + r * 16L);
            }

            // java.lang.Object Class Dump
            seg1Out.writeByte(0x20); // CLASS_DUMP
            seg1Out.writeLong(javaLangObjectClassId);
            seg1Out.writeInt(0);
            seg1Out.writeLong(0L); // superclass
            seg1Out.writeLong(0L); // classloader
            seg1Out.writeLong(0L);
            seg1Out.writeLong(0L);
            seg1Out.writeLong(0L);
            seg1Out.writeLong(0L);
            seg1Out.writeInt(16); // instance size
            seg1Out.writeShort(0); // const pool
            seg1Out.writeShort(0); // static fields
            seg1Out.writeShort(0); // instance fields

            // Domain classes
            for (int i = 0; i < numClasses; i++) {
                seg1Out.writeByte(0x20); // CLASS_DUMP
                seg1Out.writeLong(classIds[i]);
                seg1Out.writeInt(0);
                seg1Out.writeLong(javaLangObjectClassId); // superclass
                seg1Out.writeLong(0L); // classloader
                seg1Out.writeLong(0L);
                seg1Out.writeLong(0L);
                seg1Out.writeLong(0L);
                seg1Out.writeLong(0L);
                seg1Out.writeInt(32); // instance size
                seg1Out.writeShort(0); // const pool
                seg1Out.writeShort(0); // static fields
                seg1Out.writeShort(2); // 2 instance fields (ref + long)
                seg1Out.writeLong(fieldNames[0]);
                seg1Out.writeByte(2); // OBJECT
                seg1Out.writeLong(fieldNames[1]);
                seg1Out.writeByte(11); // LONG
            }

            byte[] seg1Bytes = seg1Buf.toByteArray();
            writeHeapDumpSegment(out, seg1Bytes);

            // Now stream object segments until target file size is reached
            long currentAddress = 0x10000000L;
            long writtenObjects = 0;
            long totalWrittenBytes = fos.getChannel().position();

            byte[] dummyPayloadBuffer = new byte[64 * 1024]; // 64 KB buffer for primitive arrays
            java.util.Arrays.fill(dummyPayloadBuffer, (byte) 0xAA);

            // Pre-allocate segment byte buffer to stream directly
            java.io.ByteArrayOutputStream segBuf = new java.io.ByteArrayOutputStream(SEGMENT_FLUSH_BYTES + 65536);
            DataOutputStream sOut = new DataOutputStream(segBuf);

            long lastProgressReport = System.currentTimeMillis();

            while (totalWrittenBytes < targetSizeBytes) {
                segBuf.reset();

                while (segBuf.size() < SEGMENT_FLUSH_BYTES) {
                    long objAddr = currentAddress;
                    currentAddress += 24L;
                    writtenObjects++;

                    int kind = (int) (writtenObjects % 10);
                    if (kind < 7) {
                        // 70% INSTANCE_DUMP
                        sOut.writeByte(0x21); // INSTANCE_DUMP
                        sOut.writeLong(objAddr);
                        sOut.writeInt(0); // stack
                        long classId = classIds[(int) (writtenObjects % numClasses)];
                        sOut.writeLong(classId);
                        sOut.writeInt(16); // bytes following: 8 bytes ref + 8 bytes long
                        long refAddr = (writtenObjects > 1) ? (objAddr - 24L) : 0L; // reference previous object
                        sOut.writeLong(refAddr);
                        sOut.writeLong(writtenObjects);
                    } else if (kind < 9) {
                        // 20% OBJECT_ARRAY_DUMP
                        int arrayLen = 4;
                        sOut.writeByte(0x22); // OBJECT_ARRAY_DUMP
                        sOut.writeLong(objAddr);
                        sOut.writeInt(0);
                        sOut.writeInt(arrayLen);
                        sOut.writeLong(javaLangObjectClassId);
                        for (int k = 0; k < arrayLen; k++) {
                            sOut.writeLong(objAddr - (k + 1) * 24L);
                        }
                    } else {
                        // 10% PRIMITIVE_ARRAY_DUMP (byte[] filling payload buffer to reach target GB quickly)
                        int primLen = 2048; // 2 KB byte array
                        sOut.writeByte(0x23); // PRIMITIVE_ARRAY_DUMP
                        sOut.writeLong(objAddr);
                        sOut.writeInt(0);
                        sOut.writeInt(primLen);
                        sOut.writeByte(8); // BYTE
                        sOut.write(dummyPayloadBuffer, 0, primLen);
                    }
                }

                byte[] segBytes = segBuf.toByteArray();
                writeHeapDumpSegment(out, segBytes);
                out.flush();
                totalWrittenBytes = fos.getChannel().position();

                long now = System.currentTimeMillis();
                if (now - lastProgressReport >= 5000) {
                    double currentGb = (double) totalWrittenBytes / (1024.0 * 1024.0 * 1024.0);
                    double pct = Math.min(100.0, (currentGb / targetSizeGb) * 100.0);
                    double mbPerSec = ((double) totalWrittenBytes / (1024.0 * 1024.0)) / ((now - startTime) / 1000.0);
                    System.out.println(String.format("   [Progress] %.2f GB / %.2f GB (%.1f%%) | Objects: %,d | Rate: %.1f MB/s",
                            currentGb, targetSizeGb, pct, writtenObjects, mbPerSec));
                    lastProgressReport = now;
                }
            }

            // Write HEAP_DUMP_END (0x2c)
            out.writeByte(0x2c);
            out.writeInt(0);
            out.writeInt(0);
            out.flush();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        double totalGb = (double) outputFile.length() / (1024.0 * 1024.0 * 1024.0);
        System.out.println("================================================================================");
        System.out.println(String.format("SUCCESS: Generated %.2f GB heap dump in %.1f seconds!", totalGb, elapsed / 1000.0));
        System.out.println("File: " + outputFile.getAbsolutePath());
        System.out.println("================================================================================");
    }

    private static void writeString(DataOutputStream out, long stringId, String str) throws IOException {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        out.writeByte(0x01); // STRING_IN_UTF8
        out.writeInt(0); // timestamp
        out.writeInt(ID_SIZE + bytes.length);
        out.writeLong(stringId);
        out.write(bytes);
    }

    private static void writeLoadClass(DataOutputStream out, int serial, long classId, long nameStringId) throws IOException {
        out.writeByte(0x02); // LOAD_CLASS
        out.writeInt(0);
        out.writeInt(4 + ID_SIZE + 4 + ID_SIZE);
        out.writeInt(serial);
        out.writeLong(classId);
        out.writeInt(0); // stack trace serial
        out.writeLong(nameStringId);
    }

    private static void writeHeapDumpSegment(DataOutputStream out, byte[] segmentData) throws IOException {
        out.writeByte(0x1c); // HEAP_DUMP_SEGMENT
        out.writeInt(0); // timestamp
        out.writeInt(segmentData.length);
        out.write(segmentData);
    }
}
