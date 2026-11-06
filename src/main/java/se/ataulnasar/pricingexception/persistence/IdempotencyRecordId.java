package se.ataulnasar.pricingexception.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** Primary key of {@code idempotency_record}: keys are scoped per caller. */
@Embeddable
public record IdempotencyRecordId(
        @Column(name = "caller_id", length = 64, nullable = false) String callerId,
        @Column(name = "idem_key", length = 128, nullable = false) String idemKey) implements Serializable {
}
