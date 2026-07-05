#!/usr/bin/env bash
# Demonstrates the DLQ path: drop a malformed message straight onto the settlement queue.
# The worker fails to parse it, never deletes it, and after maxReceiveCount (3) SQS redrives it
# to settlement-dlq. Run against a live stack.
set -euo pipefail

Q=http://localhost:4566/000000000000/settlement-queue

echo "sending a poisoned message to $Q ..."
docker compose exec -T localstack \
  awslocal sqs send-message --queue-url "$Q" \
  --message-body '{"paymentId":null,"merchantId":null,"amountMinor":-1,"garbage":true}' >/dev/null

echo "watching the DLQ (give it ~1.5 min for 3 receives + visibility timeout)..."
for _ in $(seq 1 40); do
  N=$(docker compose exec -T localstack \
    awslocal sqs get-queue-attributes \
    --queue-url http://localhost:4566/000000000000/settlement-dlq \
    --attribute-names ApproximateNumberOfMessages \
    --query 'Attributes.ApproximateNumberOfMessages' --output text 2>/dev/null || echo 0)
  printf '  messages in DLQ = %s\r' "$N"
  [ "$N" != "0" ] && { echo; echo "poisoned message landed in the DLQ."; exit 0; }
  sleep 3
done
echo; echo "nothing in DLQ yet — check settlement-worker logs."
