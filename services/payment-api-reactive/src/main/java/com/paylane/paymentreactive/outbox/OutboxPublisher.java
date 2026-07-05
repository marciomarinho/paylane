package com.paylane.paymentreactive.outbox;

import com.paylane.paymentreactive.outbox.OutboxRepository.OutboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Reactive transactional-outbox publisher: poll unpublished rows, publish to SNS via the async
 * client, stamp published. Publish-then-mark keeps delivery at-least-once, same as the MVC twin.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 50;

    private final OutboxRepository outbox;
    private final SnsAsyncClient sns;
    private final String topicArn;

    public OutboxPublisher(OutboxRepository outbox, SnsAsyncClient sns,
                           @Value("${paylane.sns.topic-arn}") String topicArn) {
        this.outbox = outbox;
        this.sns = sns;
        this.topicArn = topicArn;
    }

    @Scheduled(fixedDelayString = "${paylane.outbox.poll-ms:1000}")
    public void publishPending() {
        outbox.fetchUnpublished(BATCH)
                .concatMap(this::publishOne)
                .subscribe();
    }

    private Mono<Long> publishOne(OutboxMessage msg) {
        PublishRequest req = PublishRequest.builder()
                .topicArn(topicArn)
                .message(msg.payload())
                .messageAttributes(java.util.Map.of(
                        "type", attr(msg.type()),
                        "aggregateId", attr(msg.aggregateId())))
                .build();
        return Mono.fromFuture(() -> sns.publish(req))
                .then(outbox.markPublished(msg.id()))
                .onErrorResume(e -> {
                    log.warn("failed to publish outbox row {}: {}", msg.id(), e.getMessage());
                    return Mono.empty();
                });
    }

    private static MessageAttributeValue attr(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
