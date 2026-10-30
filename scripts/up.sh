#!/usr/bin/env bash
# Starts the compose stack with secrets generated on first use and kept outside the repo (mode 600).
# Reruns reuse them, so the database in an existing volume still accepts its password.
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=${ROUTEMESH_ENV_FILE:-$HOME/.config/routemesh/compose.env}
if [[ ! -f $ENV_FILE ]]; then
  mkdir -p "$(dirname "$ENV_FILE")"
  umask 077
  {
    echo "ROUTEMESH_DB_PASSWORD=$(openssl rand -hex 24)"
    echo "ROUTEMESH_REDIS_PASSWORD=$(openssl rand -hex 24)"
    echo "ROUTEMESH_OTP_KEY=$(openssl rand -base64 32)"
  } > "$ENV_FILE"
  echo "wrote new secrets to $ENV_FILE"
fi
exec docker compose --env-file "$ENV_FILE" up -d --wait --wait-timeout "${WAIT:-300}" "$@"
