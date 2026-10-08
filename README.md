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
- **Complete Class Hierarchy Traversal**: Handles multi-level inheritance, all 8 Java primitive types (`boolean`, `byte`, `char`, `short`, `int`, `long`, `float`, `double`), and object references with subclass-first layout matching JVM specifications.
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

**Windows:**
```bat
git clone https://github.com/prkprime/DHP.git
cd DHP
scripts\setup-all.bat
```

### 2. Modular Convenience Scripts

- **Install Local Dependencies to Maven**:
  - Linux/macOS: `./scripts/setup-deps.sh`
  - Windows: `scripts\setup-deps.bat`
- **Setup Eclipse MAT Tooling**:
  - Linux/macOS: `./scripts/setup-mat.sh [target_dir]`
  - Windows: `scripts\setup-mat.bat` or `scripts\setup-mat.ps1`
- **Install DHP Plugin into MAT**:
  - Linux/macOS: `./scripts/install-mat-plugin.sh`
  - Windows: `scripts\install-mat-plugin.bat`
- **Install P2 Dependencies into MAT**:
  - Linux/macOS: `./scripts/install-p2-dependency.sh <repository-url> <iu-id>`
  - Windows: `scripts\install-p2-dependency.bat <repository-url> <iu-id>`
- **Run Headless Analysis on `.dhp`**:
  - Linux/macOS: `./scripts/run-mat-headless.sh <dump.dhp> [reports...]`
  - Windows: `scripts\run-mat-headless.bat <dump.dhp> [reports...]`

---

## CLI Usage

The standalone fat JAR is located at `dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar`.

### CLI Options

| Flag | Aliases | Description |
| :--- | :--- | :--- |
| `-d` | `--dump` | Path to the input `.hprof` heap dump file |
| `-c` | `--config` | Path to DHP descriptor file (`.dhp`) |
| `--db` | `--url`, `--db-url`, `--jdbcurl` | Database name or JDBC connection URL |
| `-m` | `--memory`, `--memory-budget` | Memory budget (e.g. `512M`, `2G`, `4GB`, or raw bytes). Defaults to 75% of JVM `-Xmx` |
| `-t` | `-w`, `--threads`, `--workers` | Worker threads count |
| `--clean` | `--drop-existing` | Drop pre-existing DHP tables in target database before ingestion |
| `--export-dhp` | | Path to export `.dhp` MAT descriptor (defaults to `<prefix>.dhp`) |

### Examples

**Parse directly into SQLite WAL database:**
```bash
java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar \
  --dump /path/to/heapdump.hprof \
  --url jdbc:sqlite:/path/to/heapdump.dhp.db \
  --memory 2G \
  --threads 4
```

**Stream to PostgreSQL:**
```bash
java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar \
  --dump /path/to/heapdump.hprof \
  --db jdbc:postgresql://localhost:5432/heapdumps \
  --user postgres \
  --password secret \
  --memory 4G
```

---

## Opening in Eclipse Memory Analyzer (MAT)

DHP registers **strictly the `.dhp` extension** in Eclipse MAT to bypass the legacy memory-intensive `.idx` file generation entirely.

1. Ingest your dump using CLI:
   ```bash
   java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar -d /path/to/dump.hprof
   # Generates /path/to/dump.dhp and /path/to/dump.dhp.db
   ```
2. Open `/path/to/dump.dhp` directly in Eclipse MAT GUI or run headlessly:
   ```bash
   ./scripts/run-mat-headless.sh /path/to/dump.dhp org.eclipse.mat.api:suspects
   ```
3. Eclipse MAT opens the snapshot immediately without re-parsing, using direct SQL queries against SQLite/PostgreSQL and generating reports with zero disk index files!

---

## Testing

Run unit and integration tests across all reactor modules:

```bash
mvn test
```

Includes test suites verifying:
- Duplicate object and class dump handling (`DuplicateRecordHandlingTest`)
- Zero-length segment and unknown tag resilience (`HprofStreamEdgeCasesTest`)
- Multi-level class hierarchy and primitive type extraction (`PrimitiveAndInheritedFieldsTest`)
- Eclipse MAT equivalence and parity against standard MAT test dumps (`EclipseMatEquivalenceParityTest`)

---

## License & Attribution

This project is licensed under the [Eclipse Public License v. 2.0 (EPL-2.0)](LICENSE).

This project incorporates and builds upon source code, algorithms, and parser structures from the [Eclipse Memory Analyzer (MAT)](https://www.eclipse.dev/mat) project (Copyright © 2008, 2026 SAP AG, IBM Corporation, and others). See [NOTICE](NOTICE) for full attribution details.
