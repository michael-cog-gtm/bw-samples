#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${CREDIT_JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p .run
for name in standins creditapp; do
  if [[ -f ".run/$name.pid" ]] && kill -0 "$(<".run/$name.pid")" 2>/dev/null; then
    echo "$name is already running; use ./stop-local.sh first" >&2
    exit 1
  fi
done
mvn -B -DskipTests package
java -jar target/creditapp.jar --spring.profiles.active=standins >.run/standins.log 2>&1 &
echo $! >.run/standins.pid
java -jar target/creditapp.jar >.run/creditapp.log 2>&1 &
echo $! >.run/creditapp.pid
for attempt in {1..90}; do
  if curl -fsS http://localhost:13081/admin/bureaus >/dev/null 2>&1 &&
     curl -fsS http://localhost:8080/api/last-trace >/dev/null 2>&1; then
    echo "Credit app :8080; Equifax :13080; Experian :7080; admin :13081. Logs: .run/"
    exit 0
  fi
  sleep 1
done
echo "Startup failed; inspect .run/*.log" >&2
./stop-local.sh
exit 1
