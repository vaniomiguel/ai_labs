#!/usr/bin/env bash
# Captures database and original documents at one quiescent point in time.
set -euo pipefail
cd "$(dirname "$0")/.."
destination="${1:?Indica uma pasta de destino nova}"
if [[ -e "$destination" ]]; then echo "O destino já existe." >&2; exit 1; fi
umask 077
mkdir -p "$destination"
destination="$(cd "$destination" && pwd)"
docker compose stop app
trap 'docker compose start app >/dev/null' EXIT
docker compose exec -T postgres pg_dump -U accounting -d accounting -Fc > "$destination/database.backup"
docker compose run --rm --no-deps -T --user 0 --entrypoint tar app -C /data -cf - documents > "$destination/documents.tar"
date -u +%FT%TZ > "$destination/created-at.txt"
echo "Cópia criada em $destination"
