package se.ataulnasar.pricingexception.model;

/** Type of a {@code request_event} row. Exactly one event is written per status change (INV-02). */
public enum EventType {
    SUBMITTED,
    APPROVED,
    DECLINED,
    WITHDRAWN,
    SUPERSEDED
}
