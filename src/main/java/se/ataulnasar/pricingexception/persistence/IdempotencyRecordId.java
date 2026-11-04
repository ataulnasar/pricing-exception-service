package se.ataulnasar.pricingexception.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Primary key of {@code idempotency_record}: keys are scoped per caller. */
@Embeddable
public class IdempotencyRecordId implements Serializable {

    @Column(name = "caller_id", length = 64, nullable = false)
    private String callerId;

    @Column(name = "idem_key", length = 128, nullable = false)
    private String idemKey;

    protected IdempotencyRecordId() {
        // JPA
    }

    public IdempotencyRecordId(String callerId, String idemKey) {
        this.callerId = callerId;
        this.idemKey = idemKey;
    }

    public String getCallerId() {
        return callerId;
    }

    public String getIdemKey() {
        return idemKey;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IdempotencyRecordId other
                && Objects.equals(callerId, other.callerId) && Objects.equals(idemKey, other.idemKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callerId, idemKey);
    }

    @Override
    public String toString() {
        return callerId + "/" + idemKey;
    }
}
