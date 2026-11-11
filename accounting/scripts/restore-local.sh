#!/usr/bin/env bash
# Restores a verified cold snapshot into a new directory on the same OS/architecture and PG major.
set -euo pipefail
source_directory="${1:?Indica a pasta da cópia local}"
destination="${2:?Indica um diretório de dados novo}"
if [[ -e "$destination" ]]; then echo "A recuperação exige um diretório de dados novo." >&2; exit 1; fi
source_directory="$(cd "$source_directory" && pwd)"
test "$(cat "$source_directory/PG_VERSION")" = 18
(cd "$source_directory"; if command -v sha256sum >/dev/null; then sha256sum --check SHA256SUMS; else shasum -a 256 --check SHA256SUMS; fi)
# Reject paths outside the two expected archive roots before creating the destination.
while IFS= read -r entry; do
  case "$entry" in /*|../*|*/../*|*/..) echo "Caminho inválido na cópia." >&2; exit 1;; esac
  case "$entry" in postgres|postgres/*|documents|documents/*) ;; *) echo "Conteúdo inesperado na cópia." >&2; exit 1;; esac
done < <(tar -tzf "$source_directory/data.tar.gz")
umask 077
mkdir -p "$destination"
tar -C "$destination" -xzf "$source_directory/data.tar.gz"
test ! -e "$destination/postgres/postmaster.pid"
echo "Recuperação local concluída. Arranca com ACCOUNTING_DATA definido para $destination"
