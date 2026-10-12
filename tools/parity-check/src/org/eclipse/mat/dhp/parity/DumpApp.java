package org.eclipse.mat.dhp.parity;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.query.IResultTree;
import org.eclipse.mat.query.IStructuredResult;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.FieldDescriptor;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.snapshot.model.IArray;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.snapshot.model.NamedReference;
import org.eclipse.mat.snapshot.model.ObjectReference;
import org.eclipse.mat.snapshot.query.SnapshotQuery;
import org.eclipse.mat.util.ConsoleProgressListener;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.VoidProgressListener;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Dumps canonical views of a snapshot opened through MAT's own API, so that a native .hprof (MAT's parser and index
 * files) and a DHP-backed .dhp can be compared with a plain diff. Usage:
 *
 * <pre>
 *   MemoryAnalyzer -application org.eclipse.mat.dhp.parity.dump SNAPSHOT OUT [MODE [ARGS]]
 *     (no mode) | N        per object: address class shallow retained dominator outbound roots; N = sample modulus
 *     loaders              class loader instances found through class -> object IDs
 *     inspect N            what the object inspector shows: names, class hierarchy, statics, fields, array content
 *     dominator            top level of the dominator tree and the order of the children
 *     queries FILE         run MAT commands / OQL ("oql: select ...") from FILE and dump the result tables
 * </pre>
 */
