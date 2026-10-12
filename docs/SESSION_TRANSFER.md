# DHP Session Transfer Document

Written 2026-10-12 on the Raspberry Pi 5 (4 cores, 8 GB RAM, eMMC disk, SSH-only), for continuing on a laptop (16 GB RAM, NVMe, GUI).
Read this together with `AGENTS.md` and `README.md`. It records what was learned, measured, and decided so work can resume without redoing it.

## 1. Goal (from the user)
Debug and improve DHP performance on the 11 GB heap dump (`dump_11g.hprof`, 47.9M objects) with `-Xmx4g`.
- Keep memory bounded, but do not trade away so much speed that parsing takes forever.
- Find bottlenecks by testing, by reasoning, or by reading Eclipse MAT's source (clone it if useful).
- Pre-compute the queries MAT runs when it opens a snapshot.
- Parallelize as far as possible without exceeding `-Xmx`.
- The user allowed `git clean -fdx` and wiping ignored files and the MAT install. Never delete the 11 GB hprof.

## 2. Project summary
Java 21 Maven reactor: `dhp-core` (parsing, memory structures, dominator tree, no JDBC), `dhp-storage-jdbc` (`JdbcHeapStorageEngine`, SQLite WAL or Postgres), `dhp-cli` (`DhpMain`, picocli, shaded jar), `dhp-mat-plugin` (OSGi bundle with `DhpIndexBuilder`, `DhpHeapObjectReader` and `DbOne2*` index adapters).

Pipeline (see `DhpMain.call()`):
1. **Pass 1** (`Pass1ScanParser`): scans strings, classes, GC roots and all instance addresses. Sorts addresses with `InPlaceLongSort`.
2. **Pass 2** (`Pass2ObjectIngester`): streams objects and edges into unindexed tables. `JdbcHeapStorageEngine.finishIngestion()` then builds indexes sequentially, pre-resolves class and GC-root ids, and computes stats.
3. **Dominator tree** (`DominatorTreeEngine`): loads the outbound CSR and runs iterative Lengauer-Tarjan, then `finishDominatorTree()` builds its indexes.

The `.dhp` descriptor written next to the dump is what MAT opens. Array contents are not stored; the plugin seeks into the hprof using `file_position`.

Commit history is in `git log`. Oct 9 was a series of out-of-memory fixes (sorted address map, in-place sort, string pruning, CSR graph, `--estimate`).

## 3. Environment notes (Pi)
- Run commands from the repo root. `java`, `mvn` 3.9.9 and JDK 21 are installed. `~/.m2` already had the MAT 1.17.0 jars.
- zsh gotcha: quote args containing `*`, e.g. `'-Xlog:gc*:file=/tmp/gc.log'`. Unquoted, zsh fails with "no matches found".
- `nohup ... &` did not survive the tool sandbox. Use the tool's background mode instead.
- Downloading MAT (`scripts/setup-mat.sh`) ran at about 40 KB/s on the Pi. On the laptop it should be fast.
- Quick test dump: `java -Xmx1g tools/dump-generator/ComplexHeapDumpGenerator.java /tmp/dhp/small.hprof 0.05`. This gives about 142 MB and 325k objects.
- Big synthetic dump generator: `tools/dump-generator/StreamingHeapDumpGenerator.java <out> [sizeGB] [objectCountM]`. The 11 GB dump was presumably made with it. A scaled copy such as `1.1 5` would be a good fast benchmark with a similar shape, at about 230 bytes per object.
- Neither the 11 GB dump nor its database is in git (`.gitignore` covers `*.hprof` and `*.db`). The laptop needs its own dump; regenerate with the streaming generator.

