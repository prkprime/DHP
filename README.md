# Dynamic Heap Parser (DHP)

[![CI](https://github.com/prkprime/DHP/actions/workflows/ci.yml/badge.svg)](https://github.com/prkprime/DHP/actions/workflows/ci.yml)
[![License: EPL-2.0](https://img.shields.io/badge/License-EPL%202.0-blue.svg)](https://opensource.org/licenses/EPL-2.0)
[![Java: 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)

> **High-throughput, constant-memory HPROF heap dump ingestion and graph engine for Eclipse Memory Analyzer (MAT)**.

DHP parses massive Java heap dumps (100GB+) without requiring hundreds of gigabytes of RAM. By replacing memory-bound custom indices with an adaptive multi-tiered engine (SQLite WAL with memory budget governor or PostgreSQL) and bulk B-tree index generation, DHP offers high ingestion speeds while keeping memory usage under configurable budgets.

---

## Architecture Overview

```
                      ┌────────────────────────────────────────┐
                      │             HPROF Dump File            │
                      └───────────────────┬────────────────────┘
                                          │
                         [Pass 1: Scan & Metadata Discovery]
                                          │
                                          ▼
                      ┌────────────────────────────────────────┐
                      │             Memory Governor            │
                      │  (Tuned batching, threads, DB cache)   │
                      └───────────────────┬────────────────────┘
                                          │
                        [Pass 2: Streaming Object Ingestion]
                                          │
                                          ▼
                      ┌────────────────────────────────────────┐
                      │           Raw Bulk Tables              │
                      │    (No PKs or Indexes during ingest)   │
                      └───────────────────┬────────────────────┘
                                          │
                          [Bulk B-Tree Index Generation]
                                          │
                                          ▼
                      ┌────────────────────────────────────────┐
                      │         Dominator Tree Engine          │
                      │     (Tarjan-Lengauer / Retained Size)  │
                      └───────────────────┬────────────────────┘
                                          │
                                          ▼
                      ┌────────────────────────────────────────┐
                      │    Eclipse MAT DHP Index Extension     │
                      │       (Drop-in Parser Extension)       │
                      └────────────────────────────────────────┘
```

### Module Structure

- **`dhp-core`**: Core HPROF streaming parser, Tarjan-Lengauer Dominator Tree engine, Memory Governor, and class hierarchy resolver.
- **`dhp-storage-jdbc`**: Pluggable high-throughput JDBC storage engine supporting SQLite WAL and PostgreSQL with asynchronous commit.
- **`dhp-cli`**: Standalone command-line interface for batch parsing and database generation.
- **`dhp-mat-plugin`**: Eclipse Memory Analyzer (MAT) plugin providing full drop-in index builder parity (`DhpIndexBuilder` and `DhpHeapObjectReader`).

---

## Features

- **Constant-Memory Architecture**: Configurable RAM budget (e.g. 1GB–4GB) regardless of whether the heap dump is 2GB or 150GB.
- **Dynamic Memory Governor**: Self-tunes batch sizes, thread pools, and SQLite page cache sizes based on available system memory.
- **Zero-Auxiliary In-Place Address Sorting**: Multi-threaded in-place Quicksort (`InPlaceLongSort`) with strictly 0 bytes of auxiliary array allocation, eliminating the `DualPivotQuicksort.tryMergeRuns` OOM spikes on multi-gigabyte dumps with 100M+ objects.
- **Dynamic String Pruning & Zero-Copy Dedup**: Automatically prunes non-class strings right after class hierarchy resolution and indexes sorted address subranges without duplicate array copies, saving up to 2.5 GB of heap headroom.
- **Compact Topological Storage**: Stores graph edges, class metadata, and dominators in SQLite (~58 MB for a 2.2 GB dump), seeking directly into `.hprof` byte offsets (`file_position`) for raw array and instance payloads.
- **Full Object Inspector & Static Fields**: Populates static fields (both primitive and object reference values), superclasses, and subclasses across the entire JVM hierarchy.
- **Dominator Tree & Histogram Retained Sizing**: Full Lengauer-Tarjan semi-dominator tree computation with instant bottom-up retained sizes and full compatibility with MAT's on-demand "Calculate Minimum Retained Size" calculator.
- **Resilient Stream Handling**: Resilient to duplicate `INSTANCE_DUMP` / `CLASS_DUMP` records, zero-length `HEAP_DUMP_SEGMENT`s, and unknown record tags.
- **Full Eclipse MAT Parity**: Generates index structures compliant with Eclipse MAT, enabling immediate analysis in Eclipse MAT GUI or headless reports.

---

## Quick Start & Setup Scripts

DHP provides cross-platform setup scripts for Linux, macOS, and Windows.

### Prerequisites

- **Java**: JDK 21 or higher
- **Maven**: 3.9+
- **curl** and **unzip** / PowerShell

### 1. One-Step Automated Setup

Run the master setup script to install local Eclipse MAT JARs, compile the reactor modules, download/configure Eclipse MAT, and install the DHP plugin:

**Linux / macOS:**
```bash
git clone https://github.com/prkprime/DHP.git
cd DHP
./scripts/setup-all.sh
```

**Windows (Command Prompt or PowerShell):**
```bat
git clone https://github.com/prkprime/DHP.git
cd DHP
scripts\setup-all.bat
# or in PowerShell:
.\scripts\setup-all.ps1
```

### 2. Dynamic Eclipse MAT Dependency Resolution

DHP decouples itself from pre-committed binary blobs in Git. Instead:
- Root [`pom.xml`](pom.xml) defines a single source-of-truth property: `<mat.version>1.17.0</mat.version>`.
- The setup scripts automatically detect existing local Eclipse MAT installations (or download the official distribution from Eclipse mirrors), extract the required p2 bundles (`api`, `parser`, `hprof`, `report`), and install them into your local Maven cache (`~/.m2`).
- If already installed in `~/.m2`, the scripts verify them instantly without redundant downloads.

### 3. Modular Convenience Scripts

Full triple cross-platform parity (`.sh`, `.bat`, `.ps1`) across Linux, macOS, and Windows:

| Task | Linux / macOS (Bash) | Windows (CMD) | Windows (PowerShell) |
| :--- | :--- | :--- | :--- |
| **Full Setup** | `./scripts/setup-all.sh` | `scripts\setup-all.bat` | `.\scripts\setup-all.ps1` |
| **Install Dependencies** | `./scripts/setup-deps.sh` | `scripts\setup-deps.bat` | `.\scripts\setup-deps.ps1` |
| **Download & Extract MAT** | `./scripts/setup-mat.sh` | `scripts\setup-mat.bat` | `.\scripts\setup-mat.ps1` |
| **Deploy Plugin to MAT** | `./scripts/install-mat-plugin.sh` | `scripts\install-mat-plugin.bat` | `.\scripts\install-mat-plugin.ps1` |
| **Install P2 Features** | `./scripts/install-p2-dependency.sh` | `scripts\install-p2-dependency.bat` | `.\scripts\install-p2-dependency.ps1` |
| **Run Headless Report** | `./scripts/run-mat-headless.sh` | `scripts\run-mat-headless.bat` | `.\scripts\run-mat-headless.ps1` |

---

## Generating Sample Dumps (`ComplexHeapDumpGenerator`)

DHP includes an authentic, production-grade synthetic dump generator ([`tools/dump-generator/ComplexHeapDumpGenerator.java`](tools/dump-generator/ComplexHeapDumpGenerator.java)) that simulates complex enterprise workloads:
- Deep multi-level inheritance hierarchies across all 8 Java primitive types.
- Diamond DAG topologies testing dominator convergence (`idom(D) = A`).
- Cyclic reference loops, binary search trees (16,383 nodes), and collections (`HashMap`, `TreeMap`).
- Multi-thread local GC roots and distinct dominator retained buckets (650 MB, 550 MB, 450 MB, 350 MB, 250 MB).

Thanks to Java 21 single-file source execution, no prior compilation is required:

### Linux / macOS (Bash):
```bash
# Full authentic dump (strictly 2.0 GB - 3.0 GB, ~2.38 GB):
java -Xmx4g tools/dump-generator/ComplexHeapDumpGenerator.java sample_dump.hprof

# Fast scaled test dump (~140 MB, scale = 0.05):
java -Xmx1g tools/dump-generator/ComplexHeapDumpGenerator.java sample_dump.hprof 0.05
```

### Windows Command Prompt (CMD):
```cmd
:: Full authentic dump (strictly 2.0 GB - 3.0 GB, ~2.38 GB):
java -Xmx4g tools\dump-generator\ComplexHeapDumpGenerator.java sample_dump.hprof

:: Fast scaled test dump (~140 MB, scale = 0.05):
java -Xmx1g tools\dump-generator\ComplexHeapDumpGenerator.java sample_dump.hprof 0.05
```

### Windows PowerShell:
```powershell
# Full authentic dump (strictly 2.0 GB - 3.0 GB, ~2.38 GB):
java -Xmx4g tools\dump-generator\ComplexHeapDumpGenerator.java sample_dump.hprof

# Fast scaled test dump (~140 MB, scale = 0.05):
java -Xmx1g tools\dump-generator\ComplexHeapDumpGenerator.java sample_dump.hprof 0.05
```

---

## CLI Usage & Parsing from Repository Root

The standalone fat JAR is located at `dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar`.

### CLI Options

| Flag | Aliases | Description |
| :--- | :--- | :--- |
| `-d` | `--dump` | Path to the input `.hprof` heap dump file |
| `-c` | `--config` | Path to DHP descriptor file (`.dhp`) |
| `--db` | `--url`, `--db-url`, `--jdbcurl` | Database name or JDBC connection URL |
| `-m` | `--memory`, `--memory-budget` | Memory budget (e.g. `512M`, `2G`, `4GB`, or raw bytes). Defaults to 75% of JVM `-Xmx` |
| `-t` | `-w`, `--threads`, `--workers` | Worker threads count (defaults to available CPU cores) |
| `--clean` | `--drop-existing` | Drop pre-existing DHP tables in target database before ingestion |
| `--export-dhp` | | Path to export `.dhp` MAT descriptor (defaults to `<dumpPrefix>.dhp`) |
| `--monitor` | `--memory-monitor` | Enable live background CLI memory monitor logging JVM heap usage every 3s and peak RAM |
| `--address-map` | | Address-to-ID map strategy: `auto` (default), `sorted`, `chunked`, or `mmap` |
| `--membership-set` | | Ingested objects membership set: `bitset` (default, ~2.9ns/op) or `roaring` (run-compressed) |

### Parsing Commands (All Platforms)

When executing DHP CLI from the repository root (or any directory), invoke:

**Linux / macOS (Bash):**
```bash
# Auto-stages into SQLite alongside the dump:
java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar -d sample_dump.hprof

# Or with custom memory budget and threads:
java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar \
  --dump sample_dump.hprof \
  --memory 2G \
  --threads 4

# Or stream into PostgreSQL:
java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar \
  --dump sample_dump.hprof \
  --db jdbc:postgresql://localhost:5432/heapdumps \
  --user postgres \
  --password secret \
  --memory 4G
```

**Windows Command Prompt (CMD):**
```cmd
:: Auto-stages into SQLite alongside the dump:
java -jar dhp-cli\target\dhp-cli-1.0.0-SNAPSHOT.jar -d sample_dump.hprof

:: Or with custom memory budget and threads:
java -jar dhp-cli\target\dhp-cli-1.0.0-SNAPSHOT.jar ^
  --dump sample_dump.hprof ^
  --memory 2G ^
  --threads 4

:: Or stream into PostgreSQL:
java -jar dhp-cli\target\dhp-cli-1.0.0-SNAPSHOT.jar ^
  --dump sample_dump.hprof ^
  --db jdbc:postgresql://localhost:5432/heapdumps ^
  --user postgres ^
  --password secret ^
  --memory 4G
```

**Windows PowerShell:**
```powershell
# Auto-stages into SQLite alongside the dump:
java -jar dhp-cli\target\dhp-cli-1.0.0-SNAPSHOT.jar -d sample_dump.hprof

# Or with custom memory budget and threads:
java -jar dhp-cli\target\dhp-cli-1.0.0-SNAPSHOT.jar `
  --dump sample_dump.hprof `
  --memory 2G `
  --threads 4

# Or stream into PostgreSQL:
java -jar dhp-cli\target\dhp-cli-1.0.0-SNAPSHOT.jar `
  --dump sample_dump.hprof `
  --db jdbc:postgresql://localhost:5432/heapdumps `
  --user postgres `
  --password secret `
  --memory 4G
```

---

## Output Files & Resolution Architecture

When parsing `sample_dump.hprof`, DHP automatically co-locates the database and descriptor directly alongside the dump:

```
/path/to/
├── sample_dump.hprof     # Raw HPROF binary (never modified)
├── sample_dump.dhp.db    # SQLite WAL database (stores topology, dominators, stats)
└── sample_dump.dhp       # Eclipse MAT descriptor file
```

### Descriptor File (`.dhp`) Format:
```properties
# Dynamic Heap Parser (DHP) Descriptor
db.url=jdbc:sqlite:/path/to/sample_dump.dhp.db
dump.file=/path/to/sample_dump.hprof
memory.budget=2147483648
```
- **Canonical Absolute Paths**: By default, `db.url` and `dump.file` are generated with canonical absolute paths. Eclipse MAT can therefore be launched from **any directory** or through the desktop GUI, and it will locate the database and `.hprof` binary without path mismatch errors.
- **Relative Path Support**: Relative paths are also supported; if `dump.file` is relative, DHP resolves it relative to the directory containing the `.dhp` file.

---

## Opening in Eclipse Memory Analyzer (MAT)

DHP registers **strictly the `.dhp` extension** in Eclipse MAT to bypass the legacy memory-intensive `.idx` file generation entirely.

### 1. Headless Report Generation (All Platforms)

Generate standard MAT HTML leak reports (e.g., `sample_dump_Leak_Suspects.zip`):

**Linux / macOS (Bash):**
```bash
./scripts/run-mat-headless.sh sample_dump.dhp org.eclipse.mat.api:suspects
```

**Windows Command Prompt (CMD):**
```cmd
scripts\run-mat-headless.bat sample_dump.dhp org.eclipse.mat.api:suspects
```

**Windows PowerShell:**
```powershell
.\scripts\run-mat-headless.ps1 sample_dump.dhp org.eclipse.mat.api:suspects
```

### 2. Eclipse MAT GUI Navigation
1. Launch Eclipse MAT:
   - Linux: `./tools/mat/mat/MemoryAnalyzer`
   - Windows: `tools\mat\mat\MemoryAnalyzer.exe`
2. Click **File -> Open Heap Dump...**
3. Select `sample_dump.dhp` (the file dialog filter defaults to `.dhp`).
4. Eclipse MAT opens the snapshot immediately in **< 1 second** using direct SQL queries against SQLite/PostgreSQL, generating reports with **zero disk index files**!

> [!NOTE]
> **Understanding Headless Output (`Task: Writing HTML files`)**:
> When executing headless reports via `run-mat-headless` for `org.eclipse.mat.api:suspects`, Eclipse MAT's Equinox reporting engine evaluates chart templates and renders dozens of drill-down HTML pages for suspect components into an archive. While rendering, it outputs `Task: Writing HTML files` progress lines to stdout. This is standard Eclipse MAT report generation, confirming active progress rather than an infinite loop.

---

---

## Performance Benchmarks & Engine Comparison

Benchmark conducted on a 2.22 GB complex heap dump (`complex_bench.hprof`) generated by `ComplexHeapDumpGenerator` with realistic deep topologies (diamond DAGs, cyclic reference loops, multi-level class hierarchies, thread locals, and distinct dominator retained sizes):

| Metric | Native Eclipse MAT | DHP (SQLite WAL) | DHP (PostgreSQL Docker) |
| :--- | :--- | :--- | :--- |
| **Ingestion / Parse Time** | 7.1 s (unbounded RAM) | **13.27 s** (budget-governed) | 35.29 s (asynchronous commit) |
| **JVM Memory Budget** | Unbounded | **1.0 GB RAM** | **1.0 GB RAM** |
| **Index / Storage Size** | 9.95 MB (12 `.index` files) | 57.82 MB (single `.dhp.db`) | 123 MB (relational tables) |
| **Snapshot Cold Open** | ~1.2 s | **772 ms** (0.77 s) | **350 ms** (0.35 s) |
| **Snapshot Warm Open** | ~0.8 s | **41 ms** (0.041 s) | **118 ms** (0.118 s) |
| **Overview Report Generation** | 6.16 s | 10.12 s | ~45 s |
| **Leak Suspects Report** | 7.10 s | 17.52 s | 1m 39s |

### Snapshot Opening Latency Optimizations

Previous versions suffered from a 10–15 second latency when opening snapshots in Eclipse MAT UI. This was resolved through architectural enhancements:

1. **Precomputed Aggregations**:
   - `dhp_class_stats` precalculates instance counts and total sizes during `finishIngestion()`, replacing expensive `GROUP BY` full-table scans.
   - `totalHeapSize` and `numberOfObjects` are pre-stored in `dhp_snapshot_info`, returning in `< 1 ms`.
2. **Pre-Resolved Entity Relationships**:
   - `dhp_classes` directly stores pre-resolved `class_obj_id`, `super_class_obj_id`, `class_loader_obj_id`, and `used_size`.
   - `dhp_gc_roots` stores pre-resolved `object_id` and `thread_object_id`.
   - Eliminates over 30,000 JDBC round-trips during snapshot loading.
3. **Partial B-Tree Indexing & Streaming BitField**:
   - Partial index `idx_dhp_objects_arrays` on `dhp_objects(object_id) WHERE is_array = 1`.
   - Replaced multi-megabyte `boolean[]` allocations with streaming `populateArrayBitField(IntConsumer)` directly into MAT's `BitField`.
4. **Schema Initialization Bypass**:
   - `DhpIndexBuilder` and `DhpSnapshotFactory` detect existing tables and skip redundant DDL checks when opening pre-indexed dumps.

---

## Memory-Bounded Ingestion Architecture & Bitmap Analysis

DHP strictly enforces the user-configured memory budget (`--memory-budget` / `-m`) by replacing open-addressing hash maps and hash sets with memory-bounded, zero-rehashing structures:

### 1. Address-to-ID Map: Sorted Primitive Array vs Open Hash Map

When mapping 64-bit object JVM addresses to sequential MAT 32-bit object IDs:
- **Fastutil `Long2IntOpenHashMap` Problem**: Required $2^{28}$ slots for 100M objects at 0.75 load factor. Its steady-state footprint was ~3.22 GB, and doubling during rehashing caused catastrophic **6.4 GB memory spikes** and `OutOfMemoryError`.
- **DHP's `SortedAddressToIdMap` Solution**: In Pass 1, addresses are gathered and sorted in-place in primitive `long[]`. MAT IDs are dense, contiguous integer offsets `baseInstanceId + index`. Binary search operates directly on the array with **zero value array overhead**.
- **Performance**:
  - Build time for 5,000,000 objects is **14.0 ms** (compared to 412.5 ms for hash map — **29x faster build**).
  - Memory footprint for 100,000,000 objects is strictly **800 MB** (vs 3.2–4.8 GB with hash maps).
  - Zero allocation during lookup, zero JVM GC pauses, and zero rehashing spikes.
- **Off-Heap Support (`MmapAddressToIdMap`)**: For extremely constrained JVM environments (e.g., `-Xmx512m` or `-Xmx1g` on a 150GB dump), `--address-map mmap` writes the sorted long array to an off-heap memory-mapped file, reducing JVM heap consumption to **0 MB**.

### 2. Membership Sets: `java.util.BitSet` vs `RoaringBitmap`

To track written objects during Pass 2 streaming without unbounded `LongOpenHashSet` collections (which previously consumed 1.5–2.0 GB):

| Implementation | Memory (5M objects) | Projected Memory (100M objects) | Read Latency | Strategy |
| :--- | :--- | :--- | :--- | :--- |
| **`BitSetMembershipSet`** (Default) | 610 KB | **11.9 MB** (flat 1 bit/item) | **~2.9 ns/op** | Default for highest ingestion speed |
| **`RoaringMembershipSet`** | < 1 KB | **< 100 KB** (run-compressed) | **~35.3 ns/op** | Best for minimum RAM on dense ranges |

Run benchmarks anytime via:
```bash
mvn test -pl dhp-core -Dtest=MemoryAndBitmapBenchmarkTest
```


---

## License & Attribution

This project is licensed under the [Eclipse Public License v. 2.0 (EPL-2.0)](LICENSE).

This project incorporates and builds upon source code, algorithms, and parser structures from the [Eclipse Memory Analyzer (MAT)](https://www.eclipse.dev/mat) project (Copyright © 2008, 2026 SAP AG, IBM Corporation, and others). See [NOTICE](NOTICE) for full attribution details.
