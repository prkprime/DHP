#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage: scripts/install-mat-plugin.sh

Builds the dhp-mat-plugin OSGi bundle, copies it to MAT's plugins directory,
registers it in Equinox simpleconfigurator bundles.info, and purges the OSGi cache.

Environment:
  MAT_HOME    MAT installation directory (default: tools/mat/mat)
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

MAT_DIR="${MAT_HOME:-${ROOT_DIR}/tools/mat/mat}"

if [[ ! -d "${MAT_DIR}" ]]; then
  echo "MAT directory not found at ${MAT_DIR}. Running scripts/setup-mat.sh..."
  "${SCRIPT_DIR}/setup-mat.sh" "${MAT_DIR}"
fi

SOURCE_JAR="${ROOT_DIR}/dhp-mat-plugin/target/dhp-mat-plugin-1.0.0-SNAPSHOT.jar"

if [[ ! -f "${SOURCE_JAR}" ]]; then
  echo "Plugin JAR not found. Building dhp-mat-plugin..."
  (cd "${ROOT_DIR}" && mvn package -pl dhp-mat-plugin -am -DskipTests)
fi

TARGET_JAR_NAME="org.eclipse.mat.dhp_1.0.0.SNAPSHOT.jar"
TARGET_JAR="${MAT_DIR}/plugins/${TARGET_JAR_NAME}"
BUNDLES_INFO="${MAT_DIR}/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"

echo "Deploying DHP plugin to ${TARGET_JAR}..."
cp "${SOURCE_JAR}" "${TARGET_JAR}"

echo "Updating Equinox bundles.info configuration..."
TMP_INFO="$(mktemp)"
if [[ -f "${BUNDLES_INFO}" ]]; then
  grep -v "^org\.eclipse\.mat\.dhp," "${BUNDLES_INFO}" > "${TMP_INFO}" || true
fi
printf 'org.eclipse.mat.dhp,1.0.0.SNAPSHOT,plugins/%s,4,true\n' "${TARGET_JAR_NAME}" >> "${TMP_INFO}"
mv "${TMP_INFO}" "${BUNDLES_INFO}"

echo "Purging OSGi bundle cache..."
rm -rf "${MAT_DIR}/configuration/org.eclipse.osgi"

echo "=== Dynamic Heap Parser plugin successfully installed into Eclipse MAT! ==="
