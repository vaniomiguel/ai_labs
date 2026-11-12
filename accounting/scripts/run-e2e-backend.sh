#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../backend"
# Always create a fresh database; never point browser tests at a development installation.
export ACCOUNTING_DATA
ACCOUNTING_DATA=$(mktemp -d "${TMPDIR:-/tmp}/conta-e2e.XXXXXX")
export ADMIN_USERNAME=gestor ADMIN_PASSWORD=conta-e2e-dev AI_ENABLED=false JOBS_ENABLED=false
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/e2e-classpath.txt
exec java -cp "target/test-classes:target/classes:$(cat target/e2e-classpath.txt)" \
  pt.conta.LocalDevelopment --server.port=18081 --server.address=127.0.0.1
