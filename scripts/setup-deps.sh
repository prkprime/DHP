#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Resolve MAT version from pom.xml or default
MAT_VERSION="${MAT_VERSION:-}"
if [[ -z "${MAT_VERSION}" && -f "${ROOT_DIR}/pom.xml" ]]; then
  MAT_VERSION=$(grep -oPm1 '(?<=<mat.version>)[^<]+' "${ROOT_DIR}/pom.xml" 2>/dev/null || true)
fi
if [[ -z "${MAT_VERSION}" ]]; then
  MAT_VERSION="1.17.0"
fi

MAT_DIR="${MAT_HOME:-${ROOT_DIR}/tools/mat/mat}"
PLUGINS_DIR="${MAT_DIR}/plugins"
LIB_DIR="${ROOT_DIR}/lib"

echo "=== Dynamic Heap Parser: Resolving Eclipse MAT ${MAT_VERSION} Dependencies ==="

M2_REPO="${M2_REPO:-${HOME}/.m2/repository}"
M2_MAT="${M2_REPO}/org/eclipse/mat"
if [[ -f "${M2_MAT}/org.eclipse.mat.api/${MAT_VERSION}/org.eclipse.mat.api-${MAT_VERSION}.jar" && \
      -f "${M2_MAT}/org.eclipse.mat.parser/${MAT_VERSION}/org.eclipse.mat.parser-${MAT_VERSION}.jar" && \
      -f "${M2_MAT}/org.eclipse.mat.hprof/${MAT_VERSION}/org.eclipse.mat.hprof-${MAT_VERSION}.jar" && \
      -f "${M2_MAT}/org.eclipse.mat.report/${MAT_VERSION}/org.eclipse.mat.report-${MAT_VERSION}.jar" ]]; then
  echo "Eclipse MAT ${MAT_VERSION} dependencies are already installed in local Maven repository (${M2_REPO})."
  exit 0
fi

find_jar() {
  local artifact="$1"
  local found=""
  if [[ -d "${PLUGINS_DIR}" ]]; then
    found=$(find "${PLUGINS_DIR}" -maxdepth 1 -name "org.eclipse.mat.${artifact}_${MAT_VERSION}*.jar" 2>/dev/null | head -n 1 || true)
  fi
  if [[ -z "${found}" && -d "${LIB_DIR}" ]]; then
    found=$(find "${LIB_DIR}" -maxdepth 1 -name "org.eclipse.mat.${artifact}_${MAT_VERSION}*.jar" 2>/dev/null | head -n 1 || true)
  fi
  echo "${found}"
}

API_JAR=$(find_jar "api")
PARSER_JAR=$(find_jar "parser")
HPROF_JAR=$(find_jar "hprof")
REPORT_JAR=$(find_jar "report")

if [[ -z "${API_JAR}" || -z "${PARSER_JAR}" || -z "${HPROF_JAR}" || -z "${REPORT_JAR}" ]]; then
  echo "MAT plugin JARs not found locally. Automatically downloading and setting up Eclipse MAT ${MAT_VERSION}..."
  "${SCRIPT_DIR}/setup-mat.sh" "${MAT_DIR}"
  API_JAR=$(find_jar "api")
  PARSER_JAR=$(find_jar "parser")
  HPROF_JAR=$(find_jar "hprof")
  REPORT_JAR=$(find_jar "report")
fi

if [[ -z "${API_JAR}" || -z "${PARSER_JAR}" || -z "${HPROF_JAR}" || -z "${REPORT_JAR}" ]]; then
  echo "Error: Could not locate Eclipse MAT dependencies for version ${MAT_VERSION}." >&2
  exit 1
fi

echo "Installing org.eclipse.mat.api (${API_JAR})..."
mvn -B install:install-file \
  -Dfile="${API_JAR}" \
  -DgroupId=org.eclipse.mat \
  -DartifactId=org.eclipse.mat.api \
  -Dversion="${MAT_VERSION}" \
  -Dpackaging=jar >/dev/null

echo "Installing org.eclipse.mat.parser (${PARSER_JAR})..."
mvn -B install:install-file \
  -Dfile="${PARSER_JAR}" \
  -DgroupId=org.eclipse.mat \
  -DartifactId=org.eclipse.mat.parser \
  -Dversion="${MAT_VERSION}" \
  -Dpackaging=jar >/dev/null

echo "Installing org.eclipse.mat.hprof (${HPROF_JAR})..."
mvn -B install:install-file \
  -Dfile="${HPROF_JAR}" \
  -DgroupId=org.eclipse.mat \
  -DartifactId=org.eclipse.mat.hprof \
  -Dversion="${MAT_VERSION}" \
  -Dpackaging=jar >/dev/null

echo "Installing org.eclipse.mat.report (${REPORT_JAR})..."
mvn -B install:install-file \
  -Dfile="${REPORT_JAR}" \
  -DgroupId=org.eclipse.mat \
  -DartifactId=org.eclipse.mat.report \
  -Dversion="${MAT_VERSION}" \
  -Dpackaging=jar >/dev/null

echo "=== All Eclipse MAT ${MAT_VERSION} dependencies installed successfully into local Maven repository! ==="
