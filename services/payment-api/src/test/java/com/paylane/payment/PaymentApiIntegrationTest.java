package com.paylane.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylane.payment.web.Views.PaymentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.sns.SnsClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PaymentApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // Keep the outbox poller idle during tests; we assert on the outbox table directly.
        registry.add("paylane.outbox.poll-ms", () -> "600000");
    }

    @MockitoBean
    SnsClient snsClient;   // no LocalStack in unit tests

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcClient jdbc;

    private String newMerchant() throws Exception {
        String body = mvc.perform(post("/merchants")
                        .header("Idempotency-Key", "m-" + System.nanoTime())
                        .contentType("application/json")
                        .content("""
                                {"name":"Acme","settlementAccount":"BSB-123-456"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asText();
    }

    private PaymentResponse createPayment(String merchantId, String key) throws Exception {
        String body = mvc.perform(post("/payments")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"merchantId\":\"" + merchantId + "\",\"amountMinor\":10000,\"currency\":\"AUD\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readValue(body, PaymentResponse.class);
    }

    @Test
    void happyPath_createAuthorizeCaptureEmitsOutboxEvent() throws Exception {
        String merchant = newMerchant();
        PaymentResponse created = createPayment(merchant, "pay-happy");
        assertThat(created.status()).isEqualTo("AUTHORIZED");

        mvc.perform(post("/payments/" + created.id() + "/capture")
                        .header("Idempotency-Key", "cap-happy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        mvc.perform(get("/payments/" + created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        Long events = jdbc.sql("SELECT count(*) FROM outbox WHERE type = 'payment.captured' AND aggregate_id = :id")
                .param("id", created.id().toString())
                .query(Long.class).single();
        assertThat(events).isEqualTo(1);
    }

    @Test
    void idempotentReplay_returnsSamePaymentOnce() throws Exception {
        String merchant = newMerchant();
        PaymentResponse first = createPayment(merchant, "pay-idem");
        PaymentResponse second = createPayment(merchant, "pay-idem");

        assertThat(second.id()).isEqualTo(first.id());
        Long count = jdbc.sql("SELECT count(*) FROM payment WHERE id = :id")
                .param("id", first.id()).query(Long.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void keyReusedWithDifferentBody_isConflict() throws Exception {
        String merchant = newMerchant();
        createPayment(merchant, "pay-reuse");

        mvc.perform(post("/payments")
                        .header("Idempotency-Key", "pay-reuse")
                        .contentType("application/json")
                        .content("{\"merchantId\":\"" + merchant + "\",\"amountMinor\":99999,\"currency\":\"AUD\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void missingIdempotencyKey_isBadRequest() throws Exception {
        String merchant = newMerchant();
        mvc.perform(post("/payments")
                        .contentType("application/json")
                        .content("{\"merchantId\":\"" + merchant + "\",\"amountMinor\":10000,\"currency\":\"AUD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void doubleCaptureWithDifferentKey_isRejectedByStateMachine() throws Exception {
        String merchant = newMerchant();
        PaymentResponse p = createPayment(merchant, "pay-dc");

        mvc.perform(post("/payments/" + p.id() + "/capture").header("Idempotency-Key", "cap-1"))
                .andExpect(status().isOk());
        // Second capture is a new request (different key) against an already-CAPTURED payment.
        mvc.perform(post("/payments/" + p.id() + "/capture").header("Idempotency-Key", "cap-2"))
                .andExpect(status().isConflict());
    }
}
