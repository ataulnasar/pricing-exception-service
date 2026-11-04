package se.ataulnasar.pricingexception.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Shared test data. Fixed clock, seed-data users from docs/design.md. */
final class Fixtures {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Instant LATER = Instant.parse("2026-01-16T09:30:00Z");
    static final Clock LATER_CLOCK = Clock.fixed(LATER, ZoneOffset.UTC);

    static final String APPLICATION_ID = "MA-1001";
    static final int MAX_DISCOUNT = 100;

    static final Actor ANNA = Actor.rm("rm-anna");
    static final Actor ERIK = Actor.rm("rm-erik");
    static final Actor SARA = Actor.reviewer("rev-sara", 50);
    static final Actor JOHAN = Actor.reviewer("rev-johan", 150);
    static final Actor MORTGAGE = Actor.mortgageProcess("mortgage-system");

    private Fixtures() {}

    static PricingExceptionRequest pending(int discountBps) {
        return PricingExceptionRequest.submit(
                UUID.randomUUID(), APPLICATION_ID, ANNA,
                DiscountBps.of(discountBps, MAX_DISCOUNT),
                new Reason("Long-standing customer"), null, CLOCK);
    }

    static PricingExceptionRequest pending() {
        return pending(40);
    }

    /** Builds a request in the given status by running real commands (Anna owns it). */
    static PricingExceptionRequest inStatus(RequestStatus status) {
        PricingExceptionRequest r = pending();
        switch (status) {
            case PENDING -> { }
            case APPROVED -> r.approve(JOHAN, null, CLOCK);
            case DECLINED -> r.decline(JOHAN, null, CLOCK);
            case WITHDRAWN -> r.withdraw(ANNA, CLOCK);
            case SUPERSEDED -> r.markSuperseded(ANNA, CLOCK);
        }
        return r;
    }
}