public class DumpApp implements IApplication {
    private long sampleMod = 1L;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        String[] args = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        if (args == null || args.length < 2) {
            System.err.println("Usage: -application org.eclipse.mat.dhp.parity.dump <snapshot> <out> [mode [args]]");
            return Integer.valueOf(2);
        }
        String mode = args.length > 2 ? args[2] : "objects";
        if (mode.matches("\\d+")) {
            sampleMod = Long.parseLong(mode);
            mode = "objects";
        }
        ISnapshot snapshot = SnapshotFactory.openSnapshot(new File(args[0]), Collections.emptyMap(),
                new ConsoleProgressListener(System.out));
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(new File(args[1]).toPath(), StandardCharsets.UTF_8))) {
            switch (mode) {
                case "objects" -> dumpObjects(snapshot, w);
                case "loaders" -> dumpLoaders(snapshot, w);
                case "classes" -> dumpClasses(snapshot, w);
                case "inspect" -> {
                    long mod = args.length > 3 ? Long.parseLong(args[3]) : 1000L;
                    inspect(snapshot, w, mod);
                }
                case "dominator" -> dominator(snapshot, w);
                case "listqueries" -> {
                    for (var d : org.eclipse.mat.query.registry.QueryRegistry.instance().getQueries()) {
                        w.println(d.getIdentifier() + "\t" + d.getName());
                    }
                }
                case "queries" -> queries(snapshot, w, new File(args[3]));
                default -> throw new IllegalArgumentException("unknown mode " + mode);
            }
            if (w.checkError()) {
                throw new IOException("output writer failed (unencodable characters?)");
            }
        } finally {
            SnapshotFactory.dispose(snapshot);
        }
        return IApplication.EXIT_OK;
    }

    private static String hex(long address) {
        return String.format("%016x", address);
    }

    // ------------------------------------------------------------------------------------------------ objects

    private void dumpObjects(ISnapshot s, PrintWriter w) throws Exception {
        int n = s.getSnapshotInfo().getNumberOfObjects();
        System.out.println("PARITY objects=" + n);
        IProgressListener quiet = new VoidProgressListener();
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
            GCRootInfo[] roots = s.getGCRootInfo(id);
            sb.append('\t');
            if (roots == null || roots.length == 0) {
                sb.append('-');
            } else {
                String[] parts = new String[roots.length];
                for (int k = 0; k < roots.length; k++) {
                    parts[k] = roots[k].getType() + ":" + Long.toHexString(roots[k].getContextAddress());
                }
                Arrays.sort(parts);
                sb.append(String.join(",", parts));
            }
            w.println(sb);
        }
        System.out.println("PARITY written objects");
    }

    /** All classes in the order MAT enumerates them (hash map order), with their ids. */
    private void dumpClasses(ISnapshot snapshot, PrintWriter w) throws Exception {
        for (IClass c : snapshot.getClasses()) {
            w.println(c.getObjectId() + "\t" + c.getName() + "\t" + c.getNumberOfObjects());
        }
    }

    private void dumpLoaders(ISnapshot snapshot, PrintWriter w) throws Exception {
        for (IClass c : snapshot.getClassesByName("java.lang.ClassLoader", true)) {
            w.println(c.getName() + "\tnumberOfObjects=" + c.getNumberOfObjects() + "\tobjectIds=" + c.getObjectIds().length);
        }
        w.println("numberOfClassLoaders=" + snapshot.getSnapshotInfo().getNumberOfClassLoaders());
    }

    // ------------------------------------------------------------------------------------------------ inspector

    /** Printable form: control characters and (unpaired) surrogates are escaped so that the UTF-8 output never fails. */
    private static String esc(String t) {
        StringBuilder sb = null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean bad = c < 32 || c == 127 || Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < t.length() && Character.isLowSurrogate(t.charAt(i + 1)))
                    && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(t.charAt(i - 1)));
            if (bad && sb == null) {
                sb = new StringBuilder(t.length() + 8).append(t, 0, i);
            }
            if (sb != null) {
                if (bad) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb == null ? t : sb.toString();
    }

    private static String value(Object v) {
        if (v == null) return "null";
        if (v instanceof ObjectReference r) return "ref@" + Long.toHexString(r.getObjectAddress());
        if (v instanceof Character c) return "Character:U+" + String.format("%04x", (int) c.charValue());
        return v.getClass().getSimpleName() + ":" + esc(String.valueOf(v));
    }

    private static String fields(List<Field> list) {
        StringBuilder sb = new StringBuilder();
        for (Field f : list) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(f.getName()).append(':').append(f.getType()).append(':').append(f.getVerboseSignature()).append('=').append(value(f.getValue()));
        }
        return sb.toString();
    }

    private static String descriptors(List<FieldDescriptor> list) {
        StringBuilder sb = new StringBuilder();
        for (FieldDescriptor f : list) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(f.getName()).append(':').append(f.getType()).append(':').append(f.getVerboseSignature());
        }
        return sb.toString();
    }

    private void describe(ISnapshot s, int id, PrintWriter w) throws Exception {
        IObject o = s.getObject(id);
        StringBuilder sb = new StringBuilder();
        String kind = o instanceof IClass ? "CLASS" : o instanceof IPrimitiveArray ? "PARRAY" : o instanceof IObjectArray ? "OARRAY"
                : o instanceof IInstance ? "INSTANCE" : o.getClass().getSimpleName();
        sb.append("OBJECT ").append(id).append(' ').append(hex(o.getObjectAddress())).append(' ').append(kind).append('\n');
        sb.append("  class=").append(o.getClazz().getName()).append(" classId=").append(o.getClazz().getObjectId()).append('\n');
        sb.append("  display=").append(o.getDisplayName()).append('\n');
        sb.append("  technical=").append(o.getTechnicalName()).append('\n');
        sb.append("  classSpecific=").append(o.getClassSpecificName()).append('\n');
        sb.append("  shallow=").append(o.getUsedHeapSize()).append(" retained=").append(o.getRetainedHeapSize()).append('\n');
        if (o instanceof IClass c) {
            sb.append("  name=").append(c.getName()).append(" array=").append(c.isArrayType()).append(" objects=").append(c.getNumberOfObjects())
              .append(" perInstance=").append(c.getHeapSizePerInstance()).append(" loaderId=").append(c.getClassLoaderId())
              .append(" superId=").append(c.getSuperClassId()).append('\n');
            sb.append("  hierarchy=");
            for (IClass p = c; p != null; p = p.getSuperClass()) sb.append(p.getName()).append(" > ");
            sb.append('\n');
            List<String> subs = new ArrayList<>();
            for (IClass sc : c.getSubclasses()) subs.add(sc.getName());
            Collections.sort(subs);
            sb.append("  subclasses=").append(subs).append(" all=").append(c.getAllSubclasses().size()).append('\n');
            sb.append("  fieldDescriptors=").append(descriptors(c.getFieldDescriptors())).append('\n');
            sb.append("  statics=").append(fields(c.getStaticFields())).append('\n');
        } else if (o instanceof IInstance i) {
            sb.append("  hierarchy=");
            for (IClass p = o.getClazz(); p != null; p = p.getSuperClass()) sb.append(p.getName()).append(" > ");
            sb.append('\n');
            sb.append("  fields=").append(fields(i.getFields())).append('\n');
        } else if (o instanceof IPrimitiveArray a) {
            sb.append("  length=").append(a.getLength()).append(" type=").append(a.getType()).append(" values=");
            int max = Math.min(a.getLength(), 16);
            for (int k = 0; k < max; k++) sb.append(a.getValueAt(k)).append(',');
            sb.append('\n');
        } else if (o instanceof IObjectArray a) {
            sb.append("  length=").append(a.getLength()).append(" refs=");
            long[] refs = a.getReferenceArray(0, Math.min(a.getLength(), 16));
            for (long r : refs) sb.append(Long.toHexString(r)).append(',');
            sb.append('\n');
        }
        sb.append("  outbound=");
        List<NamedReference> out = o.getOutboundReferences();
        int shown = 0;
        for (NamedReference r : out) {
            if (shown++ >= 40) { sb.append("..(").append(out.size()).append(')'); break; }
            sb.append(r.getName()).append("->").append(r.getObjectId()).append(' ');
        }
        sb.append('\n');
        GCRootInfo[] roots = o.getGCRootInfo();
        sb.append("  roots=");
        if (roots != null) for (GCRootInfo g : roots) sb.append(g.getType()).append(':').append(g.getContextId()).append(' ');
        sb.append('\n');
        w.print(sb);
    }

    private void inspect(ISnapshot s, PrintWriter w, long mod) throws Exception {
        // every class object (hierarchy, statics, fields), every class loader and thread, plus a sample of the rest
        List<Integer> ids = new ArrayList<>();
        for (IClass c : s.getClasses()) ids.add(c.getObjectId());
        Collections.sort(ids);
        for (int id : ids) describe(s, id, w);
        int n = s.getSnapshotInfo().getNumberOfObjects();
        for (int id = 0; id < n; id++) {
            long a = s.mapIdToAddress(id);
            if (((a >>> 3) % mod) != 0) continue;
            if (s.getObject(id) instanceof IClass) continue;
            describe(s, id, w);
        }
        for (String cn : new String[]{"java.lang.ClassLoader", "java.lang.Thread"}) {
            Collection<IClass> cl = s.getClassesByName(cn, true);
            if (cl == null) continue;
            List<Integer> inst = new ArrayList<>();
            for (IClass c : cl) for (int id : c.getObjectIds()) inst.add(id);
            Collections.sort(inst);
            for (int id : inst) describe(s, id, w);
        }
    }

    // ------------------------------------------------------------------------------------------------ dominator tree

    private void node(ISnapshot s, int id, String indent, PrintWriter w) throws Exception {
        w.println(indent + id + "\t" + hex(s.mapIdToAddress(id)) + "\t" + s.getClassOf(id).getName() + "\t" + s.getHeapSize(id) + "\t" + s.getRetainedHeapSize(id));
    }

    private void dominator(ISnapshot s, PrintWriter w) throws Exception {
        int[] top = s.getImmediateDominatedIds(-1);
        Integer[] boxed = Arrays.stream(top).boxed().toArray(Integer[]::new);
        Arrays.sort(boxed, Comparator.comparingLong((Integer i) -> -safeRetained(s, i)).thenComparingInt(i -> i));
        w.println("TOP " + top.length);
        for (int rank = 0; rank < Math.min(boxed.length, 200); rank++) {
            int id = boxed[rank];
            node(s, id, "", w);
            int[] kids = s.getImmediateDominatedIds(id);
            if (kids == null) continue;
            w.println("  children=" + kids.length);
            for (int k = 0; k < Math.min(kids.length, 40); k++) {
                node(s, kids[k], "    ", w);
                if (rank < 10 && k < 5) {
                    int[] gk = s.getImmediateDominatedIds(kids[k]);
                    if (gk != null) {
                        for (int g = 0; g < Math.min(gk.length, 10); g++) node(s, gk[g], "        ", w);
                    }
                }
            }
        }
    }

    private static long safeRetained(ISnapshot s, int id) {
        try {
            return s.getRetainedHeapSize(id);
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------------------------------------ queries

    private static String cell(Object v) {
        if (v == null) return "null";
        String t = String.valueOf(v);
        return esc(t.replace('\n', ' ').replace('\t', ' '));
    }

    private static final int MAX_ROWS = 400;

    private void dumpTable(IStructuredResult table, int rows, PrintWriter w, String indent, Object[] rowObjects) {
        Column[] cols = table.getColumns();
        for (int r = 0; r < Math.min(rows, MAX_ROWS); r++) {
            Object row = rowObjects[r];
            StringBuilder sb = new StringBuilder(indent);
            for (int c = 0; c < cols.length; c++) {
                if (c > 0) sb.append('\t');
                sb.append(cell(table.getColumnValue(row, c)));
            }
            w.println(sb);
        }
    }

    private void dumpTree(IResultTree tree, List<?> elements, int depth, PrintWriter w, String indent) {
        Column[] cols = tree.getColumns();
        int shown = 0;
        for (Object e : elements) {
            if (shown++ >= 60) {
                w.println(indent + "..");
                break;
            }
            StringBuilder sb = new StringBuilder(indent);
            for (int c = 0; c < cols.length; c++) {
                if (c > 0) sb.append('\t');
                sb.append(cell(tree.getColumnValue(e, c)));
            }
            w.println(sb);
            if (depth > 0 && tree.hasChildren(e)) {
                dumpTree(tree, tree.getChildren(e), depth - 1, w, indent + "  ");
            }
        }
    }

    private void printResult(IResult result, PrintWriter w) {
        if (result instanceof IResultTable t) {
            Column[] cols = t.getColumns();
            StringBuilder h = new StringBuilder("COLUMNS");
            for (Column c : cols) h.append('\t').append(c.getLabel());
            w.println(h);
            int rows = t.getRowCount();
            w.println("ROWS " + rows);
            Object[] rowObjects = new Object[Math.min(rows, MAX_ROWS)];
            for (int i = 0; i < rowObjects.length; i++) rowObjects[i] = t.getRow(i);
            dumpTable(t, rows, w, "", rowObjects);
        } else if (result instanceof IResultTree t) {
            Column[] cols = t.getColumns();
            StringBuilder h = new StringBuilder("COLUMNS");
            for (Column c : cols) h.append('\t').append(c.getLabel());
            w.println(h);
            dumpTree(t, t.getElements(), 2, w, "");
        } else if (result instanceof org.eclipse.mat.query.results.TextResult t) {
            w.println("TEXT " + cell(t.getText()));
        } else if (result instanceof org.eclipse.mat.query.results.CompositeResult comp) {
            for (var item : comp.getResultEntries()) {
                w.println("COMPOSITE " + item.getName());
                printResult(item.getResult(), w);
            }
        } else if (result != null) {
            w.println("RESULT " + result.getClass().getSimpleName() + " " + cell(result));
        } else {
            w.println("RESULT null");
        }
    }

    private void queries(ISnapshot s, PrintWriter w, File file) throws Exception {
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            w.println("QUERY " + line);
            try {
                SnapshotQuery q = line.startsWith("oql:")
                        ? SnapshotQuery.lookup("oql", s).setArgument("queryString", line.substring(4).strip())
                        : SnapshotQuery.parse(line, s);
                printResult(q.execute(new VoidProgressListener()), w);
            } catch (Throwable t) {
                w.println("ERROR " + t.getClass().getSimpleName() + ": " + cell(t.getMessage()));
            }
            w.println("END");
            w.flush();
        }
    }

    @Override
    public void stop() { }
}
