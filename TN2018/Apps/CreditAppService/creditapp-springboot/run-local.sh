#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p .run
for pidfile in .run/*.pid; do
  if [[ -f "$pidfile" ]] && kill -0 "$(<"$pidfile")" 2>/dev/null; then
    echo "Already running: $pidfile; run ./stop-local.sh first" >&2; exit 1
  fi
done
for port in 8080 13080 7080; do
  if (echo > /dev/tcp/127.0.0.1/"$port") 2>/dev/null; then echo "Port $port is in use" >&2; exit 1; fi
done
mvn -B package -DskipTests > .run/build.log 2>&1
trap './stop-local.sh' ERR INT TERM
jar=target/creditapp-springboot-1.0.0.jar
for bureau in equifax experian; do
  port=13080; [[ "$bureau" == experian ]] && port=7080
  "$JAVA_HOME/bin/java" -Dloader.main=demo.credit.StandInApplication -cp "$jar" org.springframework.boot.loader.launch.PropertiesLauncher --standin.bureau="$bureau" --server.port="$port" --server.address=127.0.0.1 > ".run/$bureau.log" 2>&1 &
  echo $! > ".run/$bureau.pid"
done
"$JAVA_HOME/bin/java" -jar "$jar" --server.address=127.0.0.1 > .run/service.log 2>&1 &
echo $! > .run/service.pid
for port in 13080 7080 8080; do
  ready=false
  for attempt in {1..90}; do
    if curl -fsS "http://localhost:$port/$([[ "$port" == 8080 ]] || echo standin/records)" >/dev/null 2>&1; then ready=true; break; fi
    sleep 1
  done
  if [[ "$ready" != true ]]; then echo "Startup failed on $port; inspect .run/*.log" >&2; ./stop-local.sh; exit 1; fi
done
echo 'Synthetic local lab ready: http://localhost:8080 (logs/PIDs: .run/)'