## 4. What was done
1. Read commits, README, AGENTS.md, scripts and core code.
2. Small-dump end-to-end test (all working):
   - Build: `mvn -o package -pl dhp-cli -am -DskipTests`, about 11 s.
   - `--estimate`: Pass 1 in 0.56 s, estimated 88 MB of heap.
   - Full ingest (`-Xmx1g -m 768M -t 4 --monitor`): 7.9 s total. Pass 1 0.52 s, Pass 2 5.36 s, dominator 1.76 s. Peak heap 65 MB. Database 95 MB.
   - `scripts/run-mat-headless.sh small.dhp org.eclipse.mat.api:suspects` produced a leak-suspects zip in 29 s with the installed plugin.
   - The dominator DFS reached only 243,738 of 327,682 stored objects from the GC roots, so about 26% of the stored objects are unreachable.
3. Cleaned the tree as authorized: `git clean -fdx` with the hprof moved aside and back. This deleted `benchmarks/`, `tools/mat/`, all `target/` directories, the old 17 GB `dump_11g.dhp.db` and `workspace/`.
4. Ran `scripts/setup-all.sh`. Steps 1-2 passed (deps, `mvn clean install -DskipTests`, 22 s). Step 3, the MAT download, was only 26% done when I stopped, so steps 3-4 are NOT finished on the Pi.
5. Started the 11 GB parse: `java -Xmx4g '-Xlog:gc*:file=/tmp/gc11.log' -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar -d dump_11g.hprof --monitor --clean`, logging to `/tmp/parse11.log`. It was still running when this was written, so its final result is unknown.

## 5. Measurements so far, 11 GB dump, -Xmx4g, Pi
| Item | Result |
|---|---|
| Pass 1 | 154 s for 47,877,279 objects, 2,009 classes, 2,000 GC roots. Heap about 1.5 GB after Pass 1. |
| `--estimate` banner | peak heap 3.08 GB, recommends -Xmx4g, free disk 13.76 GB |
| Pass 2 | Started 14:48:38. The database grew about 8 MB/s: 463 MB after about 1 min, 977 MB after about 2 min. Linear extrapolation to the old 17 GB size gives 30-40 min for Pass 2 alone, before index builds and the dominator pass. Java CPU was about 55% (not CPU-saturated). Heap 0.9-1.5 GB of 4 GB. |
| Address map | `SortedAddressToIdMap`, about 365 MB |

I did not get a thread dump of Pass 2 (the user stopped that command), so where Pass 2's time goes is still unmeasured. Take a thread dump or profile first.

## 6. Bottleneck hypotheses (code read, not yet profiled)
1. **`HprofBinaryReader` (original)** reads one byte per call through a synchronized `BufferedInputStream`, and Pass 1 and Pass 2 both use it for every field. A custom buffer with array decoding and real seeks for skips should help both passes. I wrote this rewrite (same public API plus `readFully(byte[],int)`) but it is NOT committed and NOT compiled or tested. It would be an uncommitted working-tree change on the Pi only, so redo it on the laptop (spec: 1 MB private `byte[]` buffer, fast paths for `readInt`/`readLong`, `FileChannel.position` seeks when a skip is 1 MB or more, read-through for small remainders and for `.gz`).
2. **Pass 2 is single-threaded and writes synchronously.** `insertObjectsBatch` and `insertOutboundReferencesBatch` run on the parsing thread, with one commit per batch (250k rows at a 4 GB budget). A bounded-queue writer thread would overlap parsing and DB writes.
3. **Write volume is the likely limit on eMMC.** `insertOutboundReferencesBatch` writes every edge to both `dhp_outbound_references` and `dhp_inbound_references`, in WAL mode, so each edge row is written twice and then written again at checkpoint. Ideas:
   - Skip the inbound insert during ingestion and build the inbound table later in `to_object_id` order (for example from the in-memory CSR that the dominator pass already builds), as a clustered or WITHOUT ROWID table.
   - Use `journal_mode=OFF` or `TRUNCATE` and `synchronous=OFF` during bulk load, then switch to WAL at the end.
   - Drop the redundant `seq` column or index if MAT's access pattern allows it.
