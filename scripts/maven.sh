#!/usr/bin/env bash
set -euo pipefail
FORGE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" && "$(uname)" == "Darwin" ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
export MAVEN_USER_HOME="$FORGE_ROOT/.tools/maven-cache"
if [[ -x "$FORGE_ROOT/.tools/apache-maven-3.9.11/bin/mvn" ]]; then
  MAVEN="$FORGE_ROOT/.tools/apache-maven-3.9.11/bin/mvn"
else
  MAVEN="$FORGE_ROOT/backend/mvnw"
fi
exec "$MAVEN" -B -ntp -f "$FORGE_ROOT/backend/pom.xml" "-Dmaven.repo.local=$FORGE_ROOT/.m2" "$@"
