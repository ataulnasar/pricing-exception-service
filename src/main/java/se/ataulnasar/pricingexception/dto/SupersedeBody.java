package se.ataulnasar.pricingexception.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/pricing-exceptions/{id}/supersessions}: the new terms. */
public record SupersedeBody(
        @NotNull Integer discountBps,
        @NotBlank @Size(max = 500) String reason) {
}
