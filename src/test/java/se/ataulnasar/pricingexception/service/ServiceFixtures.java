package se.ataulnasar.pricingexception.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import se.ataulnasar.pricingexception.config.PricingProperties;
import se.ataulnasar.pricingexception.exception.BusinessRuleException;
import se.ataulnasar.pricingexception.model.Actor;
import se.ataulnasar.pricingexception.model.ApplicationStatus;
import se.ataulnasar.pricingexception.model.EventType;
import se.ataulnasar.pricingexception.model.MortgageApplicationEntity;
import se.ataulnasar.pricingexception.model.PricingExceptionRequestEntity;
import se.ataulnasar.pricingexception.model.RequestEventEntity;
import se.ataulnasar.pricingexception.model.RequestStatus;
import se.ataulnasar.pricingexception.repository.MortgageApplicationRepository;
import se.ataulnasar.pricingexception.repository.PricingExceptionRequestRepository;

/** Shared test data and the mocked collaborators of the service. Fixed (settable) clock, seed-data users from docs/design.md. */
final class ServiceFixtures {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final Instant LATER = Instant.parse("2026-01-16T09:30:00Z");

    static final String APPLICATION_ID = "MA-1001";
    static final int MAX_DISCOUNT = 100;

    static final Actor ANNA = Actor.rm("rm-anna");
    static final Actor ERIK = Actor.rm("rm-erik");
    static final Actor SARA = Actor.reviewer("rev-sara", 50);
    static final Actor JOHAN = Actor.reviewer("rev-johan", 150);
    static final Actor MORTGAGE = Actor.mortgageProcess("mortgage-system");

    private ServiceFixtures() {}

    /** A clock fixed at one instant that a test can move on purpose. */
    static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The service under test with mocked repositories, as the task describes. */
    static final class Harness {
        final PricingExceptionRequestRepository requests = mock(PricingExceptionRequestRepository.class);
        final MortgageApplicationRepository applications = mock(MortgageApplicationRepository.class);
        final IdempotencyService idempotency = mock(IdempotencyService.class);
        final TestClock clock = new TestClock(NOW);
        final PricingExceptionService service;

        Harness() {
            this(MAX_DISCOUNT);
        }

        Harness(int maxDiscountBps) {
            service = new PricingExceptionService(requests, applications,
                    new PricingProperties(maxDiscountBps, new PricingProperties.Idempotency(null, null)), clock, idempotency);
            // saveAndFlush returns its argument; like JPA it sets the version of a new row
            when(requests.saveAndFlush(any())).thenAnswer(inv -> {
                PricingExceptionRequestEntity e = inv.getArgument(0);
                if (e.getVersion() == null) setVersion(e, 0L);
                return e;
            });
            setApplicationStatus(ApplicationStatus.OPEN);
            when(requests.findByActiveApplicationId(any())).thenReturn(Optional.empty());
        }

        void setApplicationStatus(ApplicationStatus status) {
            when(applications.findById(any())).thenAnswer(inv -> Optional.of(
                    new MortgageApplicationEntity(inv.getArgument(0), "CUST-1", 450, status, "rm-anna")));
        }

        /** requests.findById(entity.getId()) returns the entity under test. */
        <T extends PricingExceptionRequestEntity> T stub(T entity) {
            when(requests.findById(entity.getId())).thenReturn(Optional.of(entity));
            return entity;
        }

        void assertNothingSaved() {
            verify(requests, never()).saveAndFlush(any());
        }
    }

    static void setVersion(PricingExceptionRequestEntity e, long version) {
        try {
            Field f = PricingExceptionRequestEntity.class.getDeclaredField("version");
            f.setAccessible(true);
            f.set(e, version);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static PricingExceptionRequestEntity pending(int discountBps) {
        return pendingBy("rm-anna", discountBps);
    }

    static PricingExceptionRequestEntity pending() {
        return pending(40);
    }

    static PricingExceptionRequestEntity pendingBy(String requestedBy, int discountBps) {
        PricingExceptionRequestEntity e = new PricingExceptionRequestEntity();
        e.setId(UUID.randomUUID());
        e.setApplicationId(APPLICATION_ID);
        e.setRequestedBy(requestedBy);
        e.setDiscountBps(discountBps);
        e.setReason("Long-standing customer");
        e.setCreatedAt(NOW);
        e.setStatus(RequestStatus.PENDING);
        e.setActiveApplicationId(APPLICATION_ID);
        e.getEvents().add(new RequestEventEntity(EventType.SUBMITTED, requestedBy, NOW, null, RequestStatus.PENDING, null));
        setVersion(e, 0L);
        return e;
    }

    /** An entity in the given status with the fields a real transition sets (Anna owns it, Johan decided). */
    static PricingExceptionRequestEntity inStatus(RequestStatus status) {
        PricingExceptionRequestEntity e = pending();
        if (status == RequestStatus.PENDING) {
            return e;
        }
        boolean decided = status == RequestStatus.APPROVED || status == RequestStatus.DECLINED;
        String actor = decided ? "rev-johan" : "rm-anna";
        e.setStatus(status);
        e.setActiveApplicationId(status.isActive() ? APPLICATION_ID : null);
        if (decided) {
            e.setDecidedBy("rev-johan");
            e.setDecidedAt(NOW);
        }
        if (status == RequestStatus.APPROVED) {
            e.setApprovedDiscountBps(e.getDiscountBps());
        }
        e.getEvents().add(new RequestEventEntity(EventType.valueOf(status.name()), actor, NOW, RequestStatus.PENDING, status, null));
        return e;
    }

    /** Everything a command may change, to prove a rejected command changed nothing. */
    record Snapshot(RequestStatus status, List<RequestEventEntity> events, String decidedBy, Instant decidedAt, String comment,
                    Integer approved, String active, int discountBps, String reason, Long version) {
        static Snapshot of(PricingExceptionRequestEntity r) {
            return new Snapshot(r.getStatus(), List.copyOf(r.getEvents()), r.getDecidedBy(), r.getDecidedAt(),
                    r.getDecisionComment(), r.getApprovedDiscountBps(), r.getActiveApplicationId(), r.getDiscountBps(),
                    r.getReason(), r.getVersion());
        }
    }

    /** The command is rejected with the given error code, the entity is left exactly as it was and nothing was saved. */
    static void assertRejectedAndUntouched(Harness h, PricingExceptionRequestEntity r, Consumer<PricingExceptionService> cmd,
                                           String code) {
        Snapshot before = Snapshot.of(r);
        assertThatThrownBy(() -> cmd.accept(h.service))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.code()).isEqualTo(code));
        assertThat(Snapshot.of(r)).isEqualTo(before);
        h.assertNothingSaved();
    }
}
