#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage: scripts/install-p2-dependency.sh <repository-url> <iu-id> [extra-p2-args...]
       scripts/install-p2-dependency.sh -r <repo-url> -i <iu-id>

Installs dependencies or features from an Eclipse p2 repository into Eclipse Memory Analyzer (MAT)
using the Equinox p2 director headless application.

Arguments:
  repository-url   URL of the p2 update site repository
  iu-id            Installable Unit ID (e.g., org.eclipse.mat.chart.feature.feature.group)

Environment:
  MAT_HOME         MAT installation directory (default: tools/mat/mat)

Examples:
  scripts/install-p2-dependency.sh https://download.eclipse.org/mat/1.17.0/update-site/ org.eclipse.mat.chart.feature.feature.group
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 2 ]]; then
  usage
  exit 0
fi

REPO=""
IU=""
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -r|--repository)
      REPO="$2"
      shift 2
      ;;
    -i|--installIU|--iu)
      IU="$2"
      shift 2
      ;;
    *)
      if [[ -z "${REPO}" ]]; then
        REPO="$1"
      elif [[ -z "${IU}" ]]; then
        IU="$1"
      else
        EXTRA_ARGS+=("$1")
      fi
      shift
      ;;
  esac
done

if [[ -z "${REPO}" || -z "${IU}" ]]; then
  echo "Error: Both repository URL and installable unit (IU) ID must be provided." >&2
  usage
  exit 1
fi

MAT_DIR="${MAT_HOME:-${ROOT_DIR}/tools/mat/mat}"
MAT_BIN="${MAT_DIR}/MemoryAnalyzer"

if [[ ! -x "${MAT_BIN}" ]]; then
  echo "Eclipse MAT executable not found at ${MAT_BIN}. Running setup-mat.sh first..."
  "${SCRIPT_DIR}/setup-mat.sh" "${MAT_DIR}"
fi

echo "=== Installing p2 Dependency ==="
echo "MAT Home:   ${MAT_DIR}"
echo "Repository: ${REPO}"
echo "Unit (IU):  ${IU}"

"${MAT_BIN}" \
  -consolelog \
  -nosplash \
  -application org.eclipse.equinox.p2.director \
  -destination "${MAT_DIR}" \
  -repository "${REPO}" \
  -installIU "${IU}" \
  "${EXTRA_ARGS[@]}"

echo "=== p2 dependency installation finished successfully! ==="
