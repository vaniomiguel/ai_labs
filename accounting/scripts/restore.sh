#!/usr/bin/env bash
# Restores into a deliberately empty installation; refuses to overwrite business data.
set -euo pipefail
cd "$(dirname "$0")/.."
source_directory="${1:?Indica a pasta da cópia}"
test -s "$source_directory/database.backup"
test -s "$source_directory/documents.tar"
tables="$(docker compose exec -T postgres psql -U accounting -d accounting -Atc "select count(*) from information_schema.tables where table_schema='public'")"
if [[ "$tables" != 0 ]]; then echo "A recuperação exige uma base de dados vazia." >&2; exit 1; fi
existing_documents="$(docker compose run --rm --no-deps -T --entrypoint sh app -c 'find /data/documents -mindepth 1 -maxdepth 1 | head -n 1')"
if [[ -n "$existing_documents" ]]; then echo "A recuperação exige um volume de documentos vazio." >&2; exit 1; fi
docker compose stop app
docker compose exec -T postgres pg_restore -U accounting -d accounting --no-owner < "$source_directory/database.backup"
docker compose run --rm --no-deps -T --user 0 --entrypoint tar app -C /data -xf - < "$source_directory/documents.tar"
docker compose run --rm --no-deps -T --user 0 --entrypoint chown app -R 10001:10001 /data/documents
docker compose start app
echo "Recuperação concluída. Confirma os documentos e os saldos na aplicação."
