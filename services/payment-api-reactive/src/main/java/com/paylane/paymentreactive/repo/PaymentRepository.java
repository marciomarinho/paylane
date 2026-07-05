package com.paylane.paymentreactive.repo;

import com.paylane.paymentreactive.domain.Payment;
import com.paylane.paymentreactive.domain.PaymentStatus;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class PaymentRepository {

    private final DatabaseClient db;

    public PaymentRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Payment> insert(Payment intent) {
        return db.sql("""
                        INSERT INTO payment (merchant_id, amount_minor, currency, status)
                        VALUES (:merchantId, :amount, :currency, :status)
                        RETURNING id, merchant_id, amount_minor, currency, status, created_at, updated_at
                        """)
                .bind("merchantId", intent.merchantId())
                .bind("amount", intent.amountMinor())
                .bind("currency", intent.currency())
                .bind("status", intent.status().name())
                .map(this::map)
                .one();
    }

    public Mono<Long> updateStatus(Payment payment) {
        return db.sql("UPDATE payment SET status = :status, updated_at = now() WHERE id = :id")
                .bind("status", payment.status().name())
                .bind("id", payment.id())
                .fetch()
                .rowsUpdated();
    }

    /**
     * Stream every payment as a {@link Flux}. R2DBC fetches rows on demand (Reactive Streams
     * {@code request(n)}), so a slow HTTP consumer throttles the database cursor rather than the
     * service buffering the whole result set — end-to-end backpressure, DB → Netty.
     */
    public Flux<Payment> streamAll() {
        return db.sql("""
                        SELECT id, merchant_id, amount_minor, currency, status, created_at, updated_at
                        FROM payment ORDER BY created_at
                        """)
                .map(this::map)
                .all();
    }

    public Mono<Payment> find(UUID id) {
        return db.sql("""
                        SELECT id, merchant_id, amount_minor, currency, status, created_at, updated_at
                        FROM payment WHERE id = :id
                        """)
                .bind("id", id)
                .map(this::map)
                .one();
    }

    private Payment map(Row row, RowMetadata meta) {
        return new Payment(
                row.get("id", UUID.class),
                row.get("merchant_id", UUID.class),
                row.get("amount_minor", Long.class),
                row.get("currency", String.class),
                PaymentStatus.valueOf(row.get("status", String.class)),
                row.get("created_at", OffsetDateTime.class).toInstant(),
                row.get("updated_at", OffsetDateTime.class).toInstant());
    }
}
