package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.snapshot.model.GCRootInfo;

/** Maps the HPROF root sub-record tags stored by DHP to Eclipse MAT's {@link GCRootInfo.Type} constants. */
final class RootTypes {
    private RootTypes() {}

    static int toMat(int hprofTag) {
        return switch (hprofTag) {
            case 0x01 -> GCRootInfo.Type.NATIVE_STATIC;   // ROOT_JNI_GLOBAL
            case 0x02 -> GCRootInfo.Type.NATIVE_LOCAL;    // ROOT_JNI_LOCAL
            case 0x03 -> GCRootInfo.Type.JAVA_LOCAL;      // ROOT_JAVA_FRAME
            case 0x04 -> GCRootInfo.Type.NATIVE_STACK;    // ROOT_NATIVE_STACK
            case 0x05 -> GCRootInfo.Type.SYSTEM_CLASS;    // ROOT_STICKY_CLASS
            case 0x06 -> GCRootInfo.Type.THREAD_BLOCK;    // ROOT_THREAD_BLOCK
            case 0x07 -> GCRootInfo.Type.BUSY_MONITOR;    // ROOT_MONITOR_USED
            case 0x08 -> GCRootInfo.Type.THREAD_OBJ;      // ROOT_THREAD_OBJECT
            case 0x1000 -> GCRootInfo.Type.UNREACHABLE;   // artificial root for an unreachable object
            default -> GCRootInfo.Type.UNKNOWN;           // ROOT_UNKNOWN (0xFF) and anything else
        };
    }
}
