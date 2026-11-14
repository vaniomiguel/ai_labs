#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../backend"
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/category-evaluation-classpath.txt
exec java -cp "target/test-classes:target/classes:$(cat target/category-evaluation-classpath.txt)" \
  pt.conta.CategoryModelEvaluation "${1:-target/category-model-evaluation.json}"
