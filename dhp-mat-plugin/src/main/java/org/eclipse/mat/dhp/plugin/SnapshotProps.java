package org.eclipse.mat.dhp.plugin;

import org.eclipse.mat.dhp.core.storage.HeapStorageEngine;
import org.eclipse.mat.parser.model.XSnapshotInfo;
import org.eclipse.mat.snapshot.UnreachableObjectsHistogram;

import java.util.ArrayList;
import java.util.List;

/** Snapshot properties that the HPROF parser of MAT provides and that MAT's queries and UI read. */
final class SnapshotProps {
    private SnapshotProps() {}

    static void apply(XSnapshotInfo info, HeapStorageEngine storage) {
        try {
            String created = storage.getSnapshotInfo("creationTime");
            if (created != null) {
                info.setCreationDate(new java.util.Date(Long.parseLong(created)));
            }
        } catch (Exception ignored) {
            // optional
        }
        try {
            String idSize = storage.getSnapshotInfo("idSize");
            String refSize = storage.getSnapshotInfo("refSize");
            if (idSize != null && refSize != null && Integer.parseInt(idSize) == 8) {
                // MAT sets this on 64-bit dumps to tell whether references are compressed
                info.setProperty("$useCompressedOops", Integer.parseInt(refSize) == 4);
            }
            String histogram = storage.getSnapshotInfo("unreachableHistogram");
            if (histogram != null && !histogram.isEmpty()) {
                List<UnreachableObjectsHistogram.Record> records = new ArrayList<>();
                for (String line : histogram.split("\n")) {
                    if (line.isEmpty()) continue;
                    String[] f = line.split(",", 4);
                    records.add(new UnreachableObjectsHistogram.Record(f[3], Long.parseLong(f[0]), Integer.parseInt(f[1]), Long.parseLong(f[2])));
                }
                info.setProperty(UnreachableObjectsHistogram.class.getName(), new UnreachableObjectsHistogram(records));
            }
        } catch (Exception ignored) {
            // properties are optional
        }
    }
}
