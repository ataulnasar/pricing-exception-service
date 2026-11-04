package se.ataulnasar.pricingexception.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A pricing exception request: an RM asks for a discount on one mortgage application.
 *
 * <p>The discount and reason never change after {@link #submit} (INV-01). The only thing that
 * moves is the status, and only while PENDING (INV-05). Every status change appends exactly one
 * {@link RequestEvent} (INV-02). Decisions are checked in this order: status, role, self-review,
 * approval limit, then the comment length (docs/design.md "Domain error codes and events"). All checks
 * run before any state is touched, so a rejected command leaves the request exactly as it was.
 */
public final class PricingExceptionRequest {

    public static final int MAX_COMMENT_LENGTH = 500;

    private final UUID id;
    private final String applicationId;
    private final String requestedBy;
    private final DiscountBps discount;
    private final Reason reason;
    private final Instant createdAt;
    private final UUID supersedesId;
    private final List<RequestEvent> events = new ArrayList<>();

    private RequestStatus status;
    private String decidedBy;
    private Instant decidedAt;
    private String decisionComment;
    private DiscountBps approvedDiscount;

    private PricingExceptionRequest(UUID id, String applicationId, String requestedBy, DiscountBps discount,
                                    Reason reason, Instant createdAt, UUID supersedesId) {
        this.id = id;
        this.applicationId = applicationId;
        this.requestedBy = requestedBy;
        this.discount = discount;
        this.reason = reason;
        this.createdAt = createdAt;
        this.supersedesId = supersedesId;
        this.status = RequestStatus.PENDING;
    }

    /**
     * Creates a new PENDING request and its SUBMITTED event. Only an RM can submit.
     *
     * @param supersedesId the PENDING request this one replaces, or {@code null}
     */
    public static PricingExceptionRequest submit(UUID id, String applicationId, Actor requester,
                                                 DiscountBps discount, Reason reason, UUID supersedesId,
                                                 Clock clock) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(discount, "discount");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(clock, "clock");
        if (applicationId.isBlank()) {
            throw new IllegalArgumentException("applicationId must not be blank");
        }
        if (id.equals(supersedesId)) {
            throw new IllegalArgumentException("a request cannot supersede itself");
        }
        requireRole(requester, Role.RM, "submit");

        Instant now = clock.instant();
        PricingExceptionRequest request = new PricingExceptionRequest(
                id, applicationId, requester.id(), discount, reason, now, supersedesId);
        request.events.add(new RequestEvent(
                id, RequestEvent.Type.SUBMITTED, requester.id(), now, null, RequestStatus.PENDING, null));
        return request;
    }

    /**
     * Rebuilds a stored request without running commands or writing events. For the persistence
     * mapper only. Checks the cheap consistency rules: the history starts with SUBMITTED, and
     * {@code approvedDiscount} is set exactly when the status is APPROVED and then equals the
     * requested discount (INV-01).
     */
    public static PricingExceptionRequest reconstitute(UUID id, String applicationId, String requestedBy,
                                                       DiscountBps discount, Reason reason, Instant createdAt,
                                                       UUID supersedesId, RequestStatus status,
                                                       String decidedBy, Instant decidedAt, String decisionComment,
                                                       DiscountBps approvedDiscount, List<RequestEvent> events) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(discount, "discount");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(events, "events");
        checkHistory(id, status, events);
        if (id.equals(supersedesId)) {
            throw new IllegalArgumentException("request " + id + " cannot supersede itself");
        }
        if (status == RequestStatus.APPROVED) {
            if (!discount.equals(approvedDiscount)) {
                throw new IllegalArgumentException("APPROVED request " + id
                        + " must have approvedDiscount equal to its discount " + discount.value());
            }
        } else if (approvedDiscount != null) {
            throw new IllegalArgumentException("request " + id + " in status " + status
                    + " must not have an approvedDiscount");
        }
        boolean decided = status == RequestStatus.APPROVED || status == RequestStatus.DECLINED;
        if (decided && (decidedBy == null || decidedAt == null)) {
            throw new IllegalArgumentException("request " + id + " in status " + status
                    + " must have decidedBy and decidedAt");
        }
        if (!decided && (decidedBy != null || decidedAt != null || decisionComment != null)) {
            throw new IllegalArgumentException("request " + id + " in status " + status
                    + " must not have decision fields");
        }
        if (decided && decidedBy.equals(requestedBy)) {
            throw new IllegalArgumentException("request " + id + " was decided by its requester (INV-03)");
        }

        PricingExceptionRequest request = new PricingExceptionRequest(
                id, applicationId, requestedBy, discount, reason, createdAt, supersedesId);
        request.status = status;
        request.decidedBy = decidedBy;
        request.decidedAt = decidedAt;
        request.decisionComment = decisionComment;
        request.approvedDiscount = approvedDiscount;
        request.events.addAll(events);
        return request;
    }

    /**
     * The history this state machine can produce: one SUBMITTED event first, then at most one
     * transition out of PENDING whose target is the current status (INV-02, INV-05).
     */
    private static void checkHistory(UUID id, RequestStatus status, List<RequestEvent> events) {
        if (events.isEmpty() || events.get(0).type() != RequestEvent.Type.SUBMITTED) {
            throw new IllegalArgumentException("history of request " + id + " must start with a SUBMITTED event");
        }
        RequestEvent previous = null;
        for (int i = 0; i < events.size(); i++) {
            RequestEvent e = events.get(i);
            Objects.requireNonNull(e, "event");
            if (!id.equals(e.requestId())) {
                throw new IllegalArgumentException("event " + i + " belongs to request " + e.requestId()
                        + ", not " + id);
            }
            if (i > 0 && e.type() == RequestEvent.Type.SUBMITTED) {
                throw new IllegalArgumentException("history of request " + id + " has more than one SUBMITTED event");
            }
            if (i < events.size() - 1 && e.toStatus() != RequestStatus.PENDING) {
                throw new IllegalArgumentException("history of request " + id + " continues after a final status");
            }
            // No check on occurredAt order: a clock stepping backwards between two commands must not
            // make a stored request unreadable. Persistence orders events by insertion id.
            previous = e;
        }
        if (previous.toStatus() != status) {
            throw new IllegalArgumentException("history of request " + id + " ends in " + previous.toStatus()
                    + " but status is " + status);
        }
    }

    // ---------- commands ----------

    /** Reviewer approves. Stores the discount that was reviewed as {@code approvedDiscount} (INV-01). */
    public void approve(Actor reviewer, String comment, Clock clock) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(clock, "clock");
        requirePending("approve");
        requireRole(reviewer, Role.REVIEWER, "approve");
        requireNotRequester(reviewer);
        if (discount.value() > reviewer.approvalLimitBps()) {
            throw new ForbiddenActionException(ForbiddenActionException.OVER_APPROVAL_LIMIT,
                    "discount " + discount.value() + " bps is above " + reviewer.id()
                            + "'s approval limit of " + reviewer.approvalLimitBps() + " bps");
        }
        validateComment(comment);

        Instant now = clock.instant();
        decidedBy = reviewer.id();
        decidedAt = now;
        decisionComment = comment;
        approvedDiscount = discount;
        transition(RequestStatus.APPROVED, RequestEvent.Type.APPROVED, reviewer, now, comment);
    }

    /** Reviewer declines. No approval limit applies. */
    public void decline(Actor reviewer, String comment, Clock clock) {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(clock, "clock");
        requirePending("decline");
        requireRole(reviewer, Role.REVIEWER, "decline");
        requireNotRequester(reviewer);
        validateComment(comment);

        Instant now = clock.instant();
        decidedBy = reviewer.id();
        decidedAt = now;
        decisionComment = comment;
        transition(RequestStatus.DECLINED, RequestEvent.Type.DECLINED, reviewer, now, comment);
    }

    /** The owner RM withdraws a pending request. */
    public void withdraw(Actor actor, Clock clock) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(clock, "clock");
        requirePending("withdraw");
        requireOwner(actor, "withdraw");
        transition(RequestStatus.WITHDRAWN, RequestEvent.Type.WITHDRAWN, actor, clock.instant(), null);
    }

    /**
     * The owner RM replaces this pending request with a new one (INV-01). This only marks the old
     * request; the application service submits the new one in the same transaction.
     */
    public void markSuperseded(Actor actor, Clock clock) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(clock, "clock");
        requirePending("supersede");
        requireOwner(actor, "supersede");
        transition(RequestStatus.SUPERSEDED, RequestEvent.Type.SUPERSEDED, actor, clock.instant(), null);
    }

    // ---------- queries ----------

    /** The application id while the request is active (PENDING or APPROVED), else {@code null}. Backs INV-04. */
    public String activeApplicationId() {
        return status.isActive() ? applicationId : null;
    }

    public UUID id() {
        return id;
    }

    public String applicationId() {
        return applicationId;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public DiscountBps discount() {
        return discount;
    }

    public Reason reason() {
        return reason;
    }

    public RequestStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UUID supersedesId() {
        return supersedesId;
    }

    public String decidedBy() {
        return decidedBy;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public String decisionComment() {
        return decisionComment;
    }

    /** The discount the reviewer approved; {@code null} unless APPROVED. */
    public DiscountBps approvedDiscount() {
        return approvedDiscount;
    }

    /** History, oldest first. The first entry is always SUBMITTED. An immutable snapshot. */
    public List<RequestEvent> events() {
        return List.copyOf(events);
    }

    // ---------- checks ----------

    private void transition(RequestStatus to, RequestEvent.Type type, Actor actor, Instant at, String comment) {
        RequestStatus from = status;
        status = to;
        events.add(new RequestEvent(id, type, actor.id(), at, from, to, comment));
    }

    private void requirePending(String command) {
        if (status.isFinal()) {
            throw new InvalidTransitionException(status, command);
        }
    }

    private static void requireRole(Actor actor, Role required, String command) {
        if (actor.role() != required) {
            throw new ForbiddenActionException(ForbiddenActionException.WRONG_ROLE,
                    "only " + required + " can " + command + ", " + actor.id() + " is " + actor.role());
        }
    }

    private void requireNotRequester(Actor reviewer) {
        if (reviewer.id().equals(requestedBy)) {
            throw new ForbiddenActionException(ForbiddenActionException.SELF_REVIEW,
                    reviewer.id() + " cannot decide their own request");
        }
    }

    private void requireOwner(Actor actor, String command) {
        if (actor.role() != Role.RM) {
            throw new ForbiddenActionException(ForbiddenActionException.WRONG_ROLE,
                    "only the owner RM can " + command + ", " + actor.id() + " is " + actor.role());
        }
        if (!actor.id().equals(requestedBy)) {
            throw new ForbiddenActionException(ForbiddenActionException.NOT_OWNER,
                    actor.id() + " is not the owner of this request");
        }
    }

    private static void validateComment(String comment) {
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw new ValidationException(ValidationException.INVALID_COMMENT,
                    "comment must be at most " + MAX_COMMENT_LENGTH + " characters, was " + comment.length());
        }
    }
}
