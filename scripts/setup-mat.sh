#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage: scripts/setup-mat.sh [target_dir]

Sets up Eclipse Memory Analyzer (MAT) in tools/mat/mat.
Reuses local installation if available, otherwise downloads from Eclipse mirrors.

Arguments:
  target_dir   Target MAT installation folder (default: tools/mat/mat)

Environment:
  MAT_HOME     Overrides target installation directory
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

MAT_DIR="${MAT_HOME:-${1:-${ROOT_DIR}/tools/mat/mat}}"
PARENT_DIR="$(dirname "${MAT_DIR}")"
mkdir -p "${PARENT_DIR}"

if [[ -x "${MAT_DIR}/MemoryAnalyzer" && -f "${MAT_DIR}/ParseHeapDump.sh" ]]; then
  echo "Eclipse MAT already installed at ${MAT_DIR}"
  exit 0
fi

# Check if pre-existing MAT exists in sibling directory
LOCAL_MAT_CANDIDATE="/home/prk/easy-heap-dump-parser/tools/mat/mat"
if [[ -d "${LOCAL_MAT_CANDIDATE}" && -x "${LOCAL_MAT_CANDIDATE}/MemoryAnalyzer" ]]; then
  echo "Found local MAT installation at ${LOCAL_MAT_CANDIDATE}. Copying..."
  cp -r "${LOCAL_MAT_CANDIDATE}" "${MAT_DIR}"
  chmod +x "${MAT_DIR}/MemoryAnalyzer" "${MAT_DIR}/ParseHeapDump.sh"
  echo "Successfully set up MAT at ${MAT_DIR}"
  exit 0
fi

# Determine OS and Arch
OS="$(uname -s)"
ARCH="$(uname -m)"

case "${OS}" in
  Linux)
    OS_NAME="linux"
    ;;
  Darwin)
    OS_NAME="macosx"
    ;;
  *)
    echo "Unsupported OS for automatic download: ${OS}. Please install MAT manually into ${MAT_DIR}." >&2
    exit 1
    ;;
esac

case "${ARCH}" in
  x86_64|amd64)
    ARCH_NAME="x86_64"
    ;;
  aarch64|arm64)
    ARCH_NAME="aarch64"
    ;;
  *)
    echo "Unsupported architecture: ${ARCH}." >&2
    exit 1
    ;;
esac

if [[ "${OS_NAME}" == "linux" ]]; then
  CLASSIFIER="linux.gtk.${ARCH_NAME}"
elif [[ "${OS_NAME}" == "macosx" ]]; then
  CLASSIFIER="macosx.cocoa.${ARCH_NAME}"
fi

VERSION="1.17.0.20260601"
BASE_VERSION="1.17.0"
ARCHIVE="MemoryAnalyzer-${VERSION}-${CLASSIFIER}.zip"
DEST_ZIP="${PARENT_DIR}/${ARCHIVE}"

URLS=(
  "https://www.eclipse.org/downloads/download.php?file=/mat/${BASE_VERSION}/rcp/${ARCHIVE}&r=1"
  "https://download.eclipse.org/mat/${BASE_VERSION}/rcp/${ARCHIVE}"
  "https://mirror.umd.edu/eclipse/mat/${BASE_VERSION}/rcp/${ARCHIVE}"
)

echo "Downloading Eclipse MAT ${VERSION} for ${CLASSIFIER}..."
DOWNLOADED=0
for url in "${URLS[@]}"; do
  echo "Trying: ${url}"
  if curl -L --fail --output "${DEST_ZIP}.tmp" "${url}"; then
    mv "${DEST_ZIP}.tmp" "${DEST_ZIP}"
    DOWNLOADED=1
    break
  fi
  rm -f "${DEST_ZIP}.tmp"
done

if [[ "${DOWNLOADED}" -ne 1 ]]; then
  echo "Failed to download MAT from mirrors." >&2
  exit 1
fi

echo "Extracting ${DEST_ZIP} to ${PARENT_DIR}..."
unzip -q -o "${DEST_ZIP}" -d "${PARENT_DIR}"
rm -f "${DEST_ZIP}"

if [[ -f "${MAT_DIR}/MemoryAnalyzer" ]]; then
  chmod +x "${MAT_DIR}/MemoryAnalyzer"
fi
if [[ -f "${MAT_DIR}/ParseHeapDump.sh" ]]; then
  chmod +x "${MAT_DIR}/ParseHeapDump.sh"
fi

echo "Eclipse MAT successfully installed at ${MAT_DIR}"
