#!/usr/bin/env bash
# Builds the browser and starts Java with persistent local PostgreSQL 18.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -d frontend/node_modules ]]; then (cd frontend && npm ci --no-audit --no-fund); fi
(cd frontend && npm run build)
mvn -f backend/pom.xml -q test-compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
mkdir -p backend/target/classes/static
cp -R frontend/dist/conta/browser/. backend/target/classes/static/
classpath="backend/target/test-classes:backend/target/classes:$(cat backend/target/classpath.txt)"
exec java -Djava.awt.headless=true -cp "$classpath" pt.conta.LocalDevelopment
