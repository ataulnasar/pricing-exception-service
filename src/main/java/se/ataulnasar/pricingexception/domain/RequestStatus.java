package se.ataulnasar.pricingexception.domain;

/** Lifecycle of a request. Only PENDING can change (INV-05). */
public enum RequestStatus {
    PENDING,
    APPROVED,
    DECLINED,
    WITHDRAWN,
    SUPERSEDED;

    /** Active requests count towards "max one per application" (INV-04). */
    public boolean isActive() {
        return this == PENDING || this == APPROVED;
    }

    /** A final request can never change status again (INV-05). */
    public boolean isFinal() {
        return this != PENDING;
    }
}
