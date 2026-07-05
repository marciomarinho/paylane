package com.paylane.payment.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Idempotency for writes. The key row and the effect commit in a single transaction, so a
 * committed key always carries the exact response the caller first received. A replay returns
 * that stored response verbatim; a key reused with a different body is rejected.
 */
@Service
public class IdempotencyService {

    public record StoredResponse(int status, String body) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** A stable fingerprint of the request, used to detect key reuse across different bodies. */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update((p == null ? "" : p).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Transactional
    public StoredResponse runIdempotent(String key, String fingerprint,
                                        int successStatus, Supplier<Object> handler) {
        if (key == null || key.isBlank()) {
            throw new IdempotencyExceptions.MissingKey();
        }

        // Claim the key. If another (committed) request already holds it, this inserts nothing.
        int claimed = jdbc.sql("""
                        INSERT INTO idempotency_key (key, fingerprint) VALUES (:key, :fp)
                        ON CONFLICT (key) DO NOTHING
                        """)
                .param("key", key)
                .param("fp", fingerprint)
                .update();

        if (claimed == 0) {
            return replay(key, fingerprint);
        }

        // Fresh request: do the work in THIS transaction, then record its response atomically.
        Object result = handler.get();
        String body = toJson(result);
        jdbc.sql("""
                        UPDATE idempotency_key SET response_status = :st, response_body = :body
                        WHERE key = :key
                        """)
                .param("st", successStatus)
                .param("body", body)
                .param("key", key)
                .update();
        return new StoredResponse(successStatus, body);
    }

    private StoredResponse replay(String key, String fingerprint) {
        record Row(String fingerprint, Integer status, String body) {}
        Row row = jdbc.sql("""
                        SELECT fingerprint, response_status, response_body
                        FROM idempotency_key WHERE key = :key
                        """)
                .param("key", key)
                .query((rs, rn) -> new Row(
                        rs.getString("fingerprint"),
                        (Integer) rs.getObject("response_status"),
                        rs.getString("response_body")))
                .single();

        if (!fingerprint.equals(row.fingerprint())) {
            throw new IdempotencyExceptions.Conflict(key);
        }
        if (row.body() == null) {
            throw new IdempotencyExceptions.InProgress(key);
        }
        return new StoredResponse(row.status(), row.body());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response for idempotency store", e);
        }
    }
}
