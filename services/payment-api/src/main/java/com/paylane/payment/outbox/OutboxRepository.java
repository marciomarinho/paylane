package com.paylane.payment.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OutboxRepository {

    public record OutboxMessage(long id, String aggregateType, String aggregateId,
                                String type, String payload) {}

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Append an event. Called inside the same transaction as the domain change it describes. */
    public void append(String aggregateType, String aggregateId, String type, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO outbox (aggregate_type, aggregate_id, type, payload)
                        VALUES (:aggType, :aggId, :type, CAST(:payload AS jsonb))
                        """)
                .param("aggType", aggregateType)
                .param("aggId", aggregateId)
                .param("type", type)
                .param("payload", payloadJson)
                .update();
    }

    public List<OutboxMessage> fetchUnpublished(int limit) {
        return jdbc.sql("""
                        SELECT id, aggregate_type, aggregate_id, type, payload::text AS payload
                        FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY id
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, rn) -> new OutboxMessage(
                        rs.getLong("id"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("type"),
                        rs.getString("payload")))
                .list();
    }

    public void markPublished(long id) {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }
}
