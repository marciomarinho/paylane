package com.paylane.settlement.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylane.settlement.events.CapturedEvent;
import com.paylane.settlement.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Long-polls the settlement queue and hands each payment.captured event to the service.
 * A message is deleted only after it is processed; anything that throws is left on the queue,
 * so SQS redelivers it and, past the redrive count, parks it in the DLQ. The poisoned seed
 * message rides this exact path.
 */
@Component
@ConditionalOnProperty(name = "paylane.sqs.enabled", havingValue = "true", matchIfMissing = true)
public class SqsPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsPoller.class);

    private final SqsClient sqs;
    private final SettlementService service;
    private final ObjectMapper mapper;
    private final String queueUrl;

    private volatile boolean running = false;
    private ExecutorService executor;

    public SqsPoller(SqsClient sqs, SettlementService service, ObjectMapper mapper,
                     @Value("${paylane.sqs.queue-url}") String queueUrl) {
        this.sqs = sqs;
        this.service = service;
        this.mapper = mapper;
        this.queueUrl = queueUrl;
    }

    @Override
    public void start() {
        running = true;
        executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("sqs-poller").factory());
        executor.submit(this::pollLoop);
        log.info("SQS poller started on {}", queueUrl);
    }

    private void pollLoop() {
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(20)
                        .build()).messages();
                for (Message message : messages) {
                    handle(message);
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("poll loop error: {}", e.getMessage());
                    sleep();
                }
            }
        }
    }

    private void handle(Message message) {
        try {
            CapturedEvent event = mapper.readValue(message.body(), CapturedEvent.class);
            if (event.paymentId() == null || event.merchantId() == null || event.amountMinor() <= 0) {
                throw new IllegalArgumentException("malformed capture event: " + message.body());
            }
            service.handleCapture(event);
            sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (Exception e) {
            // Leave it on the queue: redelivery, then DLQ. Do not delete.
            log.warn("failed to process message {}: {}", message.messageId(), e.getMessage());
        }
    }

    private void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
