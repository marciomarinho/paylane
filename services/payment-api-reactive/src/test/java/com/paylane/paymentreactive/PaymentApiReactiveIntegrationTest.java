package com.paylane.paymentreactive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.sns.SnsAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class PaymentApiReactiveIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName()));
        r.add("spring.r2dbc.username", postgres::getUsername);
        r.add("spring.r2dbc.password", postgres::getPassword);
        r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername);
        r.add("spring.flyway.password", postgres::getPassword);
        r.add("paylane.outbox.poll-ms", () -> "600000");
    }

    @MockitoBean
    SnsAsyncClient snsAsyncClient;

    @Autowired
    WebTestClient client;
    @Autowired
    ObjectMapper mapper;

    private String createMerchant() throws Exception {
        byte[] body = client.post().uri("/merchants")
                .header("Idempotency-Key", "m-" + System.nanoTime())
                .header("Content-Type", "application/json")
                .bodyValue("{\"name\":\"Acme\",\"settlementAccount\":\"BSB-1\"}")
                .exchange().expectStatus().isCreated()
                .expectBody().returnResult().getResponseBody();
        return mapper.readTree(body).get("id").asText();
    }

    @Test
    void happyPath_createAuthorizeCapture() throws Exception {
        String merchant = createMerchant();

        byte[] created = client.post().uri("/payments")
                .header("Idempotency-Key", "p1")
                .header("Content-Type", "application/json")
                .bodyValue("{\"merchantId\":\"" + merchant + "\",\"amountMinor\":10000,\"currency\":\"AUD\"}")
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("AUTHORIZED")
                .returnResult().getResponseBody();
        String paymentId = mapper.readTree(created).get("id").asText();

        client.post().uri("/payments/" + paymentId + "/capture")
                .header("Idempotency-Key", "c1")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CAPTURED");

        client.get().uri("/payments/" + paymentId)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CAPTURED");
    }

    @Test
    void idempotentReplay_returnsSamePayment() throws Exception {
        String merchant = createMerchant();
        String reqBody = "{\"merchantId\":\"" + merchant + "\",\"amountMinor\":7000,\"currency\":\"AUD\"}";

        byte[] first = client.post().uri("/payments").header("Idempotency-Key", "dup")
                .header("Content-Type", "application/json").bodyValue(reqBody)
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody();
        byte[] second = client.post().uri("/payments").header("Idempotency-Key", "dup")
                .header("Content-Type", "application/json").bodyValue(reqBody)
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody();

        assertThat(mapper.readTree(second).get("id").asText())
                .isEqualTo(mapper.readTree(first).get("id").asText());
    }

    @Test
    void missingIdempotencyKey_isBadRequest() throws Exception {
        String merchant = createMerchant();
        client.post().uri("/payments")
                .header("Content-Type", "application/json")
                .bodyValue("{\"merchantId\":\"" + merchant + "\",\"amountMinor\":10000,\"currency\":\"AUD\"}")
                .exchange().expectStatus().isBadRequest();
    }
}
