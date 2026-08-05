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

# Base64 config: 5 static (domain, team, environment, region, service) + 10 dynamic rules on the
# com.example.Garage.park exit point (Car passed as $arg0): brand, model, year, color, licensePlate,
# vin, mileage, fuelType, transmission, passengers[1].name. One enrich event per park call.
# The expr gate only enriches BMWs with an X-series model (e.g. "X5", "X3").
export OTEL_CUSTOM_AGENT_CONFIG="PGNvbmZpZ3VyYXRpb24+CiAgICA8c3RhdGljPgogICAgICAgIDxhdHRyaWJ1dGUga2V5PSJkb21haW4iIHZhbHVlPSJjYXJzIi8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InRlYW0iIHZhbHVlPSJ3aW5uaW5nIi8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9ImVudmlyb25tZW50IiB2YWx1ZT0icHJvZHVjdGlvbiIvPgogICAgICAgIDxhdHRyaWJ1dGUga2V5PSJyZWdpb24iIHZhbHVlPSJldS13ZXN0Ii8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InNlcnZpY2UiIHZhbHVlPSJnYXJhZ2UiLz4KICAgIDwvc3RhdGljPgogICAgPGR5bmFtaWM+CiAgICAgICAgPGVucmljaCBjbGFzcz0iY29tLmV4YW1wbGUuR2FyYWdlIiBtZXRob2Q9InBhcmsiCiAgICAgICAgICAgICAgICBleHByPSIoJGFyZzAuYnJhbmQgPT0gJ2JtdycgfHwgJGFyZzAuYnJhbmQgPT0gJ2F1ZGknIHx8ICRhcmcwLmJyYW5kID09ICd2dycgfHwgJGFyZzAuYnJhbmQgPT0gJ21lcmNlZGVzJyB8fCAkYXJnMC5icmFuZCA9PSAndG95b3RhJyB8fCAkYXJnMC5icmFuZCA9PSAnaG9uZGEnIHx8ICRhcmcwLmJyYW5kID09ICdmb3JkJyB8fCAkYXJnMC5icmFuZCA9PSAncmVuYXVsdCcpICZhbXA7JmFtcDsgJGFyZzAubWlsZWFnZSA+IDAiPgogICAgICAgICAgICA8YXR0cmlidXRlIGtleT0iYnJhbmQiIHBhdGg9IiRhcmcwLmJyYW5kIi8+CiAgICAgICAgICAgIDxhdHRyaWJ1dGUga2V5PSJtb2RlbCIgcGF0aD0iJGFyZzAubW9kZWwiLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InllYXIiIHBhdGg9IiRhcmcwLnllYXIiLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9ImNvbG9yIiBwYXRoPSIkYXJnMC5jb2xvciIvPgogICAgICAgICAgICA8YXR0cmlidXRlIGtleT0ibGljZW5zZVBsYXRlIiBwYXRoPSIkYXJnMC5saWNlbnNlUGxhdGUiLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InZpbiIgcGF0aD0iJGFyZzAudmluIi8+CiAgICAgICAgICAgIDxhdHRyaWJ1dGUga2V5PSJtaWxlYWdlIiBwYXRoPSIkYXJnMC5taWxlYWdlIi8+CiAgICAgICAgICAgIDxhdHRyaWJ1dGUga2V5PSJmdWVsVHlwZSIgcGF0aD0iJGFyZzAuZnVlbFR5cGUiLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InRyYW5zbWlzc2lvbiIgcGF0aD0iJGFyZzAudHJhbnNtaXNzaW9uIi8+CiAgICAgICAgICAgIDxhdHRyaWJ1dGUga2V5PSJwYXNzZW5nZXJzIiBwYXRoPSIkYXJnMC5wYXNzZW5nZXJzWzFdLm5hbWUiLz4KICAgICAgICA8L2VucmljaD4KICAgIDwvZHluYW1pYz4KPC9jb25maWd1cmF0aW9uPg=="

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
