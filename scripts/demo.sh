#!/usr/bin/env bash
# End-to-end demo: create a merchant, take three payments, watch them flow through SNS/SQS to the
# settlement worker, settle, and show the ledger balancing to zero. Requires the stack to be up
# (docker compose up --build) and python3 + curl on the host.
set -euo pipefail

PAYMENT=${PAYMENT_URL:-http://localhost:8081}
LEDGER=${LEDGER_URL:-http://localhost:8082}
SETTLE=${SETTLE_URL:-http://localhost:8083}

bold() { printf '\n\033[1m%s\033[0m\n' "$1"; }
field() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

wait_health() {
  printf 'waiting for %s ' "$1"
  for _ in $(seq 1 60); do
    if curl -fsS "$2/actuator/health" >/dev/null 2>&1; then echo 'ok'; return; fi
    printf '.'; sleep 2
  done
  echo 'TIMEOUT'; exit 1
}

bold "0. Waiting for services"
wait_health payment-api "$PAYMENT"
wait_health ledger "$LEDGER"
wait_health settlement-worker "$SETTLE"

bold "1. Onboard a merchant"
MERCHANT=$(curl -fsS -X POST "$PAYMENT/merchants" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: merchant-acme" \
  -d '{"name":"Acme Coffee","settlementAccount":"BSB-062-000-12345678"}')
echo "$MERCHANT"
MERCHANT_ID=$(echo "$MERCHANT" | field "['id']")

bold "2. Take three payments (create -> authorize -> capture)"
TOTAL=0
for AMOUNT in 12000 5000 8000; do
  KEY="pay-${AMOUNT}"
  PAYMENT_JSON=$(curl -fsS -X POST "$PAYMENT/payments" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
    -d "{\"merchantId\":\"$MERCHANT_ID\",\"amountMinor\":$AMOUNT,\"currency\":\"AUD\"}")
  PID=$(echo "$PAYMENT_JSON" | field "['id']")
  curl -fsS -X POST "$PAYMENT/payments/$PID/capture" -H "Idempotency-Key: cap-$AMOUNT" >/dev/null
  echo "  captured $AMOUNT  payment=$PID"
  TOTAL=$((TOTAL + AMOUNT))
done
echo "  gross captured = $TOTAL minor units"

bold "3. Idempotency: replay 'pay-12000' with the same key -> same payment, no new charge"
REPLAY=$(curl -fsS -X POST "$PAYMENT/payments" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: pay-12000" \
  -d "{\"merchantId\":\"$MERCHANT_ID\",\"amountMinor\":12000,\"currency\":\"AUD\"}")
echo "  replayed payment id = $(echo "$REPLAY" | field "['id']")  (status $(echo "$REPLAY" | field "['status']"))"

bold "4. Wait for the worker to consume payment.captured and post captures to the ledger"
for _ in $(seq 1 30); do
  SR=$(curl -fsS "$LEDGER/accounts/scheme_receivable/balance" | field "['balanceMinor']")
  printf '  scheme_receivable = %s\r' "$SR"
  [ "$SR" -ge "$TOTAL" ] && break
  sleep 2
done
echo

bold "5. Run settlement (batch per merchant, reconcile, pay out)"
curl -fsS -X POST "$SETTLE/settlements/run" | python3 -m json.tool

bold "6. Ledger balances — the books net to zero"
curl -fsS "$LEDGER/accounts" | python3 -m json.tool
echo
TOTAL_BAL=$(curl -fsS "$LEDGER/accounts" | python3 -c "import sys,json;print(sum(a['balanceMinor'] for a in json.load(sys.stdin)))")
echo "sum of all account balances = $TOTAL_BAL  (must be 0)"
