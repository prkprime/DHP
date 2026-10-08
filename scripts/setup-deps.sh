#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LIB_DIR="${ROOT_DIR}/lib"

echo "=== Installing Local Eclipse MAT Dependencies into Maven Local Repository ==="

mvn install:install-file \
  -Dfile="${LIB_DIR}/org.eclipse.mat.api_1.17.0.202606011933.jar" \
  -DgroupId=org.eclipse.mat \
  -DartifactId=org.eclipse.mat.api \
  -Dversion=1.17.0 \
  -Dpackaging=jar

mvn install:install-file \
  -Dfile="${LIB_DIR}/org.eclipse.mat.parser_1.17.0.202606011933.jar" \
  -DgroupId=org.eclipse.mat \
  -DartifactId=org.eclipse.mat.parser \
  -Dversion=1.17.0 \
  -Dpackaging=jar

mvn install:install-file \
  -Dfile="${LIB_DIR}/org.eclipse.mat.hprof_1.17.0.202606011933.jar" \
  -DgroupId=org.eclipse.mat \
  -DartifactId=org.eclipse.mat.hprof \
  -Dversion=1.17.0 \
  -Dpackaging=jar

mvn install:install-file \
  -Dfile="${LIB_DIR}/org.eclipse.mat.report_1.17.0.202606011933.jar" \
  -DgroupId=org.eclipse.mat \
  -DartifactId=org.eclipse.mat.report \
  -Dversion=1.17.0 \
  -Dpackaging=jar

echo "=== All Eclipse MAT JARs installed successfully! ==="
