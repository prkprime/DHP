# AGENTS.md — Dynamic Heap Parser (DHP) Developer & Agent Guide

Welcome to the **Dynamic Heap Parser (DHP)** repository. This document serves as the primary technical specification, architectural reference, and operational playbook for AI agents and human developers maintaining, debugging, or extending this project.

---

## 1. Project Mission & Core Architecture

DHP is a high-throughput, low-memory HPROF heap dump ingestion and graph engine designed as a drop-in storage and index provider for **Eclipse Memory Analyzer (MAT)**.

Standard Eclipse MAT relies on custom in-memory and disk-paged index files (`.idx`, `.o2c`, `.inbound`, `.outbound`, `.dom`) that consume significant RAM during generation. DHP solves this bottleneck by streaming HPROF records into an optimized relational database engine (SQLite WAL with memory budget governance or PostgreSQL) and computing dominator trees and retained sizes before exposing standard MAT preliminary indexes.

### Ingestion & Analysis Pipeline

```
                     ┌────────────────────────────────────────┐
                     │          Raw .hprof Heap Dump          │
                     └───────────────────┬────────────────────┘
                                         │
                        [Pass 1: Fast Scan & Metadata]
                        - Discovers string constants
                        - Maps class definitions & fields
                        - Identifies GC roots & thread locals
                                         │
                                         ▼
                     ┌────────────────────────────────────────┐
                     │        Dynamic Memory Governor         │
                     │  - Sets batch sizes & thread pools     │
                     │  - Tunes SQLite PRAGMAs & page cache   │
                     └───────────────────┬────────────────────┘
                                         │
                        [Pass 2: Streaming Object Ingest]
                        - High-speed batch inserts (no indexes)
                        - Instances, object arrays, primitive arrays
                        - Outbound and inbound references
                                         │
                                         ▼
                     ┌────────────────────────────────────────┐
                     │  In-memory HeapGraph, then chunk file   │
                     │  - Columns per object + compact edges  │
                     │  - Unreachable objects removed (MAT)   │
                     └───────────────────┬────────────────────┘
                                         │
                        [Dominator Tree & Retained Engine]
                        - Lengauer-Tarjan semi-dominator algorithm
                        - Depth-First Search from artificial super root
                        - Immediate dominators & bottom-up retained size
                                         │
                                         ▼
                     ┌────────────────────────────────────────┐
                     │     Eclipse MAT DHP Plugin Layer       │
                     │  - DhpSnapshotFactory (opens .dhp)     │
                     │  - DhpIndexBuilder (IIndexBuilder)     │
                     │  - DhpHeapObjectReader (IObjectReader) │
                     │  - Database-backed IIndexReader adapters│
                     └────────────────────────────────────────┘
```

---

## 2. Reactor Modules & Responsibilities

The project is structured as a Maven multi-module reactor under Java 21:

| Module | Artifact ID | Description |
| :--- | :--- | :--- |
| **`dhp-core`** | `org.eclipse.mat.dhp:dhp-core` | Core parsing and graph logic: `HprofBinaryReader`, `Pass1ScanParser`, `Pass2ObjectIngester`, `DominatorTreeEngine`, `MemoryGovernor`, `HeapRecords`, and memory-bounded data structures (`IAddressToIdMap`, `SortedAddressToIdMap`, `ChunkedAddressToIdMap`, `MmapAddressToIdMap`, `BitSetMembershipSet`, `RoaringMembershipSet`). Contains zero JDBC code. |
| **`dhp-storage-jdbc`** | `org.eclipse.mat.dhp:dhp-storage-jdbc` | JDBC storage abstraction and engine: `HeapStorageEngine` interface and `JdbcHeapStorageEngine` implementation supporting SQLite WAL and PostgreSQL with one write connection plus a lock-free per-thread read connection (plain `DriverManager`, no pool) and bulk chunk writes. |
| **`dhp-cli`** | `org.eclipse.mat.dhp:dhp-cli` | Command-line interface (`DhpMain`) using Picocli to parse dumps directly from the shell or execute headless database staging. Packaged as a fat JAR with `maven-shade-plugin`. |
| **`dhp-mat-plugin`** | `org.eclipse.mat.dhp:dhp-mat-plugin` | Eclipse MAT plugin. `DhpSnapshotFactory` opens `.dhp` descriptors (the main path); `DhpIndexBuilder` (`IIndexBuilder`) is the registered fallback for MAT's own open flow. Both share `DhpDescriptor` (descriptor parsing), `DhpIngestion` (open or ingest on the fly), `ClassModel`/`ClassOrder` (MAT's classes in MAT's order), `RootMaps`/`RootTypes` and `DhpIndexes` (the `DbOne2LongIndex`/`DbOne2OneIndex`/`DbOne2ManyIndex`/`DbOne2SizeIndex` wiring). `DhpHeapObjectReader` (`IObjectReader`) reads object contents from the dump through `HprofMap`. Packaged as an OSGi bundle with `plugin.xml` and shaded runtime dependencies. |

