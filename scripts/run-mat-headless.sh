#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage: scripts/run-mat-headless.sh <dump.dhp> [report-ids...]

Runs Eclipse Memory Analyzer (MAT) in headless mode using ParseHeapDump
against a DHP descriptor file.

Arguments:
  dump.dhp        Path to .dhp descriptor file
  report-ids      One or more report identifiers (default: org.eclipse.mat.api:suspects)
                  Common reports:
                    org.eclipse.mat.api:suspects
                    org.eclipse.mat.api:top_components
                    org.eclipse.mat.api:overview

Environment:
  MAT_HOME        MAT installation directory (default: tools/mat/mat)
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 1 ]]; then
  usage
  exit 0
fi

DHP_FILE="$1"
shift

REPORTS=("$@")
if [[ ${#REPORTS[@]} -eq 0 ]]; then
  REPORTS=("org.eclipse.mat.api:suspects")
fi

MAT_DIR="${MAT_HOME:-${ROOT_DIR}/tools/mat/mat}"
PARSE_SCRIPT="${MAT_DIR}/ParseHeapDump.sh"

if [[ ! -x "${PARSE_SCRIPT}" ]]; then
  echo "ParseHeapDump.sh not found at ${PARSE_SCRIPT}. Running setup-mat.sh..."
  "${SCRIPT_DIR}/setup-mat.sh" "${MAT_DIR}"
fi

# Ensure DHP plugin is installed in MAT
BUNDLES_INFO="${MAT_DIR}/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
if ! grep -q "^org\.eclipse\.mat\.dhp," "${BUNDLES_INFO}" 2>/dev/null; then
  echo "DHP plugin not detected in bundles.info. Running install-mat-plugin.sh..."
  "${SCRIPT_DIR}/install-mat-plugin.sh"
fi

# Convert DHP_FILE to absolute path
if [[ ! "$DHP_FILE" = /* ]]; then
  DHP_FILE="$(pwd)/${DHP_FILE}"
fi

if [[ ! -f "$DHP_FILE" ]]; then
  echo "Error: DHP file does not exist: ${DHP_FILE}" >&2
  exit 1
fi

echo "=== Running Eclipse MAT Headless Parse ==="
echo "DHP Descriptor: ${DHP_FILE}"
echo "Reports:        ${REPORTS[*]}"
echo "MAT Home:       ${MAT_DIR}"

"${PARSE_SCRIPT}" "${DHP_FILE}" "${REPORTS[@]}"

echo "=== Headless analysis complete! ==="
