#!/usr/bin/env bash
# Compares Eclipse MAT's native parser with DHP on one heap dump, through MAT's own API.
#
#   tools/parity-check/run-parity.sh <dump.hprof> <workdir> [--no-reports]
#
# Environment: MAT_HOME (default tools/mat/mat), DHP_JAR (default dhp-cli/target/*-SNAPSHOT.jar), SAMPLE (default 64,
# sampling modulus of the per-object comparison), INSPECT (default 500, sampling modulus of the inspector comparison),
# QUERIES (query file, default tools/parity-check/queries.txt), DHP_XMX (default 4g), MAT_XMX (default 4g),
# REUSE_NATIVE=1 (keep existing native.* outputs, they only depend on the dump), MODES (default "objects loaders
# inspect dominator queries").
# Writes native.*/dhp.* outputs into <workdir> and prints one line per comparison.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
DUMP="$(realpath "$1")"; WORK="$2"; REPORTS=1; [[ "${3:-}" == "--no-reports" ]] && REPORTS=0
MAT_HOME="${MAT_HOME:-$ROOT/tools/mat/mat}"
DHP_JAR="${DHP_JAR:-$(ls "$ROOT"/dhp-cli/target/dhp-cli-*-SNAPSHOT.jar | grep -v original | head -1)}"
SAMPLE="${SAMPLE:-64}"; INSPECT="${INSPECT:-500}"; QUERIES="${QUERIES:-$HERE/queries.txt}"
DHP_XMX="${DHP_XMX:-4g}"; MAT_XMX="${MAT_XMX:-4g}"; MODES="${MODES:-objects loaders inspect dominator classretained queries}"
NAME="$(basename "$DUMP" .hprof)"
mkdir -p "$WORK/native" "$WORK/dhp"
for d in native dhp; do [[ -e "$WORK/$d/$NAME.hprof" ]] || ln "$DUMP" "$WORK/$d/$NAME.hprof" 2>/dev/null || cp "$DUMP" "$WORK/$d/$NAME.hprof"; done
sed -i "s/^-Xmx.*/-Xmx$MAT_XMX/" "$MAT_HOME/MemoryAnalyzer.ini"
APP="$MAT_HOME/MemoryAnalyzer -consolelog -nosplash -application org.eclipse.mat.dhp.parity.dump"
run_mode() { # <side> <snapshot> <out> [mode args...]
  local side="$1" snap="$2" out="$3"; shift 3
  $APP "$snap" "$out" "$@" 2>&1 | grep -E "xception|Error:" | grep -v "^\[INFO\]" | head -3 || true
}

echo "== DHP ingest"
( cd "$WORK/dhp" && java -Xmx"$DHP_XMX" -jar "$DHP_JAR" -d "$NAME.hprof" --clean 2>&1 | grep -E "finished in|Exception|OutOfMemory" )

for side in native dhp; do
  [[ $side == native ]] && SNAP="$WORK/native/$NAME.hprof" || SNAP="$WORK/dhp/$NAME.dhp"
  echo "== $side: dumping"
  for mode in $MODES; do
    case $mode in
      objects) out="$WORK/$side.objects.tsv"; extra=("$SAMPLE") ;;
      loaders) out="$WORK/$side.loaders.txt"; extra=(loaders) ;;
      inspect) out="$WORK/$side.inspect.txt"; extra=(inspect "$INSPECT") ;;
      dominator) out="$WORK/$side.dominator.txt"; extra=(dominator) ;;
      classretained) out="$WORK/$side.classretained.txt"; extra=(classretained) ;;
      queries) out="$WORK/$side.queries.txt"; extra=(queries "$QUERIES") ;;
    esac
    if [[ $side == native && "${REUSE_NATIVE:-0}" == 1 && -s "$out" ]]; then continue; fi
    run_mode $side "$SNAP" "$out" "${extra[@]}"
  done
done

echo "== comparison"
status=0
LC_ALL=C sort -t$'\t' -k1,1 "$WORK/native.objects.tsv" > "$WORK/native.objects.s.tsv"
LC_ALL=C sort -t$'\t' -k1,1 "$WORK/dhp.objects.tsv" > "$WORK/dhp.objects.s.tsv"
python3 -I "$HERE/compare.py" "$WORK/native.objects.s.tsv" "$WORK/dhp.objects.s.tsv" 3 | tail -9 || status=1
cut -f1 "$WORK/native.objects.tsv" | cmp -s - <(cut -f1 "$WORK/dhp.objects.tsv") && echo "object ids: identical" || { echo "object ids: DIFFERENT"; status=1; }
for k in $(echo $MODES | tr ' ' '\n' | grep -v '^objects$'); do
  f=txt
  if [[ $k == queries ]]; then
    # identity hash codes and stack traces differ by construction: compare per query after normalizing them
    res="$(python3 -I "$HERE/query_diff.py" "$WORK/native.$k.$f" "$WORK/dhp.$k.$f" 3)"
    echo "$res" | tail -1 | grep -q "'ORDER-ONLY': 0, 'DIFFERENT': 0" && echo "queries: identical ($(echo "$res" | tail -1))" || { echo "queries: DIFFERENT"; echo "$res"; status=1; }
    continue
  fi
  if diff -q "$WORK/native.$k.$f" "$WORK/dhp.$k.$f" >/dev/null; then echo "$k: identical ($(wc -l < "$WORK/native.$k.$f") lines)"
  else echo "$k: DIFFERENT ($(diff "$WORK/native.$k.$f" "$WORK/dhp.$k.$f" | grep -c '^[<>]') differing lines) -> diff $WORK/native.$k.$f $WORK/dhp.$k.$f"; status=1; fi
done
if [[ $REPORTS == 1 ]]; then
  echo "== standard reports"
  (cd "$WORK/native" && "$MAT_HOME/ParseHeapDump.sh" "$NAME.hprof" org.eclipse.mat.api:suspects org.eclipse.mat.api:overview org.eclipse.mat.api:top_components >/dev/null 2>&1)
  (cd "$WORK/dhp" && "$MAT_HOME/ParseHeapDump.sh" "$NAME.dhp" org.eclipse.mat.api:suspects org.eclipse.mat.api:overview org.eclipse.mat.api:top_components >/dev/null 2>&1)
  for r in Leak_Suspects System_Overview Top_Components; do
    python3 -I "$HERE/compare_reports.py" "$WORK/native/${NAME}_$r.zip" "$WORK/dhp/${NAME}_$r.zip" | tail -1 || status=1
  done
fi
exit $status
