# AGENTS.md — Dynamic Heap Parser (DHP) Developer & Agent Guide

Welcome to the **Dynamic Heap Parser (DHP)** repository. This document serves as the primary technical specification, architectural reference, and operational playbook for AI agents and human developers maintaining, debugging, or extending this project.

---

## 1. Project Mission & Core Architecture

DHP is a high-throughput, constant-memory HPROF heap dump ingestion and graph engine designed as a drop-in storage and index provider for **Eclipse Memory Analyzer (MAT)**.

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
                     │      Bulk B-Tree Index Generation      │
                     │  - Unique PKs on object_id & address   │
                     │  - Composite indexes on references     │
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
| **`dhp-core`** | `org.eclipse.mat.dhp:dhp-core` | Core parsing and graph logic: `HprofBinaryReader`, `Pass1ScanParser`, `Pass2ObjectIngester`, `DominatorTreeEngine`, `MemoryGovernor`, `HeapRecords`, and `HprofConstants`. Contains zero JDBC code. |
| **`dhp-storage-jdbc`** | `org.eclipse.mat.dhp:dhp-storage-jdbc` | JDBC storage abstraction and engine: `HeapStorageEngine` interface and `JdbcHeapStorageEngine` implementation supporting SQLite WAL and PostgreSQL with HikariCP connection pooling and bulk indexing. |
| **`dhp-cli`** | `org.eclipse.mat.dhp:dhp-cli` | Command-line interface (`DhpMain`) using Picocli to parse dumps directly from the shell or execute headless database staging. Packaged as a fat JAR with `maven-shade-plugin`. |
| **`dhp-mat-plugin`** | `org.eclipse.mat.dhp:dhp-mat-plugin` | Eclipse MAT plugin implementing `IIndexBuilder` (`DhpIndexBuilder`) and `IObjectReader` (`DhpHeapObjectReader`), database-backed index adapters (`DbOne2LongIndex`, `DbOne2OneIndex`, `DbOne2ManyIndex`, `DbOne2SizeIndex`), packaged as an OSGi bundle with `plugin.xml` and shaded runtime dependencies. |

---

## 3. Database Schema & Indexing Strategy

To achieve constant memory usage and avoid intermediate lock contention during ingestion:
1. **Raw Ingestion Phase**: Tables are created without secondary indexes or primary key constraints. Records are inserted in large batches (`INSERT INTO dhp_objects...`, `INSERT INTO dhp_outbound_references...`).
2. **Bulk Index Phase**: Once Pass 2 finishes, indexes and constraints are created in parallel via `CREATE UNIQUE INDEX` and `CREATE INDEX`.

### Tables

- **`dhp_snapshot_info`**: Key-value pairs for snapshot metadata (`idSize`, `creationDate`, `totalHeapSize`, `numberOfObjects`). Precomputes aggregates in `finishIngestion()` to achieve sub-millisecond retrieval.
- **`dhp_classes`**: Stores `class_id`, `super_class_id`, `class_loader_id`, `class_name`, `instance_size`, `fields_data`, `static_fields_data`, and pre-resolved `class_obj_id`, `super_class_obj_id`, `class_loader_obj_id`, and `used_size`. Supports full Class Hierarchy navigation and Static Fields in MAT's Inspector view.
- **`dhp_class_stats`**: Stores pre-aggregated `class_id`, `instance_count`, and `total_size` populated in `finishIngestion()`, eliminating costly full-table `GROUP BY` aggregations during snapshot open.
- **`dhp_objects`**: Primary entity table storing `object_id` (0-indexed integer), `object_address` (JVM 64-bit pointer), `class_id`, `used_size` (shallow size), `file_position` (byte offset in `.hprof`), and `is_array`. Indexed by `idx_dhp_objects_arrays` partial index (`WHERE is_array = 1`).
- **`dhp_outbound_references`**: Directed edges `(from_object_id, seq, to_object_id)` representing object references.
- **`dhp_inbound_references`**: Reverse edges `(to_object_id, from_object_id)` for incoming references.
- **`dhp_gc_roots`**: Root pointers `(object_id, object_address, referrer_address, root_type, thread_address, thread_object_id)` with pre-resolved `object_id` and `thread_object_id`.
- **`dhp_dominator_tree`**: Dominator computation results: `object_id`, `dominator_id`, and `retained_size`.

### Storage Model & File Size Disparity (Why 58MB SQLite for a 2.2GB Dump)
In HPROF format, 85-95% of file size comprises primitive array payload buffers (`byte[]`, `char[]`, `int[]`). DHP stores the object graph topology, relationships, metadata, and dominator trees in SQLite (~58 MB), while recording the exact byte offset (`file_position`) in `dhp_objects`. `DhpHeapObjectReader` performs zero-memory-overhead random seeks into `.hprof` only when array contents or instance field values are inspected, matching Eclipse MAT's native architecture where index files are similarly 15-30 MB.

