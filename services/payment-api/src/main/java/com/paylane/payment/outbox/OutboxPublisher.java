package com.paylane.payment.outbox;

import com.paylane.payment.outbox.OutboxRepository.OutboxMessage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls the outbox and publishes committed events to SNS, then stamps them published.
 * Publish-then-mark makes delivery at-least-once: a crash between the two just republishes,
 * and the settlement-worker dedupes. Consumers must be idempotent — that is by design.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 50;

    // Writes each propagated header (traceparent, ...) as an SNS message attribute.
    private static final TextMapSetter<Map<String, MessageAttributeValue>> SETTER =
            (carrier, key, value) -> carrier.put(key, attr(value));

    private final OutboxRepository outbox;
    private final SnsClient sns;
    private final String topicArn;

    public OutboxPublisher(OutboxRepository outbox, SnsClient sns,
                           @Value("${paylane.sns.topic-arn}") String topicArn) {
        this.outbox = outbox;
        this.sns = sns;
        this.topicArn = topicArn;
    }

    @Scheduled(fixedDelayString = "${paylane.outbox.poll-ms:1000}")
    public void publishPending() {
        List<OutboxMessage> pending = outbox.fetchUnpublished(BATCH);
        for (OutboxMessage msg : pending) {
            try {
                Map<String, MessageAttributeValue> attributes = new HashMap<>();
                attributes.put("type", attr(msg.type()));
                attributes.put("aggregateId", attr(msg.aggregateId()));
                // Inject the current W3C trace context so the trace continues across SNS -> SQS
                // into the settlement worker (raw delivery carries these attributes through).
                GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                        .inject(Context.current(), attributes, SETTER);

                sns.publish(PublishRequest.builder()
                        .topicArn(topicArn)
                        .message(msg.payload())
                        .messageAttributes(attributes)
                        .build());
                outbox.markPublished(msg.id());
                log.debug("published outbox row {} ({})", msg.id(), msg.type());
            } catch (Exception e) {
                // Leave the row unpublished; the next tick retries.
                log.warn("failed to publish outbox row {}: {}", msg.id(), e.getMessage());
            }
        }
    }

    private static MessageAttributeValue attr(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
