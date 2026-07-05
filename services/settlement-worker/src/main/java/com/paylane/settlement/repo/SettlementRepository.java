package com.paylane.settlement.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SettlementRepository {

    public record Item(long id, UUID paymentId, UUID merchantId, long amountMinor, long feeMinor) {}

    public record Batch(long id, UUID merchantId, long grossMinor, long feeMinor,
                        long payoutMinor, String status, Instant createdAt) {}

    private final JdbcClient jdbc;

    public SettlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a captured payment as a pending item. Idempotent on payment_id. */
    public boolean insertItem(UUID paymentId, UUID merchantId, long amountMinor, long feeMinor) {
        int inserted = jdbc.sql("""
                        INSERT INTO settlement_item (payment_id, merchant_id, amount_minor, fee_minor)
                        VALUES (:pid, :mid, :amount, :fee)
                        ON CONFLICT (payment_id) DO NOTHING
                        """)
                .param("pid", paymentId)
                .param("mid", merchantId)
                .param("amount", amountMinor)
                .param("fee", feeMinor)
                .update();
        return inserted == 1;
    }

    public List<UUID> merchantsWithUnbatchedItems() {
        return jdbc.sql("SELECT DISTINCT merchant_id FROM settlement_item WHERE batch_id IS NULL")
                .query((rs, rn) -> rs.getObject("merchant_id", UUID.class))
                .list();
    }

    public List<Item> unbatchedItems(UUID merchantId) {
        return jdbc.sql("""
                        SELECT id, payment_id, merchant_id, amount_minor, fee_minor
                        FROM settlement_item WHERE merchant_id = :mid AND batch_id IS NULL
                        ORDER BY id
                        """)
                .param("mid", merchantId)
                .query(this::mapItem)
                .list();
    }

    public long createBatch(UUID merchantId, long gross, long fee, long payout, String status) {
        return jdbc.sql("""
                        INSERT INTO settlement_batch (merchant_id, gross_minor, fee_minor, payout_minor, status)
                        VALUES (:mid, :gross, :fee, :payout, :status)
                        RETURNING id
                        """)
                .param("mid", merchantId)
                .param("gross", gross)
                .param("fee", fee)
                .param("payout", payout)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    public void assignItemsToBatch(List<Long> itemIds, long batchId) {
        jdbc.sql("UPDATE settlement_item SET batch_id = :batch WHERE id = ANY(:ids)")
                .param("batch", batchId)
                .param("ids", itemIds.toArray(new Long[0]))
                .update();
    }

    public List<Batch> listBatches() {
        return jdbc.sql("""
                        SELECT id, merchant_id, gross_minor, fee_minor, payout_minor, status, created_at
                        FROM settlement_batch ORDER BY id DESC
                        """)
                .query(this::mapBatch)
                .list();
    }

    public Optional<Batch> findBatch(long id) {
        return jdbc.sql("""
                        SELECT id, merchant_id, gross_minor, fee_minor, payout_minor, status, created_at
                        FROM settlement_batch WHERE id = :id
                        """)
                .param("id", id)
                .query(this::mapBatch)
                .optional();
    }

    private Item mapItem(ResultSet rs, int rn) throws SQLException {
        return new Item(
                rs.getLong("id"),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getLong("amount_minor"),
                rs.getLong("fee_minor"));
    }

    private Batch mapBatch(ResultSet rs, int rn) throws SQLException {
        return new Batch(
                rs.getLong("id"),
                rs.getObject("merchant_id", UUID.class),
                rs.getLong("gross_minor"),
                rs.getLong("fee_minor"),
                rs.getLong("payout_minor"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }
}
