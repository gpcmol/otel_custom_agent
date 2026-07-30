#!/bin/bash
set -o errexit

echo "Up Signoz"

kubectx kind-kind

# install and update helm charts
helm repo add signoz https://charts.signoz.io
helm repo update

# setup signoz
helm install signoz signoz/signoz \
 --namespace signoz --create-namespace \
 --wait \
 --timeout 1h \
 -f values.yaml

echo 'kubectl port-forward --address=0.0.0.0 "svc/signoz" "8080:8080" -n signoz'

#kubectl port-forward -n <namespace> svc/signoz 8080:8080
echo "Finished Signoz"
