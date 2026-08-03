#!/bin/bash
set -o errexit

echo "Up Kind"

if ! command -v kind &> /dev/null; then
  echo "install kind first from https://kind.sigs.k8s.io/"
  exit 1
fi

if ! command -v helm &> /dev/null; then
  echo "install helm first from https://helm.sh/docs/intro/install/"
  exit 1
fi

# create kind cluster
./kind-up-with-registry.sh

# metric server
kubens kube-system
kubectl apply -f ./metrics-server/components.yaml

echo "Finished Kind"
