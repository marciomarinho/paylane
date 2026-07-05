#!/usr/bin/env bash
# Run the mixed workload against both twins: three concurrency tiers (baseline) plus a
# slow-downstream variant (200ms simulated card-scheme call). Requires the stack up and k6 + python3.
set -euo pipefail
cd "$(dirname "$0")/.."          # repo root
mkdir -p bench/out
rm -f bench/out/*.json
RUN_ID="run$(date +%s)"          # date is fine in a shell script
TIERS="${TIERS:-50 150 300}"

wait_health() {
  printf 'waiting for %s ' "$1"
  for _ in $(seq 1 60); do
    curl -fsS "$2/actuator/health" >/dev/null 2>&1 && { echo ok; return; }
    printf '.'; sleep 2
  done
  echo TIMEOUT; exit 1
}
wait_health mvc      http://localhost:8081
wait_health reactive http://localhost:8091

for pair in "mvc:8081" "reactive:8091"; do
  name=${pair%%:*}; port=${pair##*:}; base="http://localhost:${port}"
  for vus in $TIERS; do
    echo ">> $name baseline vus=$vus"
    k6 run -q \
      -e BASE_URL="$base" -e VUS="$vus" -e DURATION=15s \
      -e RUN_ID="${RUN_ID}-${name}-${vus}" \
      -e OUT="bench/out/${name}-${vus}.json" \
      bench/mixed.js
  done
  echo ">> $name slow-downstream vus=200 delay=200ms"
  k6 run -q \
    -e BASE_URL="$base" -e VUS=200 -e DURATION=20s -e SCHEME_DELAY=200 \
    -e RUN_ID="${RUN_ID}-${name}-slow" \
    -e OUT="bench/out/${name}-slow.json" \
    bench/mixed.js
done

echo "=== container memory under idle after load (docker stats) ==="
docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' \
  | grep -E 'payment-api(-reactive)?-1' | tee bench/out/mem.txt

python3 bench/summarize.py
echo "wrote bench/results.md"
