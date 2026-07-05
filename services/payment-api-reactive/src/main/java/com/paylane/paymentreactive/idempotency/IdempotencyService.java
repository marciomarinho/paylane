package com.paylane.paymentreactive.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Reactive idempotency — same contract as the MVC twin. The key claim, the effect, and the stored
 * response all commit in one reactive transaction ({@code TransactionalOperator}). A replay returns
 * the stored response; a key reused with a different body is a conflict.
 */
@Service
public class IdempotencyService {

    public record StoredResponse(int status, String body) {}

    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final ObjectMapper mapper;

    public IdempotencyService(DatabaseClient db, TransactionalOperator tx, ObjectMapper mapper) {
        this.db = db;
        this.tx = tx;
        this.mapper = mapper;
    }

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

    public Mono<StoredResponse> runIdempotent(String key, String fingerprint,
                                              int successStatus, Supplier<Mono<Object>> handler) {
        if (key == null || key.isBlank()) {
            return Mono.error(new IdempotencyExceptions.MissingKey());
        }

        Mono<StoredResponse> flow = db.sql("""
                        INSERT INTO idempotency_key (key, fingerprint) VALUES (:key, :fp)
                        ON CONFLICT (key) DO NOTHING
                        """)
                .bind("key", key)
                .bind("fp", fingerprint)
                .fetch()
                .rowsUpdated()
                .flatMap(claimed -> claimed == 0
                        ? replay(key, fingerprint)
                        : handler.get().flatMap(result -> record(key, successStatus, result)));

        return flow.as(tx::transactional);
    }

    private Mono<StoredResponse> record(String key, int successStatus, Object result) {
        String body = toJson(result);
        return db.sql("""
                        UPDATE idempotency_key SET response_status = :st, response_body = :body
                        WHERE key = :key
                        """)
                .bind("st", successStatus)
                .bind("body", body)
                .bind("key", key)
                .fetch()
                .rowsUpdated()
                .thenReturn(new StoredResponse(successStatus, body));
    }

    private Mono<StoredResponse> replay(String key, String fingerprint) {
        return db.sql("""
                        SELECT fingerprint, response_status, response_body
                        FROM idempotency_key WHERE key = :key
                        """)
                .bind("key", key)
                .map((row, meta) -> new String[]{
                        row.get("fingerprint", String.class),
                        row.get("response_status", Integer.class) == null ? null
                                : String.valueOf(row.get("response_status", Integer.class)),
                        row.get("response_body", String.class)})
                .one()
                .flatMap(cols -> {
                    if (!fingerprint.equals(cols[0])) {
                        return Mono.error(new IdempotencyExceptions.Conflict(key));
                    }
                    if (cols[2] == null) {
                        return Mono.error(new IdempotencyExceptions.InProgress(key));
                    }
                    return Mono.just(new StoredResponse(Integer.parseInt(cols[1]), cols[2]));
                });
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response for idempotency store", e);
        }
    }
}