---

## 3. Database Schema & Indexing Strategy

To keep ingestion fast and its memory proportional to the object count (about 55 bytes per object; a 10.7 GB / 51.9M-object real dump ingests in ~70 s at -Xmx3g-4g):
1. **In-memory graph**: Pass 2 fills a `HeapGraph` (primitive per-object columns plus a compact edge log) instead of inserting rows. SQLite inserts cost ~1.3 us per row regardless of journal mode, so per-object/per-edge rows dominated ingestion (29M rows per 5M objects).
2. **Chunked bulk persist**: the finished graph is written in a few bulk calls (`saveObjects`, `saveOutboundEdges`, `saveInboundEdges`, `saveDominators`) as blobs in a single `dhp_chunks` table, one row per chunk of 512 objects (scalar columns) or 32 objects (edge lists). Bulk ingestion runs with `journal_mode=OFF`, `synchronous=OFF`; WAL is restored when the dominator tree has been written.

### Tables

- **`dhp_snapshot_info`**: Key-value pairs for snapshot metadata (`idSize`, `creationDate`, `totalHeapSize`, `numberOfObjects`). Precomputes aggregates in `finishIngestion()` to achieve sub-millisecond retrieval.
- **`dhp_classes`**: Stores `class_id`, `super_class_id`, `class_loader_id`, `class_name`, `instance_size`, `fields_data`, `static_fields_data`, and pre-resolved `class_obj_id`, `super_class_obj_id`, `class_loader_obj_id`, and `used_size`. Supports full Class Hierarchy navigation and Static Fields in MAT's Inspector view.
- **`dhp_class_stats`**: Stores pre-aggregated `class_id`, `instance_count`, and `total_size` populated in `finishIngestion()`, eliminating costly full-table `GROUP BY` aggregations during snapshot open.
- **`<db>.chunks` (SQLite)**: for file-backed SQLite databases the chunk blobs below are not stored in the `dhp_chunks` table but in an append-only sidecar file `<db>.chunks` (records `[len][bytes]` plus a trailer with one directory per kind), memory-mapped for reading. A lookup is a directory read and a memcpy (~0.1 us) instead of a JDBC statement and B-tree seek (~1 us), which made index-heavy MAT queries 2-3x slower than native; with the sidecar they are on par or faster. The file is finalized and `chunkFile=1` is recorded in `dhp_snapshot_info` before the completion marker; copy the `.chunks` file together with the `.db`. PostgreSQL and in-memory SQLite keep using `dhp_chunks`.
- **`dhp_chunks`**: `chunk_key = (kind << 32) | chunk` -> `data` BLOB (little-endian). Kinds: address, class object ID, shallow size, file position and array-flag columns (512 objects per chunk); outbound, inbound and dominated-children lists (32 objects per chunk: `[huge-list mask][33 offsets][targets]`, lists above 16K entries are stored out of line); dominator ID and retained size columns; objects grouped by class; first-address-per-chunk index used for address lookups (instances are ID-ordered by address, so lookup is a binary search over chunks).
- **`dhp_gc_roots`**: Root pointers `(object_id, object_address, referrer_address, root_type, thread_address, thread_object_id)` with pre-resolved `object_id` and `thread_object_id`.

