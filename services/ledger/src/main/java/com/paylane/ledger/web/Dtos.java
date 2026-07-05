package com.paylane.ledger.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Request DTOs for the ledger API. Responses reuse the repo read-model records directly. */
public final class Dtos {
    private Dtos() {}

    public record PostingDto(@NotBlank String account, long amountMinor) {}

    public record PostEntryRequest(
            @NotBlank String externalRef,
            @NotBlank String description,
            @NotEmpty List<@Valid PostingDto> postings) {}
}
