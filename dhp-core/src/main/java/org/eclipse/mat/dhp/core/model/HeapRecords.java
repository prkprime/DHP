package org.eclipse.mat.dhp.core.model;

import java.util.List;

/**
 * Immutable records representing core heap entities parsed from HPROF.
 */
public final class HeapRecords {
    private HeapRecords() {}

    public record Header(
            String version,
            int idSize,
            long creationTime
    ) {}

    public record FieldDescriptor(
            String name,
            int type
    ) {}

    public record ClassRecord(
            long classId,
            long superClassId,
            long classLoaderId,
            String name,
            int instanceSize,
            List<FieldDescriptor> fields,
            List<StaticFieldRecord> staticFields,
            int classObjId,
            int superClassObjId,
            int classLoaderObjId,
            long usedSize
    ) {
        public ClassRecord(long classId, long superClassId, long classLoaderId, String name, int instanceSize, List<FieldDescriptor> fields, List<StaticFieldRecord> staticFields) {
            this(classId, superClassId, classLoaderId, name, instanceSize, fields, staticFields, -1, -1, -1, 0L);
        }
    }

    public record StaticFieldRecord(
            String name,
            int type,
            Object value
    ) {}

    public record InstanceRecord(
            long address,
            long classId,
            byte[] instanceData,
            long filePosition
    ) {}

    public record ObjectArrayRecord(
            long address,
            long classId,
            int length,
            long[] elements,
            long filePosition
    ) {}

    public record PrimitiveArrayRecord(
            long address,
            int elementType,
            int length,
            long filePosition,
            long calculatedSize
    ) {}

    public record GcRootRecord(
            int objectId,
            long objectAddress,
            long referrerAddress,
            int rootType,
            long threadAddress,
            int threadObjectId
    ) {
        public GcRootRecord(long objectAddress, long referrerAddress, int rootType, long threadAddress) {
            this(-1, objectAddress, referrerAddress, rootType, threadAddress, -1);
        }
    }

    public record StackFrameRecord(
            long frameId,
            String methodName,
            String methodSignature,
            String sourceFile,
            long classSerialNumber,
            int lineNumber
    ) {}

    public record StackTraceRecord(
            long threadSerialNumber,
            long[] frameIds
    ) {}
}