---

## 4. Dominator Tree Algorithm (Lengauer-Tarjan)

`DominatorTreeEngine` implements the classic Lengauer-Tarjan algorithm for dominator tree computation:
- **Artificial Super Root**: Connects to all GC root objects to provide a single entry point for Depth-First Search (DFS).
- **Semi-dominators**: Computed using path compression with disjoint sets (`ancestor`, `label`, `semi`).
- **Immediate Dominators**: Resolves immediate dominator (`idom`) for all reachable heap objects.
- **Retained Sizes**: Computed bottom-up across the dominator tree by accumulating shallow sizes from leaves to roots.
- **MAT Parity**: The resulting tree conforms to Eclipse MAT's dominator semantics where super-root children represent top-level GC dominators.

---

## 5. Eclipse MAT Plugin Integration

### File Extensions & Descriptors
The plugin binds **strictly to `.dhp` files**:
- `.dhp` files serve as database descriptors pointing to the SQLite DB or PostgreSQL instance and the underlying `.hprof` file.
- Example descriptor:
  ```properties
  db.url=jdbc:sqlite:/path/to/heapdump.dhp.db
  dump.file=/path/to/heapdump.hprof
  memory.budget=4G
  ```
- If the database is not yet ingested, `DhpIndexBuilder` will ingest the `.hprof` into the database on the fly. If already ingested (via `dhp-cli`), it reuses the precomputed tables and dominator tree immediately without generating legacy MAT index files.

### Automation & Tooling Scripts (`scripts/`)
Cross-platform scripts (Linux, macOS, Windows) streamline installation and headless execution:
- **`scripts/setup-all.sh` / `setup-all.bat`**: Master setup: installs local MAT dependencies to Maven, compiles the reactor, sets up Eclipse MAT, and installs the DHP bundle.
- **`scripts/setup-deps.sh` / `setup-deps.bat`**: Installs `lib/*.jar` into the local Maven cache (`~/.m2`).
- **`scripts/setup-mat.sh` / `setup-mat.bat` / `setup-mat.ps1`**: Sets up Eclipse MAT into `tools/mat/mat` (reusing local cache or downloading the official RCP release).
- **`scripts/install-mat-plugin.sh` / `install-mat-plugin.bat`**: Deploys the built DHP shaded OSGi bundle to `MAT_HOME/plugins/`, registers it in `bundles.info`, and purges the OSGi cache.
- **`scripts/install-p2-dependency.sh` / `install-p2-dependency.bat`**: Wrapper around `MemoryAnalyzer -application org.eclipse.equinox.p2.director` to install p2 update site features.
- **`scripts/run-mat-headless.sh` / `run-mat-headless.bat`**: Headless report generation using `ParseHeapDump` against `.dhp` descriptor files.

### Object & Array Payload Reading
`DhpHeapObjectReader` utilizes `RandomAccessFile` and indexed `file_position` from the database:
- Reads primitive arrays directly from binary offsets into typed Java arrays (`boolean[]`, `byte[]`, `char[]`, `int[]`, `long[]`, etc.).
- Reads object arrays and resolves reference pointers.
- Reconstructs instance field values according to JVM class hierarchy specification (base class fields first, subclass fields appended).

---

## 6. Build, Test, and Packaging Instructions

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

## 7. Installing into Eclipse Memory Analyzer (MAT)

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

## 8. CLI Usage Examples

### Parse into SQLite
```bash
java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar \
  --dump /path/to/heapdump.hprof \
  --jdbcurl jdbc:sqlite:/path/to/heapdump.db \
  --memory-budget 2147483648 \
  --threads 4
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

## 9. Developer & Coding Guidelines

1. **Memory Budget Discipline**: When implementing graph algorithms or data transformations, never load arbitrary unbounded collections into JVM memory. Use streamed queries, paginated queries, or memory-mapped primitives (`fastutil` primitive collections).
2. **Deterministic Parity**: Any changes to object size calculation, dominator computation, or hierarchy traversal must pass `EclipseMatEquivalenceParityTest` and `EclipseMatGeneralSnapshotTestSuiteTest`.
3. **OSGi & Serialization Safety**: In the MAT plugin, never store non-serializable objects (such as active connections or lambdas referencing JDBC components) in `XSnapshotInfo.properties`. MAT serializes snapshot metadata to disk when saving indexes.
