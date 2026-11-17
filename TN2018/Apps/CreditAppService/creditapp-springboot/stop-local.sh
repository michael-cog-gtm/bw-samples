#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
for name in creditapp standins; do
  file=".run/$name.pid"
  if [[ -f "$file" ]]; then
    pid="$(<"$file")"
    if [[ "$pid" =~ ^[0-9]+$ ]] && [[ -r "/proc/$pid/cmdline" ]] &&
       tr '\0' ' ' <"/proc/$pid/cmdline" | grep -q 'java -jar target/creditapp.jar'; then
      kill "$pid"
      for attempt in {1..30}; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
    fi
    rm -f "$file"
  fi
done
