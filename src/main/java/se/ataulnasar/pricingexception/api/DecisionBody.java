package se.ataulnasar.pricingexception.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import se.ataulnasar.pricingexception.application.Decision;

/** Body of {@code POST /api/pricing-exceptions/{id}/decision}. decidedBy/decidedAt are never read from here. */
public record DecisionBody(
        @NotNull Decision outcome,
        @Size(max = 500) String comment) {
}
