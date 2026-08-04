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
export OTEL_CUSTOM_AGENT_CONFIG="PGNvbmZpZ3VyYXRpb24+CiAgICA8c3RhdGljPgogICAgICAgIDxhdHRyaWJ1dGUga2V5PSJkb21haW4iIHZhbHVlPSJjYXJzIi8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InRlYW0iIHZhbHVlPSJ3aW5uaW5nIi8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9ImVudmlyb25tZW50IiB2YWx1ZT0icHJvZHVjdGlvbiIvPgogICAgICAgIDxhdHRyaWJ1dGUga2V5PSJyZWdpb24iIHZhbHVlPSJldS13ZXN0Ii8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InNlcnZpY2UiIHZhbHVlPSJnYXJhZ2UiLz4KICAgIDwvc3RhdGljPgogICAgPGR5bmFtaWM+CiAgICAgICAgPGVucmljaCBjbGFzcz0iY29tLmV4YW1wbGUuR2FyYWdlIiBtZXRob2Q9InBhcmsiCiAgICAgICAgICAgICAgICBleHByPSIkYXJnMC5icmFuZCA9PSAnQk1XJyAmYW1wOyZhbXA7IGlsaWtlKCRhcmcwLm1vZGVsLCAneCUnKSI+CiAgICAgICAgICAgIDxhdHRyaWJ1dGUga2V5PSJicmFuZCIgcGF0aD0iJGFyZzAuYnJhbmQiLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9Im1vZGVsIiBwYXRoPSIkYXJnMC5tb2RlbCIvPgogICAgICAgICAgICA8YXR0cmlidXRlIGtleT0ieWVhciIgcGF0aD0iJGFyZzAueWVhciIvPgogICAgICAgICAgICA8YXR0cmlidXRlIGtleT0iY29sb3IiIHBhdGg9IiRhcmcwLmNvbG9yIi8+CiAgICAgICAgICAgIDxhdHRyaWJ1dGUga2V5PSJsaWNlbnNlUGxhdGUiIHBhdGg9IiRhcmcwLmxpY2Vuc2VQbGF0ZSIvPgogICAgICAgICAgICA8YXR0cmlidXRlIGtleT0idmluIiBwYXRoPSIkYXJnMC52aW4iLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9Im1pbGVhZ2UiIHBhdGg9IiRhcmcwLm1pbGVhZ2UiLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9ImZ1ZWxUeXBlIiBwYXRoPSIkYXJnMC5mdWVsVHlwZSIvPgogICAgICAgICAgICA8YXR0cmlidXRlIGtleT0idHJhbnNtaXNzaW9uIiBwYXRoPSIkYXJnMC50cmFuc21pc3Npb24iLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InBhc3NlbmdlcjEiIHBhdGg9IiRhcmcwLnBhc3NlbmdlcnNbMV0ubmFtZSIvPgogICAgICAgIDwvZW5yaWNoPgogICAgPC9keW5hbWljPgo8L2NvbmZpZ3VyYXRpb24+Cg=="

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