### Storage Model & File Size Disparity (Why 58MB SQLite for a 2.2GB Dump)
In HPROF format, 85-95% of file size comprises primitive array payload buffers (`byte[]`, `char[]`, `int[]`). DHP stores the object graph topology, relationships, metadata, and dominator trees in SQLite (~58 MB for a 2.2 GB dump; ~5.4 GB for the 11 GB / 47.9M-object synthetic dump), while recording the exact byte offset (`file_position`) in the object position column. `DhpHeapObjectReader` performs zero-memory-overhead random seeks into `.hprof` only when array contents or instance field values are inspected, matching Eclipse MAT's native architecture where index files are similarly 15-30 MB.

---

## 4. Dominator Tree Algorithm (Lengauer-Tarjan)

`DominatorTreeEngine` implements the classic Lengauer-Tarjan algorithm for dominator tree computation:
- **Artificial Super Root**: Connects to all GC root objects to provide a single entry point for Depth-First Search (DFS).
- **Semi-dominators**: Computed using path compression with disjoint sets (`ancestor`, `label`, `semi`).
- **Immediate Dominators**: Resolves immediate dominator (`idom`) for all reachable heap objects.
- **Retained Sizes**: Computed bottom-up across the dominator tree by accumulating shallow sizes from leaves to roots.
- **MAT Parity**: The resulting tree conforms to Eclipse MAT's dominator semantics where super-root children represent top-level GC dominators.

---

## 5. Memory-Bounded Architecture (Address Maps & Membership Sets)

To eliminate out-of-memory errors on massive dumps (e.g. 11GB–100GB dumps with 100M+ objects) while maintaining peak ingestion throughput:

### Address-to-ID Mapping (`IAddressToIdMap`)
- **Root Problem**: Fastutil's `Long2IntOpenHashMap` requires $2^{28}$ slots for 100M objects (~3.2 GB steady-state). During internal `rehash()`, it duplicates table memory, causing a sudden 6.4 GB allocation spike and fatal `OutOfMemoryError`.
- **`SortedAddressToIdMap`**: In Pass 1, addresses are gathered and sorted in-place in primitive `long[]`. Sequential MAT IDs are dense integer offsets `baseInstanceId + index`. Binary search operates directly on the array with zero value array overhead:
  - 100M objects consume strictly **800 MB** of RAM (vs 3.2–4.8 GB with hash maps).
  - Build time is **29x faster** (14.0 ms vs 412.5 ms for 5M objects).
  - Eliminates the Pass 2 pre-registration insertion loop completely.
- **`InPlaceLongSort` (Zero-Auxiliary-Array Quicksort)**:
  - Standard `java.util.Arrays.parallelSort(long[])` is a parallel merge sort that allocates an auxiliary array `new long[N]` (an instant 800 MB – 1.07 GB allocation for 100M objects) and invokes OpenJDK's `DualPivotQuicksort.tryMergeRuns`, causing fatal `OutOfMemoryError` on large dumps under 4GB heaps.
  - `InPlaceLongSort` executes an in-place multi-threaded partition-first Quicksort using `ForkJoinPool` with **strictly 0 bytes** of auxiliary array allocation.
  - **Zero-Copy Deduplication**: In-place unique compaction updates `uniqueInstanceCount` without calling `Arrays.copyOf(raw, unique)`, saving an additional 800 MB allocation spike. Binary search operates directly on `Arrays.binarySearch(a, 0, uniqueInstanceCount, address)`.
- **String Memory Governance in Pass 1**:
  - Millions of application string objects in `.hprof` dumps can consume >1.5 GB heap. Pass 1 dynamically tracks referenced string IDs (class and field descriptors) and prunes `strings` down to only required names immediately after class hierarchy resolution, freeing ~1.5 GB before the sorting phase starts.
- **`ChunkedAddressToIdMap`**: Breaks sorted addresses into 8 MB chunks (1,048,576 longs each) using bit-shifting (`mid >>> 20` and `mid & 0xFFFFF`) to eliminate G1 GC humongous allocation warnings.
- **`MmapAddressToIdMap`**: Streams the sorted primitive array into an off-heap memory-mapped file on disk, reducing JVM heap consumption to **0 MB** for extreme low-memory environments (e.g. `-Xmx512m` on a 150GB dump).

