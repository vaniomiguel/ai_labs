#!/usr/bin/env bash
# Runs Java on the host; PostgreSQL, OCR and the one-off Angular build use Docker.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then
  echo "Cria accounting/.env a partir de .env.example e define ADMIN_PASSWORD e DATABASE_PASSWORD."
  exit 1
fi
python3 - <<'PY'
import socket
with socket.socket() as connection:
    try:
        connection.bind(('127.0.0.1', 8081))
    except OSError:
        raise SystemExit('Já existe uma aplicação em localhost:8081. Pára-a antes de iniciar o Java local.')
PY
docker compose -f compose.hybrid.yml config --quiet
# Compose handles dotenv quoting/interpolation. NUL-delimited values preserve spaces and symbols.
while IFS= read -r -d '' key && IFS= read -r -d '' value; do
  export "$key=$value"
done < <(docker compose -f compose.hybrid.yml config --format json | python3 -c '
import json,sys
for key,value in json.load(sys.stdin)["x-java-environment"].items():
    sys.stdout.buffer.write((key+"\0"+str(value)+"\0").encode())
')
: "${ADMIN_PASSWORD:?Não foi possível ler as credenciais da configuração}"
docker compose -f compose.hybrid.yml up --build -d --wait
docker build --target frontend -t conta-hybrid-ui .
ui_container="$(docker create conta-hybrid-ui)"
trap 'docker rm "$ui_container" >/dev/null 2>&1 || true' EXIT
mkdir -p backend/target/classes/static
docker cp "$ui_container:/ui/dist/conta/browser/." backend/target/classes/static/
docker rm "$ui_container" >/dev/null
trap - EXIT
mvn -f backend/pom.xml -q -DskipTests package
echo "A iniciar Java local. Abre http://localhost:8081 quando o arranque terminar."
exec java -Djava.awt.headless=true -jar backend/target/accounting-0.1.0.jar
