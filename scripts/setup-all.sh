#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "================================================================================"
echo "Dynamic Heap Parser (DHP) - Complete Environment Setup"
echo "================================================================================"

echo ""
echo "[Step 1/4] Installing Eclipse MAT local dependencies into Maven..."
"${SCRIPT_DIR}/setup-deps.sh"

echo ""
echo "[Step 2/4] Building full DHP reactor modules..."
(cd "${ROOT_DIR}" && mvn clean install -DskipTests)

echo ""
echo "[Step 3/4] Setting up Eclipse MAT standalone tooling..."
"${SCRIPT_DIR}/setup-mat.sh"

echo ""
echo "[Step 4/4] Installing DHP plugin into Eclipse MAT..."
"${SCRIPT_DIR}/install-mat-plugin.sh"

echo ""
echo "================================================================================"
echo "DHP setup complete! You are ready to parse heap dumps and run Eclipse MAT."
echo "================================================================================"
