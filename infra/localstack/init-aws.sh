#!/bin/bash
# Runs inside the LocalStack container once it is ready (mounted into init/ready.d).
# Wires the event backbone: SNS topic -> SQS queue (raw delivery) with a DLQ redrive policy.
set -euo pipefail

export AWS_DEFAULT_REGION=ap-southeast-2
ACCOUNT=000000000000
BASE=http://localhost:4566

TOPIC_ARN=$(awslocal sns create-topic --name payment-events --query TopicArn --output text)

awslocal sqs create-queue --queue-name settlement-dlq >/dev/null
awslocal sqs create-queue --queue-name settlement-queue --attributes VisibilityTimeout=30 >/dev/null

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "$BASE/$ACCOUNT/settlement-dlq" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

QUEUE_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "$BASE/$ACCOUNT/settlement-queue" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

# Redrive to DLQ after 3 failed receives (the poisoned message ends up here).
cat > /tmp/redrive.json <<EOF
{"RedrivePolicy":"{\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":\"3\"}"}
EOF
awslocal sqs set-queue-attributes --queue-url "$BASE/$ACCOUNT/settlement-queue" \
  --attributes file:///tmp/redrive.json

# Raw delivery: the SQS body is exactly the event JSON, no SNS envelope to unwrap.
awslocal sns subscribe \
  --topic-arn "$TOPIC_ARN" \
  --protocol sqs \
  --notification-endpoint "$QUEUE_ARN" \
  --attributes RawMessageDelivery=true >/dev/null

echo "paylane: localstack ready"
echo "  topic = $TOPIC_ARN"
echo "  queue = $QUEUE_ARN"
echo "  dlq   = $DLQ_ARN"
