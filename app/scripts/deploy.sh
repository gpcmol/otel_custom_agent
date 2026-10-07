#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_NAME="${IMAGE_NAME:-otel-custom-agent-app}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
REGISTRY="${REGISTRY:-localhost:5001}"
LOCAL_IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"
IMAGE="${REGISTRY}/${LOCAL_IMAGE}"
NAMESPACE="default"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-30s}"

if ! docker image inspect "${LOCAL_IMAGE}" >/dev/null 2>&1; then
  echo "Local image is missing: ${LOCAL_IMAGE}" >&2
  echo "Build the image first with ./build.sh" >&2
  exit 1
fi

docker tag "${LOCAL_IMAGE}" "${IMAGE}"
docker push "${IMAGE}"

if kubectl get deployment/garage --namespace "${NAMESPACE}" >/dev/null 2>&1; then
  if ! kubectl rollout status deployment/garage \
    --namespace "${NAMESPACE}" \
    --timeout="${ROLLOUT_TIMEOUT}"; then
    echo "Existing deployment is unhealthy; it will be removed." >&2
    kubectl delete deployment/garage --namespace "${NAMESPACE}" --wait=true
  fi
fi

kubectl apply --namespace "${NAMESPACE}" -f "${SCRIPT_DIR}/kubernetes.yaml"
kubectl set image deployment/garage "garage=${IMAGE}" --namespace "${NAMESPACE}"
kubectl rollout restart deployment/garage --namespace "${NAMESPACE}"

if ! kubectl rollout status deployment/garage \
  --namespace "${NAMESPACE}" \
  --timeout="${ROLLOUT_TIMEOUT}"; then
  echo "New deployment is unhealthy; it will be removed." >&2
  kubectl delete deployment/garage --namespace "${NAMESPACE}" --wait=true
  exit 1
fi

echo "Application deployed to namespace ${NAMESPACE}: ${IMAGE}"
echo "Agent configuration mounted from ConfigMap agent-config"
