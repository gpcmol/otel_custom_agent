#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$ROOT_DIR/app"
APP_JAR="$APP_DIR/target/garage-1.0-SNAPSHOT.jar"
AGENT_JAR="$ROOT_DIR/build/otel/opentelemetry-javaagent.jar"

if [[ ! -f "$APP_JAR" ]]; then
  echo "Building application..."
  (cd "$APP_DIR" && mvn -q clean package)
fi

if [[ ! -f "$AGENT_JAR" ]]; then
  echo "Building extended OpenTelemetry agent..."
  "$ROOT_DIR/gradlew" -q extendedAgent
fi

#export OTEL_CUSTOM_AGENT_CONFIG="PGNvbmZpZ3VyYXRpb24+CiAgICA8c3RhdGljPgogICAgICAgIDxhdHRyaWJ1dGUga2V5PSJkb21haW4iIHZhbHVlPSJjYXJzIi8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InRlYW0iIHZhbHVlPSJ3aW5uaW5nIi8+CiAgICA8L3N0YXRpYz4KICAgIDxkeW5hbWljPgogICAgICAgIDxhdHRyaWJ1dGUga2V5PSJicmFuZCIgcGF0aD0iY29tLmV4YW1wbGUuQ2FyLmJyYW5kIi8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InBhc3NlbmdlcnMiIHBhdGg9ImNvbS5leGFtcGxlLkNhci5wYXNzZW5nZXJzLm5hbWUiLz4KICAgIDwvZHluYW1pYz4KPC9jb25maWd1cmF0aW9uPgo="
export OTEL_CUSTOM_AGENT_CONFIG="PGNvbmZpZ3VyYXRpb24+CiAgICA8c3RhdGljPgogICAgICAgIDxhdHRyaWJ1dGUga2V5PSJkb21haW4iIHZhbHVlPSJjYXJzIi8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InRlYW0iIHZhbHVlPSJ3aW5uaW5nIi8+CiAgICA8L3N0YXRpYz4KICAgIDxkeW5hbWljPgogICAgICAgIDxhdHRyaWJ1dGUga2V5PSJicmFuZCIgcGF0aD0iY29tLmV4YW1wbGUuQ2FyLmJyYW5kIi8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InBhc3NlbmdlcnMiIHBhdGg9ImNvbS5leGFtcGxlLkNhci5wYXNzZW5nZXJzWzFdLm5hbWUiLz4KICAgIDwvZHluYW1pYz4KPC9jb25maWd1cmF0aW9uPg=="

#IP=$(kubectl get svc traefik -o jsonpath='{.status.loadBalancer.ingress[0].ip}' -n traefik)
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
