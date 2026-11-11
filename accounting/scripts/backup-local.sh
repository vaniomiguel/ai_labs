#!/usr/bin/env bash
# Cold snapshot of local development data: PostgreSQL must be cleanly stopped first.
set -euo pipefail
destination="${1:?Indica uma pasta de cópia nova}"
source_directory="${2:-${ACCOUNTING_DATA:-$(dirname "$0")/../data}}"
if [[ -e "$destination" ]]; then echo "O destino já existe." >&2; exit 1; fi
test -d "$source_directory/documents"
test -s "$source_directory/postgres/PG_VERSION"
if [[ -e "$source_directory/postgres/postmaster.pid" ]]; then
  echo "Encerra a aplicação local e aguarda a paragem do PostgreSQL antes de copiar." >&2; exit 1
fi
umask 077
mkdir -p "$destination"
destination="$(cd "$destination" && pwd)"
tar -C "$source_directory" -czf "$destination/data.tar.gz" postgres documents
cp "$source_directory/postgres/PG_VERSION" "$destination/PG_VERSION"
cd "$destination"
if command -v sha256sum >/dev/null; then sha256sum data.tar.gz > SHA256SUMS; else shasum -a 256 data.tar.gz > SHA256SUMS; fi
date -u +%FT%TZ > created-at.txt
echo "Cópia local criada em $destination"
