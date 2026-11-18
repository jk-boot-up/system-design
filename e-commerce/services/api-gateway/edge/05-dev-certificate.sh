#!/bin/sh
# Creates a self-signed certificate for local use when none is mounted at /etc/nginx/certs.
# In Kubernetes the certificate comes from cert-manager as a secret mounted there instead.
set -e
if [ ! -s /etc/nginx/certs/tls.crt ]; then
  mkdir -p /etc/nginx/certs
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=${EDGE_HOSTNAME:-localhost}" \
    -addext "subjectAltName=DNS:${EDGE_HOSTNAME:-localhost},DNS:localhost,IP:127.0.0.1" \
    -keyout /etc/nginx/certs/tls.key -out /etc/nginx/certs/tls.crt 2>/dev/null
  echo "edge: created a self-signed development certificate for ${EDGE_HOSTNAME:-localhost}"
fi
