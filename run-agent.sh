#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$ROOT_DIR/app"
APP_JAR="$APP_DIR/target/garage-1.0-SNAPSHOT.jar"
AGENT_JAR="$ROOT_DIR/build/otel/opentelemetry-javaagent.jar"
CONFIG_FILE="$ROOT_DIR/config/agent-config.xml"

if [[ ! -f "$APP_JAR" ]]; then
  echo "Building application..."
  (cd "$APP_DIR" && mvn -q clean package)
fi

if [[ ! -f "$AGENT_JAR" ]]; then
  echo "Building extended OpenTelemetry agent..."
  "$ROOT_DIR/gradlew" -q extendedAgent
fi

export OTEL_CUSTOM_AGENT_CONFIG_FILE="$CONFIG_FILE"

IP="127.0.0.1"
echo "Traafik IP $IP"

exec java \
  -javaagent:"$AGENT_JAR" \
  -Dotel.service.name=car-app \
  -Dotel.exporter.otlp.protocol=http/protobuf \
  -Dotel.exporter.otlp.endpoint="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://${IP}:4317}" \
  -Dotel.javaagent.debug="${OTEL_JAVAAGENT_DEBUG:-true}" \
  -Dotel.metrics.exporter=none \
  -Dotel.logs.exporter=none \
  -jar "$APP_JAR"
