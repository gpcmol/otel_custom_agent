#!/bin/sh
# modified from original https://kind.sigs.k8s.io/docs/user/local-registry/
set -o errexit

#====================
# registry:3
# 1. Create registry container unless it already exists
#reg_name='kind-registry'
#reg_port='5001'
#if [ "$(docker inspect -f '{{.State.Running}}' "${reg_name}" 2>/dev/null || true)" != 'true' ]; then
#  docker run \
#    -d --restart=always -p "127.0.0.1:${reg_port}:5000" --network bridge --name "${reg_name}" \
#    registry:3
#fi
#====================
# ZOT
reg_name='kind-registry'
reg_port='5001'

case "$(uname -m)" in
  arm64|aarch64) zot_arch='arm64' ;;
  x86_64|amd64) zot_arch='amd64' ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

if [ "$(docker inspect -f '{{.State.Running}}' "${reg_name}" 2>/dev/null || true)" = 'false' ]; then
  docker start "${reg_name}"
elif [ "$(docker inspect -f '{{.State.Running}}' "${reg_name}" 2>/dev/null || true)" = '' ]; then

  echo "Setup Zot local registry"
  mkdir -p ./zot-data

  docker run \
    -d \
    --restart=always \
    -p "127.0.0.1:${reg_port}:5000" \
    --network bridge \
    --name "${reg_name}" \
    -v "$(pwd)/zot-config.json:/etc/zot/config.json:ro" \
    -v "$(pwd)/zot-data:/var/lib/registry" \
    "ghcr.io/project-zot/zot-linux-${zot_arch}:v2.1.18"
fi

# 2. Create kind cluster with containerd registry config dir enabled
# TODO: kind will eventually enable this by default and this patch will
# be unnecessary.
#
# See:
# https://github.com/kubernetes-sigs/kind/issues/2875
# https://github.com/containerd/containerd/blob/main/docs/cri/config.md#registry-configuration
# See: https://github.com/containerd/containerd/blob/main/docs/hosts.md
kind create cluster --config ./kind/kind-config

# 3. Connect the registry to the cluster network
# This allows kind to bootstrap the network but ensures they're on the same network
if [ "$(docker inspect -f='{{json .NetworkSettings.Networks.kind}}' "${reg_name}")" = 'null' ]; then
  docker network connect "kind" "${reg_name}"
fi

# 4. Add the registry config to the nodes
#
# This is necessary because localhost resolves to loopback addresses that are
# network-namespace local.
# In other words: localhost in the container is not localhost on the host.
#
# We want a consistent name that works from both ends, so we tell containerd to
# alias localhost:${reg_port} to the registry container when pulling images
for registry in docker.io ghcr.io quay.io registry.k8s.io; do
  REGISTRY_DIR="/etc/containerd/certs.d/${registry}"
  for node in $(kind get nodes); do
    docker exec "${node}" mkdir -p "${REGISTRY_DIR}"
    cat <<EOF | docker exec -i "${node}" cp /dev/stdin "${REGISTRY_DIR}/hosts.toml"
[host."http://${reg_name}:5000"]
capabilities = ["pull", "resolve"]
EOF
  done
done

# 5. Keep localhost:5001 usable for local pushes as well.
REGISTRY_DIR="/etc/containerd/certs.d/localhost:${reg_port}"
for node in $(kind get nodes); do
  docker exec "${node}" mkdir -p "${REGISTRY_DIR}"
  cat <<EOF | docker exec -i "${node}" cp /dev/stdin "${REGISTRY_DIR}/hosts.toml"
[host."http://${reg_name}:5000"]
EOF
done

# 6. Document the local registry
# https://github.com/kubernetes/enhancements/tree/master/keps/sig-cluster-lifecycle/generic/1755-communicating-a-local-registry
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "localhost:${reg_port}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF
