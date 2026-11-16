#!/bin/sh
set -eu
umask 077
mkdir -p .local/tls
if [ -e .local/tls/stack.env ]; then
  if ! grep -q '^SECRETCHAT_MFA_ENCRYPTION_KEY=' .local/tls/stack.env 2>/dev/null; then
    # Upgrade existing local deployments without changing their database or TLS secrets.
    printf 'SECRETCHAT_MFA_ENCRYPTION_KEY=%s\n' "$(openssl rand -hex 32)" >> .local/tls/stack.env
  fi
  echo 'Local TLS configuration already exists.'
  exit 0
fi
SECRETCHAT_TLS_PASSWORD=$(openssl rand -hex 24)
SECRETCHAT_DB_PASSWORD=$(openssl rand -hex 24)
SECRETCHAT_METRICS_TOKEN=$(openssl rand -hex 24)
export SECRETCHAT_TLS_PASSWORD
keytool -genkeypair -alias server -keyalg RSA -keysize 3072 -validity 30 \
  -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1' \
  -storetype PKCS12 -keystore .local/tls/server.p12 -storepass:env SECRETCHAT_TLS_PASSWORD >/dev/null 2>&1
keytool -exportcert -rfc -alias server -keystore .local/tls/server.p12 \
  -storepass:env SECRETCHAT_TLS_PASSWORD -file .local/tls/localhost.crt >/dev/null 2>&1
keytool -importcert -noprompt -alias local-server -file .local/tls/localhost.crt \
  -storetype PKCS12 -keystore .local/tls/truststore.p12 -storepass changeit >/dev/null 2>&1
# The keystore is encrypted; the container's non-root account needs read access to the mount.
chmod 644 .local/tls/server.p12 .local/tls/localhost.crt
{
  printf 'SECRETCHAT_TLS_PASSWORD=%s\n' "$SECRETCHAT_TLS_PASSWORD"
  printf 'SECRETCHAT_DB_PASSWORD=%s\n' "$SECRETCHAT_DB_PASSWORD"
  printf 'SECRETCHAT_MFA_ENCRYPTION_KEY=%s\n' "$(openssl rand -hex 32)"
  printf 'SECRETCHAT_METRICS_TOKEN=%s\n' "$SECRETCHAT_METRICS_TOKEN"
} > .local/tls/stack.env
echo 'Prepared localhost TLS certificate and private stack environment in .local/tls.'
