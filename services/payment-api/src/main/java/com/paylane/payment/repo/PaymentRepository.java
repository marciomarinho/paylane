package com.paylane.payment.repo;

import com.paylane.payment.domain.Payment;
import com.paylane.payment.domain.PaymentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentRepository {

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new intent and return it hydrated with its generated id and timestamps. */
    public Payment insert(Payment intent) {
        return jdbc.sql("""
                        INSERT INTO payment (merchant_id, amount_minor, currency, status)
                        VALUES (:merchantId, :amount, :currency, :status)
                        RETURNING id, merchant_id, amount_minor, currency, status, created_at, updated_at
                        """)
                .param("merchantId", intent.merchantId())
                .param("amount", intent.amountMinor())
                .param("currency", intent.currency())
                .param("status", intent.status().name())
                .query(this::map)
                .single();
    }

    /** Persist a state transition. updated_at is bumped by the DB. */
    public void updateStatus(Payment payment) {
        jdbc.sql("UPDATE payment SET status = :status, updated_at = now() WHERE id = :id")
                .param("status", payment.status().name())
                .param("id", payment.id())
                .update();
    }

    public Optional<Payment> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, merchant_id, amount_minor, currency, status, created_at, updated_at
                        FROM payment WHERE id = :id
                        """)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    private Payment map(ResultSet rs, int rowNum) throws SQLException {
        return new Payment(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
