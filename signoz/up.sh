#!/bin/bash
set -e

./up-kind.sh
./up-signoz.sh
./up-traefik.sh

echo "starting cloud-provider-kind..."
sudo cloud-provider-kind
