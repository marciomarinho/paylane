package com.paylane.paymentreactive.repo;

import com.paylane.paymentreactive.domain.Merchant;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class MerchantRepository {

    private final DatabaseClient db;

    public MerchantRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Merchant> insert(String name, String settlementAccount) {
        return db.sql("""
                        INSERT INTO merchant (name, settlement_account)
                        VALUES (:name, :account)
                        RETURNING id, name, settlement_account, status, created_at
                        """)
                .bind("name", name)
                .bind("account", settlementAccount)
                .map(this::map)
                .one();
    }

    public Mono<Boolean> exists(UUID id) {
        return db.sql("SELECT 1 FROM merchant WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> 1)
                .one()
                .hasElement();
    }

    private Merchant map(Row row, RowMetadata meta) {
        return new Merchant(
                row.get("id", UUID.class),
                row.get("name", String.class),
                row.get("settlement_account", String.class),
                row.get("status", String.class),
                row.get("created_at", OffsetDateTime.class).toInstant());
    }
}
