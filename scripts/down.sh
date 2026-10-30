#!/usr/bin/env bash
# Stops the stack and deletes its volumes. Leaves the env file alone; delete it yourself if you want new secrets.
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=${ROUTEMESH_ENV_FILE:-$HOME/.config/routemesh/compose.env}
exec docker compose --env-file "$ENV_FILE" down -v "$@"
