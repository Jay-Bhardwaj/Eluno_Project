#!/bin/sh
set -eu

# The mock stays private to this container. Spring Boot accesses it via loopback.
python3 /app/starter-kit/mock-apis/mock_server.py --no-flake > /tmp/eluno-mock.log 2>&1 &
mock_pid=$!
trap 'kill "$mock_pid" 2>/dev/null || true' EXIT INT TERM

exec java -jar /app/eluno-pos.jar --server.port="${PORT:-8080}"
