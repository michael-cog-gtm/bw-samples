#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
for file in .run/*.pid; do
  [[ -f "$file" ]] || continue
  pid=$(<"$file")
  if kill -0 "$pid" 2>/dev/null; then
    if [[ $(ps -p "$pid" -o args=) == *creditapp-springboot-1.0.0.jar* ]]; then
      kill "$pid"
      for attempt in {1..50}; do kill -0 "$pid" 2>/dev/null || break; sleep .1; done
    else echo "Refusing to stop reused PID $pid" >&2; continue; fi
  fi
  rm "$file"
done
echo 'Local credit lab stopped.'
