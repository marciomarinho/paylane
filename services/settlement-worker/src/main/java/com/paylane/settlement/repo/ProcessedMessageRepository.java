package com.paylane.settlement.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedMessageRepository {

    private final JdbcClient jdbc;

    public ProcessedMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record that a message key was handled. Returns true if this is the first time (proceed),
     * false if it was already processed (a redelivery to swallow). This is the dedupe that turns
     * at-least-once delivery into effectively-once processing.
     */
    public boolean markProcessed(String messageKey) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_message (message_key) VALUES (:key)
                        ON CONFLICT (message_key) DO NOTHING
                        """)
                .param("key", messageKey)
                .update();
        return inserted == 1;
    }
}
