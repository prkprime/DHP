package org.eclipse.mat.dhp.parity;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.util.ConsoleProgressListener;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/**
 * Dumps a canonical per-object view of a snapshot opened through MAT's own API, so that a native
 * .hprof (MAT's parser and index files) and a DHP-backed .dhp can be compared with a plain diff.
 *
 * Usage: MemoryAnalyzer -application org.eclipse.mat.dhp.parity.dump &lt;snapshot&gt; &lt;out.tsv&gt; [sampleMod]
 * Line format (tab separated), written in object-ID order:
 *   address(16 hex) class shallow retained dominatorAddress(or -) outboundAddresses(sorted, comma separated)
 */
public class DumpApp implements IApplication {
    private long sampleMod = 1L;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        String[] args = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        if (args == null || args.length < 2) {
            System.err.println("Usage: -application org.eclipse.mat.dhp.parity.dump <snapshot> <out.tsv>");
            return Integer.valueOf(2);
        }
        File snapshotFile = new File(args[0]);
        // optional 3rd argument: sample only objects whose (address / 8) % mod == 0, to keep the output small on huge dumps
        sampleMod = args.length > 2 && !args[2].equals("loaders") ? Long.parseLong(args[2]) : 1L;
        ISnapshot snapshot = SnapshotFactory.openSnapshot(snapshotFile, Collections.emptyMap(),
                new ConsoleProgressListener(System.out));
        try {
            if (args.length > 2 && args[2].equals("loaders")) {
                // class loader instances found through class -> object IDs (what MAT's loader labelling relies on)
                java.util.Collection<org.eclipse.mat.snapshot.model.IClass> cl = snapshot.getClassesByName("java.lang.ClassLoader", true);
                try (java.io.PrintWriter w = new java.io.PrintWriter(args[1])) {
                    for (org.eclipse.mat.snapshot.model.IClass c : cl) {
                        w.println(c.getName() + "\tnumberOfObjects=" + c.getNumberOfObjects() + "\tobjectIds=" + c.getObjectIds().length);
                    }
                    w.println("numberOfClassLoaders=" + snapshot.getSnapshotInfo().getNumberOfClassLoaders());
                }
                return IApplication.EXIT_OK;
            }
            dump(snapshot, new File(args[1]));
        } finally {
            SnapshotFactory.dispose(snapshot);
        }
        return IApplication.EXIT_OK;
    }

    private static String hex(long address) {
        return String.format("%016x", address);
    }

    private void dump(ISnapshot s, File out) throws Exception {
        int n = s.getSnapshotInfo().getNumberOfObjects();
        System.out.println("PARITY objects=" + n);
        try (BufferedWriter w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
            org.eclipse.mat.util.IProgressListener quiet = new org.eclipse.mat.util.VoidProgressListener();
            for (int id = 0; id < n; id++) {
                if (sampleMod > 1 && ((s.mapIdToAddress(id) >>> 3) % sampleMod) != 0) continue;
                int[] outs = s.getOutboundReferentIds(new int[] { id }, quiet);
                long[] oa = new long[outs.length];
                for (int k = 0; k < oa.length; k++) oa[k] = s.mapIdToAddress(outs[k]);
                Arrays.sort(oa);
                StringBuilder sb = new StringBuilder(96);
                sb.append(hex(s.mapIdToAddress(id))).append('\t')
                  .append(s.getClassOf(id).getName()).append('\t')
                  .append(s.getHeapSize(id)).append('\t')
                  .append(s.getRetainedHeapSize(id)).append('\t');
                int dom = s.getImmediateDominatorId(id);
                sb.append(dom >= 0 ? hex(s.mapIdToAddress(dom)) : "-").append('\t');
                for (int k = 0; k < oa.length; k++) {
                    if (k > 0) sb.append(',');
                    sb.append(Long.toHexString(oa[k]));
                }
                // GC root types of this object (sorted), '-' when it is not a GC root
                org.eclipse.mat.snapshot.model.GCRootInfo[] roots = s.getGCRootInfo(id);
                sb.append('\t');
                if (roots == null || roots.length == 0) {
                    sb.append('-');
                } else {
                    // type:contextAddress (the thread of a stack-local root, 0 otherwise), sorted
                    String[] parts = new String[roots.length];
                    for (int k = 0; k < roots.length; k++) {
                        parts[k] = roots[k].getType() + ":" + Long.toHexString(roots[k].getContextAddress());
                    }
                    Arrays.sort(parts);
                    sb.append(String.join(",", parts));
                }
                w.write(sb.toString());
                w.newLine();
            }
        }
        System.out.println("PARITY written " + out);
    }

    @Override
    public void stop() { }
}
