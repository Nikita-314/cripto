#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p logs

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
JAVA_BIN="${JAVA_HOME}/bin/java"
if [[ ! -x "$JAVA_BIN" ]]; then
    JAVA_BIN="java"
fi

JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | awk -F[\\\".] '/version/ {print $2; exit}')"
if [[ -n "$JAVA_MAJOR" && "$JAVA_MAJOR" -gt 22 ]]; then
    echo "Java $JAVA_MAJOR is too new for the current Gradle/Kotlin toolchain. Use Java 21 or 22." >&2
    exit 1
fi

export JAVA_HOME
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dfile.encoding=UTF-8"
exec "$ROOT/kotlin/gradlew" -p "$ROOT/kotlin" run --no-daemon
