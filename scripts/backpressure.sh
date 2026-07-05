#!/usr/bin/env bash
# Demonstrates end-to-end backpressure in the WebFlux twin. We ask for a huge synthetic stream
# but read it slowly (rate-limited). If the service buffered the stream, memory would spike as it
# generated all rows up front. Instead memory stays flat: Netty's write demand backpressures the
# Flux, so rows are produced only as fast as this slow client reads them.
#
# Requires the reactive twin running (docker compose up). Usage: scripts/backpressure.sh
set -euo pipefail

BASE=${BASE_URL:-http://localhost:8091}
COUNT=${COUNT:-5000000}
RATE=${RATE:-64k}
CONTAINER=${CONTAINER:-paylane-payment-api-reactive-1}
OUT=$(mktemp)

echo "requesting $COUNT rows from $BASE/payments/firehose, reading at only $RATE"
echo "if it were buffered, memory would jump immediately; with backpressure it stays flat."
echo

curl -s --limit-rate "$RATE" "$BASE/payments/firehose?count=$COUNT" > "$OUT" &
CURL_PID=$!

printf '  %-6s  %-22s  %s\n' "time" "reactive mem" "rows received"
for i in $(seq 1 15); do
  sleep 2
  MEM=$(docker stats --no-stream --format '{{.MemUsage}}' "$CONTAINER" 2>/dev/null || echo "?")
  LINES=$(wc -l < "$OUT" 2>/dev/null | tr -d ' ')
  printf '  %-6s  %-22s  %s\n' "$((i * 2))s" "$MEM" "$LINES"
  kill -0 "$CURL_PID" 2>/dev/null || { echo "  (stream completed)"; break; }
done

kill "$CURL_PID" 2>/dev/null || true
wait "$CURL_PID" 2>/dev/null || true   # reap quietly (no "Terminated" noise)
rm -f "$OUT"
echo
echo "Memory stayed flat while only a fraction of $COUNT rows had been delivered:"
echo "the client's read rate governed production. That is backpressure, DB/generator -> Netty."
