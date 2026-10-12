#!/usr/bin/env bash
# Builds the parity dump bundle and registers it in the MAT install (default: tools/mat/mat).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAT_HOME="${MAT_HOME:-$HERE/../mat/mat}"
P="$MAT_HOME/plugins"
CP="$(ls "$P"/org.eclipse.mat.{api,report,parser}_*.jar "$P"/org.eclipse.equinox.{app,common}_*.jar "$P"/org.eclipse.osgi_*.jar "$P"/org.eclipse.core.runtime_*.jar | tr '\n' ':')"
OUT="$HERE/build"; rm -rf "$OUT"; mkdir -p "$OUT/classes"
javac -nowarn -d "$OUT/classes" -cp "$CP" "$HERE"/src/org/eclipse/mat/dhp/parity/*.java
cp "$HERE/plugin.xml" "$OUT/classes/"
mkdir -p "$OUT/classes/META-INF" && cp "$HERE/META-INF/MANIFEST.MF" "$OUT/classes/META-INF/"
JAR="$P/org.eclipse.mat.dhp.parity_1.0.0.jar"
( cd "$OUT/classes" && jar cfm "$JAR" META-INF/MANIFEST.MF org plugin.xml )
BI="$MAT_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
grep -q '^org.eclipse.mat.dhp.parity,' "$BI" || echo "org.eclipse.mat.dhp.parity,1.0.0,plugins/org.eclipse.mat.dhp.parity_1.0.0.jar,4,true" >> "$BI"
rm -rf "$MAT_HOME/configuration/org.eclipse.osgi"
echo "installed $JAR"