### Object Membership Tracking (`IObjectMembershipSet`)
- **`BitSetMembershipSet`** (Default): Uses standard `java.util.BitSet` (1 bit per object ID). For 100M objects, memory footprint is **11.9 MB** with sub-nanosecond lookups (~2.9 ns/op).
- **`RoaringMembershipSet`**: Uses run-compressed `org.roaringbitmap.RoaringBitmap`. Memory footprint is **< 100 KB** for sequential ranges (~35.3 ns/op lookup).

---

## 6. Eclipse MAT Plugin Integration

### File Extensions & Descriptors
The plugin binds **strictly to `.dhp` files**:
- `.dhp` files serve as database descriptors pointing to the SQLite DB or PostgreSQL instance and the underlying `.hprof` file.
- Example descriptor:
  ```properties
  db.url=jdbc:sqlite:/path/to/heapdump.dhp.db
  dump.file=/path/to/heapdump.hprof
  memory.budget=4G
  ```
- If the database is not yet ingested, `DhpIngestion.openOrIngest` ingests the `.hprof` into it on the fly (an unfinished earlier ingestion, i.e. tables without the `numberOfObjects` marker, is discarded; a missing dump is an error). If already ingested (via `dhp-cli`), the precomputed tables, chunk file and dominator tree are used immediately without generating legacy MAT index files.

### Automation & Tooling Scripts (`scripts/`)
Cross-platform scripts (Linux, macOS, Windows) streamline installation, testing, and headless execution with full Bash (`.sh`), Batch (`.bat`), and PowerShell (`.ps1`) parity:
- **`scripts/setup-all.sh` / `setup-all.bat` / `setup-all.ps1`**: Master setup: resolves MAT dependencies into Maven, compiles the reactor, sets up Eclipse MAT, and installs the DHP bundle.
- **`scripts/setup-deps.sh` / `setup-deps.bat` / `setup-deps.ps1`**: Dynamically checks local Maven repository (`~/.m2`), extracts required p2 bundles (`api`, `parser`, `hprof`, `report`) from Eclipse MAT (downloading official MAT if missing), and installs them into Maven without committing binary JARs to Git.
- **`scripts/setup-mat.sh` / `setup-mat.bat` / `setup-mat.ps1`**: Sets up Eclipse MAT into `tools/mat/mat` (reusing local cache or downloading the official RCP release dynamically matching `<mat.version>`).
- **`scripts/install-mat-plugin.sh` / `install-mat-plugin.bat` / `install-mat-plugin.ps1`**: Deploys the built DHP shaded OSGi bundle to `MAT_HOME/plugins/`, registers it in `bundles.info`, and purges the OSGi cache.
- **`scripts/install-p2-dependency.sh` / `install-p2-dependency.bat` / `install-p2-dependency.ps1`**: Wrapper around `MemoryAnalyzer -application org.eclipse.equinox.p2.director` to install p2 update site features.
- **`scripts/run-mat-headless.sh` / `run-mat-headless.bat` / `run-mat-headless.ps1`**: Headless report generation using `ParseHeapDump` against `.dhp` descriptor files. Emits `Task: Writing HTML files` progress logs during Equinox report rendering.

### Object & Array Payload Reading
`DhpHeapObjectReader` reads through `HprofMap` (read-only memory-mapped 1 GiB windows, lock-free, bulk decoding) at the `file_position` stored in the database:
- Reads primitive arrays directly from binary offsets into typed Java arrays (`boolean[]`, `byte[]`, `char[]`, `int[]`, `long[]`, etc.).
- Reads object arrays and resolves reference pointers.
- Reconstructs instance field values with exact type and multi-level class hierarchy resolution.
- Resolves strings, arrays, and complex instance graphs with 100% data parity.

---

## 7. Build, Test, and Packaging Instructions

### Prerequisites
- JDK 21+ (`java -version` >= 21)
- Maven 3.9+
- Docker (optional, for PostgreSQL tests and PostgreSQL staging)

