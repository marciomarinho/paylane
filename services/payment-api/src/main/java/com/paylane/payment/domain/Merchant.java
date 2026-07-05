package com.paylane.payment.domain;

import java.time.Instant;
import java.util.UUID;

public record Merchant(UUID id, String name, String settlementAccount, String status, Instant createdAt) {}
