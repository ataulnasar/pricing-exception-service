package se.ataulnasar.pricingexception.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/pricing-exceptions}. The discount range is checked by the service. */
public record CreateRequestBody(
        @NotBlank String applicationId,
        @NotNull Integer discountBps,
        @NotBlank @Size(max = 500) String reason) {
}
