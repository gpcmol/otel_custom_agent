#!/usr/bin/env bash
set -euo pipefail
# build.sh — compile agent + app (no benchmark code; benchmark is now k6-driven)
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[build] ./gradlew extendedAgent..."
(cd "$ROOT_DIR" && ./gradlew -q extendedAgent)

echo "[build] mvn app package..."
(cd "$ROOT_DIR/app" && mvn -q clean package -DskipTests)

echo "[build] done. Run the benchmark:"
echo "  $ROOT_DIR/scripts/bench.sh --mode all"
