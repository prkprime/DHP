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

## Quick Start

### Prerequisites

- **Java**: JDK 21 or higher
- **Maven**: 3.9+

### Build

```bash
git clone https://github.com/prkprime/DHP.git
cd DHP
mvn clean install
```

### CLI Usage

Parse an `.hprof` dump directly into an optimized SQLite database:

```bash
java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar \
  --dump /path/to/heapdump.hprof \
  --db jdbc:sqlite:/path/to/heapdump.db \
  --memory 2G
```

Or stream directly to a PostgreSQL database for multi-user analysis:

```bash
java -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar \
  --dump /path/to/heapdump.hprof \
  --db jdbc:postgresql://localhost:5432/heapdumps \
  --user postgres \
  --password secret \
  --memory 4G
```

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
