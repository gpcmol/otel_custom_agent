#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
IMAGE_NAME="${IMAGE_NAME:-otel-custom-agent-app}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

docker build \
  --tag "${IMAGE_NAME}:${IMAGE_TAG}" \
  "${APP_DIR}"

echo "Image built: ${IMAGE_NAME}:${IMAGE_TAG}"
