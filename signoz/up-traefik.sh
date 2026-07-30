#!/bin/bash
set -o errexit

echo "Up Traefik"

kubectx kind-kind

# install and update helm charts
helm repo add traefik https://traefik.github.io/charts
helm repo update

# setup traefik
(
cd traefik

kubectl create namespace traefik

kubens traefik

openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
-keyout tls.key -out tls.crt \
-subj "/CN=example.com/O=example.com"

kubectl create secret tls example.com \
--cert=tls.crt \
--key=tls.key \
-n traefik

helm install traefik traefik/traefik -f values.yaml --wait

kubectl apply -f ingress-routes.yaml --namespace=signoz
)

echo "Finished Traefik"
