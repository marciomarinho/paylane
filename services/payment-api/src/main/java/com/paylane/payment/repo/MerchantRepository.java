package com.paylane.payment.repo;

import com.paylane.payment.domain.Merchant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MerchantRepository {

    private final JdbcClient jdbc;

    public MerchantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Merchant insert(String name, String settlementAccount) {
        return jdbc.sql("""
                        INSERT INTO merchant (name, settlement_account)
                        VALUES (:name, :account)
                        RETURNING id, name, settlement_account, status, created_at
                        """)
                .param("name", name)
                .param("account", settlementAccount)
                .query(this::mapMerchant)
                .single();
    }

    public Optional<Merchant> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, name, settlement_account, status, created_at
                        FROM merchant WHERE id = :id
                        """)
                .param("id", id)
                .query(this::mapMerchant)
                .optional();
    }

    public boolean exists(UUID id) {
        return find(id).isPresent();
    }

    public List<Merchant> listAll() {
        return jdbc.sql("""
                        SELECT id, name, settlement_account, status, created_at
                        FROM merchant ORDER BY created_at
                        """)
                .query(this::mapMerchant)
                .list();
    }

    private Merchant mapMerchant(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Merchant(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("settlement_account"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }
}
