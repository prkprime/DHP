# Dynamic Heap Parser (DHP)

[![CI](https://github.com/prkprime/DHP/actions/workflows/ci.yml/badge.svg)](https://github.com/prkprime/DHP/actions/workflows/ci.yml)
[![License: EPL-2.0](https://img.shields.io/badge/License-EPL%202.0-blue.svg)](https://opensource.org/licenses/EPL-2.0)
[![Java: 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)

> **A low-memory HPROF ingestion and graph engine that plugs into Eclipse Memory Analyzer (MAT).**
> Parse a 10 GB heap dump in about a minute with a 4 GB JVM, then open it in the normal MAT UI and get the same answers
> as MAT's own parser, at the same speed or faster.

- [Why DHP](#why-dhp)
- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Command line reference](#command-line-reference)
- [Files DHP creates](#files-dhp-creates)
- [Using DHP with Eclipse MAT](#using-dhp-with-eclipse-mat)
- [Sizing and memory](#sizing-and-memory)
- [Generating test dumps](#generating-test-dumps)
- [Verifying parity with native MAT](#verifying-parity-with-native-mat)
- [Performance](#performance)
- [Memory-bounded internals](#memory-bounded-internals)
- [Building, testing, CI](#building-testing-ci)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [License](#license)

---

## Why DHP

Eclipse MAT builds a set of index files (`.idx`, `.o2c`, `.inbound`, `.outbound`, `.domIn`, ...) when it opens a heap dump.
Building them needs memory in proportion to the dump, so analysing a 10-100 GB dump on a laptop or a small server
means a large machine, a long wait, or an out-of-memory error.

DHP replaces that step. It streams the dump once, keeps only a compact object graph in memory (about 55 bytes per
object, never the array payloads), computes the dominator tree and retained sizes, and stores the result in a
database next to the dump. MAT then opens the snapshot through a small plugin. Field values and array contents are
read straight from the original `.hprof` when you look at an object.

| | Native MAT | DHP |
|---|---|---|
| Heap used to parse a 10.7 GB dump (51.9M objects) | 14 GB (`-Xmx14g`) | **4 GB** (3 GB also works) |
| Parse time, same dump | 142 s | **70 s** |
| Same answers as native MAT | n/a | **yes**: object ids, sizes, retained sizes, dominators, references, GC roots, reports, OQL, histogram order (verified, see [Verifying parity](#verifying-parity-with-native-mat)) |
| 98 MAT commands/OQL queries on a 4 GB dump | 285 s | **221 s** |

---

## How it works

```
                    ┌────────────────────────────────────────┐
                    │              HPROF dump                │   never modified
                    └───────────────────┬────────────────────┘
                                        │
                       Pass 1: scan strings, classes, GC roots,
                       thread stacks; collect and sort all addresses
                                        │
                       Pass 2: stream every object into an
                       in-memory HeapGraph (columns + edge log)
                                        │
                       remove unreachable objects (MAT default),
                       renumber ids exactly like MAT
                                        │
                                        ▼
                    ┌────────────────────────────────────────┐
                    │  Dominator tree (Lengauer-Tarjan) and  │
                    │  retained sizes, MAT-exact child order │
                    └───────────────────┬────────────────────┘
                                        │
                                        ▼
        ┌─────────────────────────┐  ┌──────────────────────────┐
        │ heap.dhp.db   (SQLite)  │  │ heap.dhp.db.chunks       │
        │ classes, roots, stats   │  │ per-object columns, edge │
        │ ~2 MB                   │  │ lists, dominator data    │
        └─────────────────────────┘  └──────────────────────────┘
                                        │
                    ┌───────────────────▼────────────────────┐
                    │  Eclipse MAT + DHP plugin (heap.dhp)   │
                    │  index readers answer MAT's lookups    │
                    │  from the chunk file; object contents  │
                    │  come from the .hprof                  │
                    └────────────────────────────────────────┘
```

1. **Pass 1** scans the whole dump once: string table, class definitions, GC roots, thread stacks. It detects
   compressed object pointers and the object alignment like MAT does, and collects every object address.
2. **Pass 2** reads all instance and array records and builds a `HeapGraph` in memory: primitive per-object columns
   (class, shallow size, file position) and one compact edge log. Unreachable objects are removed and the ids are
   renumbered the way MAT's garbage cleaner does it, so ids match MAT's.
3. **Dominator tree**: Lengauer-Tarjan from an artificial root, retained sizes bottom-up, dominated lists in the
   order MAT produces them.
4. **Storage**: everything per object is packed into fixed-size chunks (512 objects for scalar columns, 32 objects
   for edge lists) and written to an append-only, memory-mapped chunk file. A SQLite database next to it holds the
   metadata (classes, GC roots, statistics). With PostgreSQL the chunks live in a table instead.
5. **MAT plugin**: opening `heap.dhp` builds MAT's snapshot object from the database in a fraction of a second.
   Every MAT index lookup (address, class, outbound, inbound, dominator, retained size) is a directory lookup plus a
   memory copy from the chunk file.

### Modules

| Module | What it contains |
| :--- | :--- |
| `dhp-core` | HPROF reader, `Pass1ScanParser`, `Pass2ObjectIngester`, `HeapGraph`, `DominatorTreeEngine`, `UnreachableRoots`, `MatSort`, memory-bounded address maps and membership sets. No JDBC. |
| `dhp-storage-jdbc` | `HeapStorageEngine` implementation for SQLite and PostgreSQL, the memory-mapped `ChunkFile`. |
| `dhp-cli` | The `dhp` command (picocli), shaded into one jar. |
| `dhp-mat-plugin` | The OSGi bundle for MAT: snapshot factory, object reader, index adapters (`DbOne2*Index`). |
| `tools/` | Dump generators, the native-vs-DHP parity checker, and a MAT copy for headless runs. |

---

## Quick start

**Requirements:** JDK 21+, Maven 3.9+, `curl` and `unzip` (or PowerShell on Windows). Docker is only needed for the
optional PostgreSQL tests.

### 1. Install everything (one command)

```bash
git clone https://github.com/prkprime/DHP.git
cd DHP
./scripts/setup-all.sh          # Windows: scripts\setup-all.bat  or  .\scripts\setup-all.ps1
```

This installs the MAT API jars into your local Maven repository, builds the project, downloads Eclipse MAT into
`tools/mat/mat` and installs the DHP plugin into it. The MAT version comes from `mat.version` in the root `pom.xml`.

### 2. Parse a dump

```bash
java -Xmx4g -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar --dump /data/heap.hprof
```

```
[Phase 1/3] Scanning HPROF structure, classes, and GC roots...
   Parsed 13,661 strings, 5,779 classes, 4,490,072 unique objects, 4,083 GC roots (1.32 s).
[Phase 2/3] Streaming object instances & references into database...
   Ingested & indexed 4,338,915 reachable heap objects (3.47 s).
[Phase 3/3] Calculating Dominator Tree & Retained Sizes...
   Dominator tree and retained sizes computed (2.58 s).
================================================================================
DHP Parsing successfully finished in 7.63 seconds!
   Phase 1 (Scan & Discovery):   1.32 s
   Phase 2 (Ingest & Indexes):   3.47 s
   Phase 3 (Dominator Analysis): 2.58 s
================================================================================
Exported MAT DHP descriptor: /data/heap.dhp
Ready to open with Eclipse MAT using DHP plugin!
```

(That output is from a 212 MB dump with 4.5M objects at `-Xmx2g`.)

Next to the dump you now have:

```
/data/heap.hprof            the original dump (never modified, still needed by MAT)
/data/heap.dhp              descriptor: tells MAT where the database and the dump are
/data/heap.dhp.db           SQLite database: classes, GC roots, statistics (about 2 MB)
/data/heap.dhp.db.chunks    object graph and dominator tree (about 80 bytes per object, 347 MB for the dump above)
/data/heap.threads          thread stacks (read by MAT)
```

### 3. Open it in MAT

```bash
./tools/mat/mat/MemoryAnalyzer            # File -> Open Heap Dump... -> /data/heap.dhp
```

Or generate the standard reports without a GUI:

```bash
./scripts/run-mat-headless.sh /data/heap.dhp org.eclipse.mat.api:suspects org.eclipse.mat.api:overview
# -> /data/heap_Leak_Suspects.zip, /data/heap_System_Overview.zip
```

### All scripts

Every script exists as `.sh`, `.bat` and `.ps1` with the same behaviour.

| Task | Script |
| :--- | :--- |
| Everything: dependencies, build, MAT download, plugin install | `scripts/setup-all` |
| Install the MAT API jars into `~/.m2` (extracted from a MAT download) | `scripts/setup-deps` |
| Download and unpack Eclipse MAT into `tools/mat/mat` (`MAT_HOME` overrides) | `scripts/setup-mat` |
| Install the built DHP bundle into MAT and clear its OSGi cache | `scripts/install-mat-plugin` |
| Install a p2 feature into MAT (for example the chart feature) | `scripts/install-p2-dependency <update-site> <feature>` |
| Run MAT reports headless on a `.dhp` | `scripts/run-mat-headless <file.dhp> [report-id ...]` |

---

## Command line reference

```
java -jar dhp-cli-1.0.0-SNAPSHOT.jar [options]
```

| Option | Description |
| :--- | :--- |
| `-d`, `--dump <file>` | The `.hprof` file to ingest (required unless given in `--config`). `.gz` files are rejected, see [limitations](#known-limitations). |
| `-c`, `--config <file>` | Read defaults from a descriptor/properties file (`db.url`, `db.user`, `db.password`, `dump.file`, `memory.budget`, `worker.threads`). Options on the command line win. |
| `--url`, `--db-url`, `--jdbcurl <url>` | JDBC URL. Default: `jdbc:sqlite:<dump>.dhp.db` next to the dump. |
| `--db <name or url>` | Database name (builds a PostgreSQL URL together with `--host`/`--port`) or a full JDBC URL. |
| `--host <host>`, `--port <port>` | PostgreSQL host and port (default 5432), used with `--db <name>`. |
| `-u`, `--user`, `-p`, `--password` | Database credentials. |
| `-m`, `--memory`, `--memory-budget <size>` | Memory budget for the governor: `512M`, `2G`, `4GB` or bytes. Default 75% of `-Xmx`. It steers batch sizes and the address-map strategy; the heap limit is `-Xmx`. |
| `-t`, `--threads <n>` | Threads for the parallel phases (sorting, chunk building, dominator ordering). Default: CPU cores. |
| `--clean`, `--drop-existing` | Drop an existing DHP database (and its `.chunks` file) before ingesting. Without it, ingesting into a database that already holds a snapshot is refused. |
| `-e`, `--estimate` | Scan the dump (Pass 1 only) and print the heap and disk needed, then exit. |
| `--keep-unreachable` | Keep objects that no GC root reaches. By default they are removed like MAT does (`keep_unreachable_objects` off). |
| `--export-dhp <file>` | Where to write the MAT descriptor (default `<dump>.dhp`). |
| `--monitor` | Print JVM heap usage every 3 s and the peak at the end. |
| `--address-map auto\|sorted\|chunked\|mmap` | Strategy for the address-to-id map, see [internals](#memory-bounded-internals). |
| `--membership-set bitset\|roaring` | Structure that tracks written objects. |
| `-h`, `--help`, `-V`, `--version` | Help and version. |

Exit codes: `0` success, `1` invalid arguments or the target database already holds a snapshot, `2` ingestion
succeeded but the `.dhp` descriptor could not be written.

### Examples

```bash
# Ask first: how much heap and disk does this dump need?
java -jar dhp-cli.jar --estimate -d heap.hprof

# Typical run with a memory monitor and an explicit thread count
java -Xmx4g -jar dhp-cli.jar -d heap.hprof --monitor --threads 8

# Re-ingest after a failed or outdated run
java -Xmx4g -jar dhp-cli.jar -d heap.hprof --clean

# Keep the database somewhere else (descriptor is written next to the dump)
java -Xmx4g -jar dhp-cli.jar -d heap.hprof --url jdbc:sqlite:/fast-disk/heap.dhp.db

# Reproduce MAT's "keep unreachable objects" mode
java -Xmx4g -jar dhp-cli.jar -d heap.hprof --keep-unreachable

# PostgreSQL
java -Xmx4g -jar dhp-cli.jar -d heap.hprof \
  --url jdbc:postgresql://localhost:5432/heapdb -u dhp -p dhppass
```

`--estimate` prints, for example:

```
*                   DHP PRE-FLIGHT RESOURCE ESTIMATE                           *
* Discovered Objects:       4,490,072
* HPROF Dump File Size:     202.2 MB
* Estimated Peak JVM Heap:  356.5 MB
* RECOMMENDED MINIMUM HEAP: -Xmx512m (with 35% safety headroom)
* RECOMMENDED FREE DISK:    683.7 MB (chunked database)
```

---

## Files DHP creates

For `heap.hprof` DHP writes (all next to the dump unless you pass `--url` / `--export-dhp`):

| File | Purpose | Notes |
| :--- | :--- | :--- |
| `heap.dhp` | Descriptor that MAT opens | Plain properties file, see below. |
| `heap.dhp.db` | SQLite database | Classes, GC roots, snapshot info and statistics (`dhp_*` tables). A few MB. |
| `heap.dhp.db.chunks` | Object graph and dominator data | Append-only chunk file, memory-mapped by readers. **Must stay next to the `.db`**: the database records an id of the chunk file and refuses a mismatching copy. |
| `heap.threads` | Thread stacks | Written next to the dump, MAT reads `<prefix>threads`. |
| `heap.hprof` | The dump | Never modified; MAT reads object contents from it. |

With PostgreSQL there is no `.chunks` file; the chunks are stored in the `dhp_chunks` table.

### Descriptor (`.dhp`)

```properties
db.url=jdbc:sqlite:/data/heap.dhp.db
dump.file=/data/heap.hprof
memory.budget=4294967296
```

| Key | Meaning |
| :--- | :--- |
| `db.url` | JDBC URL. Relative SQLite paths are resolved against the folder of the descriptor. Default: `<name>.dhp.db` next to the descriptor. |
| `db.user`, `db.password` | Credentials (PostgreSQL). Keep such a descriptor private. |
| `dump.file` | The HPROF file. Relative paths are resolved against the descriptor's folder. If the database is empty, MAT ingests this file on open. |
| `memory.budget` | Budget used when MAT has to ingest on open (`4G`, bytes, ...). Default 1 GB. |

The paths the CLI writes are absolute with forward slashes, so MAT can be started from any directory on any OS. To move
a snapshot, copy `heap.dhp`, `heap.dhp.db`, `heap.dhp.db.chunks`, `heap.threads` and `heap.hprof` together and use
relative paths in the descriptor:

```properties
db.url=jdbc:sqlite:heap.dhp.db
dump.file=heap.hprof
```

---

## Using DHP with Eclipse MAT

DHP registers the `.dhp` extension, so MAT's own `.hprof` handling and its index files stay untouched.

**GUI:** `File -> Open Heap Dump...`, choose `heap.dhp`. The snapshot opens quickly because MAT's snapshot object is built from the database and no index files are generated; everything MAT
shows (histogram, dominator tree, inspector with class hierarchy, statics and field values, thread overview, OQL,
leak suspects, ...) is computed by MAT's own code on top of DHP's index readers.

**Headless:**

```bash
./scripts/run-mat-headless.sh heap.dhp org.eclipse.mat.api:suspects
./scripts/run-mat-headless.sh heap.dhp org.eclipse.mat.api:suspects org.eclipse.mat.api:overview org.eclipse.mat.api:top_components
```

While reports render you will see `Task: Writing HTML files`: that is MAT writing the drill-down pages, not a hang.

**Installing the plugin into an existing MAT** (the scripts do this for `tools/mat/mat`):

```bash
mvn package -pl dhp-mat-plugin -am -DskipTests
cp dhp-mat-plugin/target/dhp-mat-plugin-1.0.0-SNAPSHOT.jar /path/to/mat/plugins/org.eclipse.mat.dhp_1.0.0.SNAPSHOT.jar
echo 'org.eclipse.mat.dhp,1.0.0.SNAPSHOT,plugins/org.eclipse.mat.dhp_1.0.0.SNAPSHOT.jar,4,true' \
  >> /path/to/mat/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info
rm -rf /path/to/mat/configuration/org.eclipse.osgi
/path/to/mat/MemoryAnalyzer -clean
```

**What matches native MAT:** object ids, shallow and retained sizes, dominators and their order, outbound/inbound
references, GC roots and the `<Java Local>` references of threads, thread stacks, class-loader labels, class and
histogram order, OQL results and row order, inspector contents, and the standard reports. By default unreachable
objects are removed (as in MAT); `--keep-unreachable` keeps them as `UNREACHABLE` roots exactly like MAT's
`keep_unreachable_objects`.

---

## Sizing and memory

DHP's heap use follows the **number of objects**, not the size of the file (primitive-array payloads are never loaded).

| Dump | Objects | `-Xmx` that works | Ingest time |
| :--- | ---: | :--- | ---: |
| 4.0 GB complex synthetic dump | 113M | 4 GB | 36-44 s |
| 10.7 GB real HotSpot dump | 51.9M | 4 GB (3 GB also works, 2.5 GB fails) | 70 s |
| 212 MB synthetic dump | 4.5M | 2 GB used here; `--estimate` recommends 512 MB | 8-12 s |

Rules of thumb:

- Heap: about **55 bytes per object** plus a few hundred MB. 100M objects need about 5.5 GB.
- Disk: `--estimate` prints the number (it assumes up to about 135 bytes per object; the chunk file of the 212 MB dump above is about 80 bytes per object).
- Run `--estimate` first on a dump you have not seen. It does the 1-2 s per GB Pass 1 only.
- If you run out of memory anyway: raise `-Xmx`, try `--address-map mmap` (moves the sorted address array off the heap),
  or `--membership-set roaring`.

Reading is cheap: opening a snapshot in MAT needs the heap MAT itself wants for its UI and caches (the chunk file is
memory-mapped, so it lives in the page cache, not on the heap).

---

## Generating test dumps

Everything runs as a Java 21 single-file program, no compilation needed. All dumps below are written by real JVMs or
by generators that emit authentic HPROF records.

| Generator | What it makes | Example |
| :--- | :--- | :--- |
| `ComplexJvmHeapDumpGenerator` | **Best for testing**: a real JVM builds a very varied object graph (generated classes in several class loaders, JDK collections, strings in all encodings, enums, records, lambdas, proxies, weak/soft/phantom references, cycles, deep threads with locals, garbage) and HotSpot writes the dump. | `java -Xmx7g -XX:+UseParallelGC tools/dump-generator/ComplexJvmHeapDumpGenerator.java out.hprof 3.6 7 all 6000` (live GB, seed, `live`\|`all`, optional payload bytes per object) |
| `RealHeapDumpGenerator` | A business-like object graph (customers, orders, caches) in a real JVM, sized by live GB. | `java -Xmx13g -XX:+UseParallelGC tools/dump-generator/RealHeapDumpGenerator.java out.hprof 10.5` |
| `ComplexHeapDumpGenerator` | Synthetic HPROF with exact dominator buckets, diamond DAGs, cycles and multi-level hierarchies (~2.4 GB, or scaled). | `java -Xmx1g tools/dump-generator/ComplexHeapDumpGenerator.java out.hprof 0.05` (about 140 MB) |
| `StreamingHeapDumpGenerator` | Constant-memory generator for very large dumps (11 GB and up). | `java tools/dump-generator/StreamingHeapDumpGenerator.java out.hprof 11 20` (size GB, million objects) |

---

## Verifying parity with native MAT

`tools/parity-check` runs the **real MAT parser** and DHP on the same dump and compares what MAT's own API reports.

```bash
tools/parity-check/build.sh                              # registers the comparison app in tools/mat/mat
tools/parity-check/run-parity.sh heap.hprof work/        # ingest with DHP, dump both sides, compare
REUSE_NATIVE=1 tools/parity-check/run-parity.sh heap.hprof work/ --no-reports   # re-run without redoing native / reports
DHP_XMX=4g MAT_XMX=14g MODES="objects inspect dominator queries" tools/parity-check/run-parity.sh heap.hprof work/
```

Modes: `objects` (per-object class/size/retained/dominator/references/roots), `loaders`, `inspect` (the inspector view of
sampled objects: fields, statics, class hierarchy), `dominator` (the tree with child order), `queries`
(98 MAT commands and OQL queries from `tools/parity-check/queries.txt`, compared per query with identity hash codes
and stack traces normalized by `query_diff.py`), plus the three standard report archives.

Sample result on the 4 GB complex dump:

```
common=589726 native-only=0 dhp-only=0
class differs in 0 / shallow 0 / retained 0 / idom 0 / outbound 0 / roots 0      PARITY OK
object ids: identical      loaders: identical      inspect: identical (788696 lines)
dominator: identical       queries: identical ({'identical': 98, 'ORDER-ONLY': 0, 'DIFFERENT': 0})
pages compared: 17 different: 0 / 5 different: 0 / 14 different: 0               (reports)
```

Add your own queries to `tools/parity-check/queries.txt` (`oql: select ...` or any MAT command line). Setting
`DHP_TIMES=<file>` records per-query timings and the `bench` mode times MAT's index primitives on both backends.

---

## Performance

**Ingestion** (this laptop: 12 cores, NVMe):

| Dump | Native MAT index build | DHP ingest |
| :--- | :--- | :--- |
| 10.7 GB real HotSpot dump, 51.9M objects | 142 s at `-Xmx14g`, 3.1 GB of index files | **70 s at `-Xmx4g`**, 5.1 GB database |
| 4.0 GB complex dump, 113M objects | n/a | 36-44 s at `-Xmx4g`, 3.0 GB chunk file + 2 MB database |

**MAT on top of DHP** (4 GB complex dump, 98 commands and OQL queries, native MAT at 14 GB vs DHP at 4 GB ingest):

| Query | Native MAT | DHP |
| :--- | ---: | ---: |
| `component_report_top` | 121 s | 93 s |
| `leakhunter` | 17.0 s | 17.0 s |
| `group_by_value java.lang.String` | 25.5 s | 15.1 s |
| OQL over all `java.lang.String` | 17-19 s | 12 s |
| `weak_references_statistics` | 15.4 s | 8.3 s |
| `oql: select ... from instanceof gen.Base` | 6.8 s | 6.5 s |
| **All 98 queries** | **285 s** | **221 s** |

Index primitives, ns per object on randomly spread ids: address 59 (native 35), class 71 (60), shallow size 81 (52),
retained size 37 (29), outbound references 199 (282), `getObject` with fields 435 (679).

What makes it fast:

- **Chunk file instead of per-row SQL.** A lookup is a directory entry and a memcpy from a mapped window (about
  0.1 us) rather than a JDBC statement and B-tree seek (about 1 us). Per-object SQL rows also dominated
  ingestion time (about 1.3 us per row), which is why the graph is built in memory and written in bulk.
- **Precomputed everything MAT would otherwise compute**: dominator tree with MAT's child order, retained sizes,
  per-class instance lists, class statistics, resolved class hierarchy and GC-root ids, array flags.
- **Memory-mapped dump reads** (`HprofMap`): instance and array reads are memory loads, and field layouts are cached per class.
- **MAT's own collections and order**: classes, roots and unreachable-object histograms are rebuilt through MAT's
  hash maps so they enumerate in MAT's order (a precondition for identical UI results).

---

## Memory-bounded internals

DHP keeps per-object structures as primitive arrays (no per-object hash maps, no rehash spikes). The `--memory-budget`
option steers batch sizes and the address-map strategy; `-Xmx` is the actual limit.

### Address-to-id map (`IAddressToIdMap`)

MAT ids are the rank of an object's address, so the map is a sorted array of addresses and an id is the index.

| Implementation | Use | Memory for 100M objects |
| :--- | :--- | :--- |
| `SortedAddressToIdMap` (default) | one sorted `long[]`, binary search | 800 MB |
| `ChunkedAddressToIdMap` | the same in 8 MB chunks (no humongous G1 allocations) | 800 MB |
| `MmapAddressToIdMap` (`--address-map mmap`) | the sorted array in a memory-mapped temp file | ~0 MB heap |
| (replaced) fastutil `Long2IntOpenHashMap` | | 3.2 GB, 6.4 GB while rehashing |

The sort is `InPlaceLongSort`, a parallel quicksort with **zero auxiliary arrays** (`Arrays.parallelSort` allocates a
second array the size of the input, which is an instant 800 MB-1 GB allocation for 100M objects). Pass 1 also prunes the
string table right after class resolution to give back about 1.5 GB before sorting.

### Membership sets (`IObjectMembershipSet`)

| Implementation | Memory (100M objects) | Lookup |
| :--- | :--- | :--- |
| `BitSetMembershipSet` (default) | 11.9 MB | ~2.9 ns |
| `RoaringMembershipSet` (`--membership-set roaring`) | < 100 KB for dense ranges | ~35 ns |

```bash
mvn test -pl dhp-core -Dtest=MemoryAndBitmapBenchmarkTest     # run the benchmark yourself
```

### Storage layout

`dhp-storage-jdbc` stores per-object data in *chunks* keyed by `(kind << 32) | chunkNumber`: address, class id, shallow
size, file position and array-flag columns (512 objects per chunk), outbound / inbound / dominated lists (32 objects per
chunk, lists above 16K entries stored out of line), dominator id and retained size columns, instance lists per class
and an address index. For file-backed SQLite they go into `<db>.chunks`
(`[len][bytes]` records, a per-kind directory and a footer with the database's snapshot id, written and fsynced before
the completion marker `numberOfObjects`); for PostgreSQL and in-memory SQLite into the `dhp_chunks` table. An
interrupted ingestion has no marker and is discarded the next time.

---

## Building, testing, CI

```bash
mvn clean install                                            # build and run all tests (JDK 21, Maven 3.9)
mvn clean package -pl dhp-cli -am -DskipTests                # dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar
mvn clean package -pl dhp-mat-plugin -am -DskipTests         # OSGi bundle for MAT
```

- Unit tests cover the HPROF reader, both passes, the dominator engine, the in-memory graph and compaction, MAT's sort,
  the chunk file, the JDBC engine, and the plugin's index builder. `EclipseMatEquivalenceParityTest` is skipped
  (and reported as skipped) unless `-Dmat.test.dump=<MAT test dump>` is set.
- Anything touching object size calculation, dominator computation or hierarchy traversal must also pass the native-vs-DHP
  comparison above on a real dump.
- GitHub Actions (`.github/workflows/ci.yml`) builds and tests on Ubuntu and Windows (JDK 21) for every push and pull request.
- `AGENTS.md` is the technical reference for contributors and coding agents (architecture, MAT semantics DHP replicates,
  coding guidelines); `docs/SESSION_TRANSFER.md` records the performance work history.

---

## Troubleshooting

| Symptom | Cause and fix |
| :--- | :--- |
| `OutOfMemoryError` while parsing | Heap too small for the object count. Run `--estimate`, raise `-Xmx` accordingly, or add `--address-map mmap`. |
| `Target database already contains existing DHP tables` | The database already holds a snapshot. Add `--clean` to re-ingest, or choose another `--url`. |
| MAT: `the HPROF dump ... is not available` when inspecting an object | The `.hprof` was moved or deleted. Put it back next to the `.dhp` or fix `dump.file`. Index views (histogram, dominator tree) still work, field values need the dump. |
| `chunk file ... belongs to a different snapshot` / `cannot be used` | A `.db` was paired with an old or foreign `.chunks` file. Copy both files together, or re-ingest with `--clean`. |
| `Compressed heap dumps are not supported` | Decompress first: `gunzip heap.hprof.gz`. Positions recorded during ingestion refer to the uncompressed stream. |
| `The database ... holds no snapshot and the HPROF dump ... is missing` | The descriptor points to an empty database and no dump to ingest. Fix `dump.file`. |
| MAT does not offer `.dhp` | The plugin is not installed in this MAT: run `scripts/install-mat-plugin`, then start MAT with `-clean`. |
| Maven cannot resolve `org.eclipse.mat:...` artifacts | Run `scripts/setup-deps` (installs the MAT API jars into `~/.m2`). |
| Slow first queries after a reboot | The chunk file is not in the page cache yet; it warms up as it is read (or `cat heap.dhp.db.chunks > /dev/null`). |

---

## Known limitations

- **Compressed dumps** (`.gz`) are rejected; decompress them first.
- **IBM/DTFJ-style dumps** that encode class objects as `INSTANCE_DUMP` records are not handled; HotSpot and OpenJ9 `hprof` output is.
- **PostgreSQL** and the Windows scripts are exercised by CI/unit tests but have not been verified at the multi-GB scale; PostgreSQL does not use the memory-mapped chunk file and is slower for MAT queries.
- The database and chunk file are tied together; copy both. A descriptor with `db.password` should be treated as a secret.
- Opening a snapshot needs the original `.hprof` for field values and array contents.

---

## License

[Eclipse Public License v. 2.0 (EPL-2.0)](LICENSE).

DHP builds on source code, algorithms and parser structures of the [Eclipse Memory Analyzer (MAT)](https://www.eclipse.dev/mat)
project (Copyright © 2008, 2026 SAP AG, IBM Corporation and others). See [NOTICE](NOTICE) for the attribution details.
