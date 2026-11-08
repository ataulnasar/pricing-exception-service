package se.ataulnasar.pricingexception.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/pricing-exceptions/{id}/decision}. decidedBy/decidedAt are never read from here. */
public record DecisionBody(
        @NotNull Decision outcome,
        @Size(max = 500) String comment) {
}