4. **Per-record overhead in Pass 2:**
   - `addressToId.containsKey(x)` followed by `.get(x)` does two binary searches over 48M longs. A single lookup returning -1 would halve that.
   - `pass1.getClasses().get(classAddr)` is a boxed `HashMap<Long,...>` lookup per instance.
   - `readBytes` allocates a new array per instance.
   - Every edge is a `ReferenceEdge` object (about 100M+ allocations). Primitive parallel arrays would remove this.
5. **`finishIngestion()` builds indexes one after another** on one connection (`idx_dhp_objects_pk`, `idx_dhp_outbound_pk`, `idx_dhp_objects_address`, `idx_dhp_objects_class`, `idx_dhp_objects_arrays`, `idx_dhp_inbound_to`, `idx_dhp_gc_roots_obj`). SQLite allows one writer, so parallelism is limited. Do not trust the `PRAGMA threads=4` setting to help; measure. Also note the `UPDATE ... (SELECT ... WHERE object_address = ...)` gc_roots pass, and avoid any non-indexed fallback paths.
6. **Dominator pass** (`DominatorTreeEngine.computeAndStore`): loads the CSR graph from the database, builds the in-edge CSR, and runs L-T. For 48M objects and a few hundred million edges this needs several large `int[]` arrays inside `-Xmx4g`. It has not been measured at 11 GB. Heap headroom is the thing to check.
7. **MAT-side queries**: the goal is to pre-compute anything MAT issues on open or on the common reports. The plan was to clone Eclipse MAT (`https://github.com/eclipse-mat/org.eclipse.mat`), read `dhp-mat-plugin`'s `DbOne2*Index` / `DhpSnapshotFactory` / `DhpIndexBuilder` call sites against MAT's parser API, and profile the leak-suspects report on the 11 GB database. The earlier benchmarks show that report as 17.5 s for DHP SQLite versus 7.1 s for native MAT on a 2.2 GB dump.

## 7. Suggested next steps (in order)
1. On the laptop: clone, run `./scripts/setup-all.sh` (it should download MAT quickly), then generate or copy an 11 GB-class dump.
2. Record a baseline: `java -Xmx4g -jar dhp-cli/target/dhp-cli-1.0.0-SNAPSHOT.jar -d <dump> --monitor --clean`, with Pass 1/2/3 times and peak heap. Keep the numbers beside the Pi's (Pass 1 154 s).
3. Profile Pass 2 first with a thread dump, async-profiler, or JFR. Decide between parser-bound and write-bound before changing code.
4. Implement in this order, measuring each: fast reader, then single-lookup address map and primitive edge buffers, then writer-thread pipeline, then the inbound-table and journal-mode changes, then index-build and dominator memory.
5. After each change run `mvn test` (including `EclipseMatEquivalenceParityTest` and `EclipseMatGeneralSnapshotTestSuiteTest`, required by AGENTS.md section 10) and re-run the small-dump headless MAT report to confirm parity.

## 8. Standing instructions in this repo/session
- User-level instruction: any code review must be delegated to codex (the `codex` MCP server, or `codex exec` via Bash), and disagreements with it must be surfaced.
- AGENTS.md section 10: memory-budget discipline (no unbounded hash maps for full-dump indices), deterministic parity tests, OSGi serialization safety, cross-platform path safety.
- Commit attribution trailer used in this session: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## 9. State of the Pi working tree at handoff
- Only change relative to HEAD: the untested `HprofBinaryReader.java` rewrite (uncommitted, not included in this commit).
- Generated, untracked, ignored: `target/` directories, `tools/mat/` (incomplete download), `dump_11g.dhp.db*` (partial), `/tmp/dhp/`, `/tmp/parse11.log`, `/tmp/gc11.log`, `/tmp/setup-all.log`.
- The 11 GB parse and the MAT download may still be running or may have been killed when the session ended. Check with `pgrep -af 'dhp-cli|curl'`.
