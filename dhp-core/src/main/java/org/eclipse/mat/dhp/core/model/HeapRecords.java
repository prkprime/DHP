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
            List<StaticFieldRecord> staticFields
    ) {}

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
            long objectAddress,
            long referrerAddress,
            int rootType,
            long threadAddress
    ) {}

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