### Commands
- **Full Reactor Build**:
  ```bash
  mvn clean install
  ```
- **Run Unit & Parity Tests**:
  ```bash
  mvn test
  ```
- **Build Standalone CLI**:
  ```bash
  mvn clean package -pl dhp-cli -am -DskipTests
  # Generates dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar (shaded fat JAR)
  ```
- **Build MAT Plugin Bundle**:
  ```bash
  mvn clean package -pl dhp-mat-plugin -am -DskipTests
  # Generates dhp-mat-plugin/target/dhp-mat-plugin-1.0.0-SNAPSHOT.jar (OSGi shaded bundle)
  ```

---

## 8. Installing into Eclipse Memory Analyzer (MAT)

1. Build the shaded plugin jar:
   ```bash
   mvn package -pl dhp-mat-plugin -am -DskipTests
   ```
2. Copy the artifact into MAT's `plugins/` directory:
   ```bash
   cp dhp-mat-plugin/target/dhp-mat-plugin-1.0.0-SNAPSHOT.jar \
      /path/to/mat/plugins/org.eclipse.mat.dhp_1.0.0.SNAPSHOT.jar
   ```
3. Register the bundle in MAT's `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`:
   ```text
   org.eclipse.mat.dhp,1.0.0.SNAPSHOT,plugins/org.eclipse.mat.dhp_1.0.0.SNAPSHOT.jar,4,true
   ```
4. Clear the OSGi cache to force extension re-indexing:
   ```bash
   rm -rf /path/to/mat/configuration/org.eclipse.osgi
   ```
5. Launch MAT:
   ```bash
   /path/to/mat/MemoryAnalyzer -clean
   ```

---

## 9. CLI Usage Examples

### Generate Synthetic Test Dump
```bash
# Authentic complex dump (~2.38 GB):
java -Xmx4g tools/dump-generator/ComplexHeapDumpGenerator.java dump.hprof

# Fast scaled test dump (~140 MB):
java -Xmx1g tools/dump-generator/ComplexHeapDumpGenerator.java dump.hprof 0.05
```

### Parse into SQLite with Live Memory Monitor (All Platforms)

**Linux / macOS (Bash):**
```bash
java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar \
  --dump /path/to/heapdump.hprof \
  --memory 4G \
  --monitor \
  --threads 8
```

**Windows Command Prompt (CMD):**
```cmd
java -jar dhp-cli\target\dhp-cli-1.0.0-SNAPSHOT.jar ^
  --dump C:\dumps\heapdump.hprof ^
  --memory 4G ^
  --monitor ^
  --threads 8
```

**Windows PowerShell:**
```powershell
java -jar dhp-cli\target\dhp-cli-1.0.0-SNAPSHOT.jar `
  --dump C:\dumps\heapdump.hprof `
  --memory 4G `
  --monitor `
  --threads 8
```

### Parse into PostgreSQL
```bash
java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar \
  --dump /path/to/heapdump.hprof \
  --jdbcurl jdbc:postgresql://localhost:5432/heapdb \
  --user dhp \
  --password dhppass \
  --memory-budget 4294967296 \
  --threads 8
```

---

## 9b. Verifying Parity Against Native MAT

Comparing DHP with an earlier DHP build only proves nothing regressed. To prove it matches Eclipse MAT, use `tools/parity-check`:

1. Make a real dump (`java -Xmx13g -XX:+UseParallelGC tools/dump-generator/RealHeapDumpGenerator.java out.hprof [liveGB] [seed] [payloadBytes]`, which asks HotSpot itself for the dump).
2. `tools/parity-check/build.sh` builds and registers the OSGi application `org.eclipse.mat.dhp.parity.dump` in `tools/mat/mat`.
3. Dump a canonical per-object view through MAT's API for the native dump and for the DHP descriptor: `MemoryAnalyzer -consolelog -nosplash -application org.eclipse.mat.dhp.parity.dump <dump.hprof|x.dhp> out.tsv [sampleMod]` (`sampleMod` keeps only objects with `(address/8) % mod == 0`; use it on huge dumps).
4. `LC_ALL=C sort -t$'\t' -k1,1` both files and run `python3 tools/parity-check/compare.py native.s.tsv dhp.s.tsv`.

