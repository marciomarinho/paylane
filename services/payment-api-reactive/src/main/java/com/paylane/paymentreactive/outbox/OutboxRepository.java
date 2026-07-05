package com.paylane.paymentreactive.outbox;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class OutboxRepository {

    public record OutboxMessage(long id, String aggregateType, String aggregateId,
                                String type, String payload) {}

    private final DatabaseClient db;

    public OutboxRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Long> append(String aggregateType, String aggregateId, String type, String payloadJson) {
        return db.sql("""
                        INSERT INTO outbox (aggregate_type, aggregate_id, type, payload)
                        VALUES (:aggType, :aggId, :type, CAST(:payload AS jsonb))
                        """)
                .bind("aggType", aggregateType)
                .bind("aggId", aggregateId)
                .bind("type", type)
                .bind("payload", payloadJson)
                .fetch()
                .rowsUpdated();
    }

    public Flux<OutboxMessage> fetchUnpublished(int limit) {
        return db.sql("""
                        SELECT id, aggregate_type, aggregate_id, type, payload::text AS payload
                        FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT :limit
                        """)
                .bind("limit", limit)
                .map((row, meta) -> new OutboxMessage(
                        row.get("id", Long.class),
                        row.get("aggregate_type", String.class),
                        row.get("aggregate_id", String.class),
                        row.get("type", String.class),
                        row.get("payload", String.class)))
                .all();
    }

    public Mono<Long> markPublished(long id) {
        return db.sql("UPDATE outbox SET published_at = now() WHERE id = :id")
                .bind("id", id)
                .fetch()
                .rowsUpdated();
    }
}
