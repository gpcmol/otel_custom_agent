#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${IMAGE:-otel-custom-agent:latest}"

docker build --pull -t "$IMAGE" "$ROOT_DIR"