Measured result (MAT 1.17, default settings): DHP is identical to the native MAT parser. On a 9.4M-object real HotSpot dump (full comparison) and on a 10.7 GB real dump with 51.9M objects (1/64 and 1/61 samples keyed by address, about 0.8M objects each) there are zero differences in object ids, class, shallow size, retained size, immediate dominator, outbound references and GC roots (type and context); the `.threads` stack files are identical; and every page of the leak suspects, system overview and top components reports is identical (`tools/parity-check/compare_reports.py`, which ignores only the dump path).

Timings on the 10.7 GB dump (this laptop, 12 cores, NVMe):

| | native MAT | DHP |
|---|---|---|
| index / ingest | 142 s at `-Xmx14g` | 70 s at `-Xmx4g` (68 s at `-Xmx3g`, fails at 2.5g) |
| disk | 3.1 GB of index files | 5.1 GB database |
| three standard reports | 271 s at 14 GB | 638 s at a 4 GB MAT heap |

DHP is faster to ingest and uses far less memory; that 2.4x report slowdown was caused by every index lookup going through a JDBC statement and a B-tree seek; with the memory-mapped chunk file (`<db>.chunks`) MAT queries on a 4 GB dump run on par with native MAT or faster (see the UI query speed paragraph above). The report timings in this table predate the chunk file.

UI query speed (4 GB complex dump, 98 MAT commands/OQL queries incl. leak suspects, histogram, dominator, string OQL): 221 s total for DHP at a 4 GB ingest vs 285 s for native MAT, with identical results (`query_diff.py`, `DHP_TIMES=<file>` records per-query timings, `bench` mode times the index primitives). Object inspection reads the dump through mapped windows (`HprofMap`).

Known limitations: compressed (`.gz`) dumps are rejected (recorded positions would refer to the decompressed stream); dumps that encode class objects as `INSTANCE_DUMP` records (IBM/DTFJ style) are not handled; PostgreSQL support and the Windows scripts are untested in this environment; the object reader fails with a clear error when the original dump file is missing instead of returning default values.

MAT semantics DHP replicates (all found by this comparison): `refSize` is 4 when compressed oops are detected (an object array starting inside the span the previous array would cover with 8-byte refs), objects are aligned to the GCD of address gaps (min 8), class-object shallow size is the aligned statics only, stack-local roots of a known thread are not GC roots (the thread object references them), the artificial system class loader has only a class edge and is not a root, classes of the system loader become roots only if the dump has no sticky-class roots, and unreachable objects are promoted to `UNREACHABLE` roots by MAT's multi-pass heuristic (`UnreachableRoots`).

## 10. Developer & Coding Guidelines

1. **Memory Budget Discipline**: When implementing graph algorithms or data transformations, never load arbitrary unbounded collections into JVM memory. Use streamed queries, paginated queries, memory-bounded address maps (`SortedAddressToIdMap`, `ChunkedAddressToIdMap`, `MmapAddressToIdMap`), and compact membership bitsets (`BitSetMembershipSet`, `RoaringMembershipSet`). Never use unbounded hash maps (`Long2IntOpenHashMap`) for full-dump object indices as rehashing doubles allocation spikes and causes `OutOfMemoryError`.
2. **Deterministic Parity**: Any changes to object size calculation, dominator computation, or hierarchy traversal must pass `EclipseMatEquivalenceParityTest` and `EclipseMatGeneralSnapshotTestSuiteTest`.
3. **OSGi & Serialization Safety**: In the MAT plugin, never store non-serializable objects (such as active connections or lambdas referencing JDBC components) in `XSnapshotInfo.properties`. MAT serializes snapshot metadata to disk when saving indexes.
4. **Cross-Platform Path Safety**: In SQLite JDBC URLs and file paths, always use normalized forward slashes (`/`) and canonical absolute paths (`file.getAbsoluteFile().getParentFile()`) to avoid Windows backslash escaping errors and relative-path root mismatches.

