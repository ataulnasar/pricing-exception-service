package se.ataulnasar.pricingexception.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.ataulnasar.pricingexception.config.PricingProperties;
import se.ataulnasar.pricingexception.dto.ApprovedDiscount;
import se.ataulnasar.pricingexception.dto.Decision;
import se.ataulnasar.pricingexception.dto.PricingExceptionResponse;
import se.ataulnasar.pricingexception.exception.ApiProblem;
import se.ataulnasar.pricingexception.exception.ForbiddenActionException;
import se.ataulnasar.pricingexception.exception.InvalidTransitionException;
import se.ataulnasar.pricingexception.exception.ValidationException;
import se.ataulnasar.pricingexception.model.Actor;
import se.ataulnasar.pricingexception.model.ApplicationStatus;
import se.ataulnasar.pricingexception.model.EventType;
import se.ataulnasar.pricingexception.model.MortgageApplicationEntity;
import se.ataulnasar.pricingexception.model.PricingExceptionRequestEntity;
import se.ataulnasar.pricingexception.model.RequestEventEntity;
import se.ataulnasar.pricingexception.model.RequestStatus;
import se.ataulnasar.pricingexception.model.Role;
import se.ataulnasar.pricingexception.repository.MortgageApplicationRepository;
import se.ataulnasar.pricingexception.repository.PricingExceptionRequestRepository;

/**
 * All business rules for pricing exception requests (docs/invariants.yaml), one use case per method,
 * one transaction per command. The service works directly on the JPA entities.
 *
 * <ul>
 *   <li>INV-01: discount and reason never change after submit; an approval stores the discount it saw.</li>
 *   <li>INV-02: every status change appends exactly one {@link RequestEventEntity}, flushed in the same transaction.</li>
 *   <li>INV-03: the requester never decides their own request; approvals are limited by the reviewer's limit.</li>
 *   <li>INV-04: {@code activeApplicationId} is set while PENDING or APPROVED, else null (unique index).</li>
 *   <li>INV-05: only PENDING can change; the caller's If-Match version must equal the stored version.</li>
 *   <li>INV-06: the application must be OPEN to create or approve.</li>
 * </ul>
 *
 * <p>Checks on a decision run in this order: status, role, self-review, approval limit, comment length,
 * then INV-06 (docs/design.md "Check order"). All checks run before any state is touched, so a rejected
 * command leaves the row exactly as it was.
 */
@Service
public class PricingExceptionService {

    public static final int MAX_REASON_LENGTH = 500;
    public static final int MAX_COMMENT_LENGTH = 500;

    private final PricingExceptionRequestRepository requests;
    private final MortgageApplicationRepository applications;
    private final PricingProperties properties;
    private final Clock clock;
    private final IdempotencyService idempotency;

    public PricingExceptionService(PricingExceptionRequestRepository requests,
                                   MortgageApplicationRepository applications,
                                   PricingProperties properties,
                                   Clock clock,
                                   IdempotencyService idempotency) {
        this.requests = requests;
        this.applications = applications;
        this.properties = properties;
        this.clock = clock;
        this.idempotency = idempotency;
    }

    // ---------- create ----------

    /** RM submits a request. Input and role are checked first, then INV-06 and INV-04. */
    @Transactional
    public PricingExceptionResponse create(Actor caller, String applicationId, int discountBps, String reason) {
        requireValidDiscount(discountBps);
        requireValidReason(reason);
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("applicationId must not be blank");
        }
        requireRole(caller, Role.RM, "submit");

        requireOpen(lockApplication(applicationId));
        requests.findByActiveApplicationId(applicationId).ifPresent(active -> {
            throw ApiProblem.activeRequestExists(applicationId, active.getId());
        });

        PricingExceptionRequestEntity saved = requests.saveAndFlush(newRequest(applicationId, caller, discountBps, reason, null));
        return PricingExceptionResponse.from(saved, null);
    }

    /**
     * {@link #create} under an Idempotency-Key: the first call runs the create and stores the rendered
     * response in the same transaction; later calls with the same body replay it. Not transactional
     * itself: {@link IdempotencyTransactions} owns the transaction and this method's own
     * {@code create} joins it.
     */
    public IdempotentResult createIdempotently(Actor caller, String key, CreateRequestCommand command,
                                               Function<PricingExceptionResponse, String> renderBody) {
        return idempotency.execute(caller.id(), key, command, 201,
                () -> create(caller, command.applicationId(), command.discountBps(), command.reason()),
                renderBody);
    }

    // ---------- read ----------

    @Transactional(readOnly = true)
    public PricingExceptionResponse get(UUID id) {
        return toResponse(load(id));
    }

    /** Newest first. Both filters are optional. */
    @Transactional(readOnly = true)
    public List<PricingExceptionResponse> list(RequestStatus status, String applicationId) {
        List<PricingExceptionRequestEntity> found;
        if (status != null && applicationId != null) {
            found = requests.findByApplicationIdAndStatus(applicationId, status);
        } else if (status != null) {
            found = requests.findByStatus(status);
        } else if (applicationId != null) {
            found = requests.findByApplicationId(applicationId);
        } else {
            found = requests.findAll();
        }
        return found.stream()
                .sorted(Comparator.comparing(PricingExceptionRequestEntity::getCreatedAt)
                        .thenComparing(PricingExceptionRequestEntity::getId)
                        .reversed())
                .map(this::toResponse)
                .toList();
    }

    /** What the mortgage process reads (design decision 5). Only MORTGAGE_PROCESS may call it. */
    @Transactional(readOnly = true)
    public ApprovedDiscount approvedDiscount(Actor caller, String applicationId) {
        if (caller.role() != Role.MORTGAGE_PROCESS) {
            throw new ForbiddenActionException(ForbiddenActionException.WRONG_ROLE,
                    "only MORTGAGE_PROCESS can read the approved discount, " + caller.id() + " is " + caller.role());
        }
        loadApplication(applicationId);
        PricingExceptionRequestEntity approved = requests.findByApplicationIdAndStatus(applicationId, RequestStatus.APPROVED)
                .stream().findFirst()
                .orElseThrow(() -> ApiProblem.noApprovedDiscount(applicationId));
        return new ApprovedDiscount(applicationId, approved.getApprovedDiscountBps(), approved.getId(),
                approved.getDecidedBy(), approved.getDecidedAt());
    }

    // ---------- decide ----------

    /** Reviewer approves or declines. decidedBy is the caller, decidedAt is the clock, never the body. */
    @Transactional
    public PricingExceptionResponse decide(Actor caller, UUID id, long expectedVersion, Decision decision, String comment) {
        PricingExceptionRequestEntity entity = load(id);
        requireVersion(entity, expectedVersion);

        if (decision == Decision.APPROVED) {
            requirePending(entity, "approve");
            requireRole(caller, Role.REVIEWER, "approve");
            requireNotRequester(entity, caller);
            if (entity.getDiscountBps() > caller.approvalLimitBps()) {
                throw new ForbiddenActionException(ForbiddenActionException.OVER_APPROVAL_LIMIT,
                        "discount " + entity.getDiscountBps() + " bps is above " + caller.id()
                                + "'s approval limit of " + caller.approvalLimitBps() + " bps");
            }
            requireValidComment(comment);
            requireOpen(lockApplication(entity.getApplicationId())); // INV-06, after the business checks
            entity.setApprovedDiscountBps(entity.getDiscountBps());  // INV-01: the discount the reviewer saw
            recordDecision(entity, RequestStatus.APPROVED, EventType.APPROVED, caller, comment);
        } else {
            requirePending(entity, "decline");
            requireRole(caller, Role.REVIEWER, "decline");
            requireNotRequester(entity, caller);
            requireValidComment(comment);
            recordDecision(entity, RequestStatus.DECLINED, EventType.DECLINED, caller, comment);
        }
        return PricingExceptionResponse.from(requests.saveAndFlush(entity), null);
    }

    // ---------- withdraw ----------

    /** The owner RM withdraws a pending request. */
    @Transactional
    public PricingExceptionResponse withdraw(Actor caller, UUID id, long expectedVersion) {
        PricingExceptionRequestEntity entity = load(id);
        requireVersion(entity, expectedVersion);
        requirePending(entity, "withdraw");
        requireOwner(entity, caller, "withdraw");
        transition(entity, RequestStatus.WITHDRAWN, EventType.WITHDRAWN, caller, clock.instant(), null);
        return PricingExceptionResponse.from(requests.saveAndFlush(entity), null);
    }

    // ---------- supersede ----------

    /**
     * The owner RM replaces a PENDING request with new terms (INV-01). The old request is marked
     * SUPERSEDED and flushed first, which frees the application's active slot, then the new request
     * is inserted with {@code supersedesId} pointing at the old one; both in one transaction. The new
     * terms are validated before anything changes, and INV-06 applies to the new request.
     */
    @Transactional
    public PricingExceptionResponse supersede(Actor caller, UUID oldId, long expectedVersion, int discountBps, String reason) {
        requireValidDiscount(discountBps);
        requireValidReason(reason);

        PricingExceptionRequestEntity old = load(oldId);
        requireVersion(old, expectedVersion);
        requirePending(old, "supersede");
        requireOwner(old, caller, "supersede");
        requireOpen(lockApplication(old.getApplicationId()));

        transition(old, RequestStatus.SUPERSEDED, EventType.SUPERSEDED, caller, clock.instant(), null);
        requests.saveAndFlush(old); // active_application_id is now NULL; the unique index is free

        PricingExceptionRequestEntity replacement = newRequest(old.getApplicationId(), caller, discountBps, reason, old.getId());
        return PricingExceptionResponse.from(requests.saveAndFlush(replacement), null);
    }

    /** {@link #supersede} under an Idempotency-Key; see {@link #createIdempotently}. */
    public IdempotentResult supersedeIdempotently(Actor caller, String key, SupersedeCommand command,
                                                  Function<PricingExceptionResponse, String> renderBody) {
        return idempotency.execute(caller.id(), key, command, 201,
                () -> supersede(caller, command.requestId(), command.expectedVersion(),
                        command.discountBps(), command.reason()),
                renderBody);
    }

    // ---------- state changes (INV-02, INV-04) ----------

    /** A new PENDING request with its SUBMITTED event. {@code supersedesId} is the request it replaces, or null. */
    private PricingExceptionRequestEntity newRequest(String applicationId, Actor requester, int discountBps,
                                                     String reason, UUID supersedesId) {
        Instant now = clock.instant();
        PricingExceptionRequestEntity entity = new PricingExceptionRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setApplicationId(applicationId);
        entity.setRequestedBy(requester.id());
        entity.setDiscountBps(discountBps);
        entity.setReason(reason);
        entity.setSupersedesId(supersedesId);
        entity.setCreatedAt(now);
        entity.setStatus(RequestStatus.PENDING);
        entity.setActiveApplicationId(applicationId);
        entity.getEvents().add(new RequestEventEntity(EventType.SUBMITTED, requester.id(), now, null, RequestStatus.PENDING, null));
        return entity;
    }

    private void recordDecision(PricingExceptionRequestEntity entity, RequestStatus to, EventType type,
                                Actor reviewer, String comment) {
        Instant now = clock.instant();
        entity.setDecidedBy(reviewer.id());
        entity.setDecidedAt(now);
        entity.setDecisionComment(comment);
        transition(entity, to, type, reviewer, now, comment);
    }

    /** The one place a status changes: sets the status and the active slot, and appends exactly one event. */
    private static void transition(PricingExceptionRequestEntity entity, RequestStatus to, EventType type,
                                   Actor actor, Instant at, String comment) {
        RequestStatus from = entity.getStatus();
        entity.setStatus(to);
        entity.setActiveApplicationId(to.isActive() ? entity.getApplicationId() : null);
        entity.getEvents().add(new RequestEventEntity(type, actor.id(), at, from, to, comment));
    }

    // ---------- checks ----------

    private static void requirePending(PricingExceptionRequestEntity entity, String command) {
        if (entity.getStatus().isFinal()) {
            throw new InvalidTransitionException(entity.getStatus(), command);
        }
    }

    private static void requireRole(Actor actor, Role required, String command) {
        if (actor.role() != required) {
            throw new ForbiddenActionException(ForbiddenActionException.WRONG_ROLE,
                    "only " + required + " can " + command + ", " + actor.id() + " is " + actor.role());
        }
    }

    private static void requireNotRequester(PricingExceptionRequestEntity entity, Actor reviewer) {
        if (reviewer.id().equals(entity.getRequestedBy())) {
            throw new ForbiddenActionException(ForbiddenActionException.SELF_REVIEW,
                    reviewer.id() + " cannot decide their own request");
        }
    }

    private static void requireOwner(PricingExceptionRequestEntity entity, Actor actor, String command) {
        if (actor.role() != Role.RM) {
            throw new ForbiddenActionException(ForbiddenActionException.WRONG_ROLE,
                    "only the owner RM can " + command + ", " + actor.id() + " is " + actor.role());
        }
        if (!actor.id().equals(entity.getRequestedBy())) {
            throw new ForbiddenActionException(ForbiddenActionException.NOT_OWNER,
                    actor.id() + " is not the owner of this request");
        }
    }

    private static void requireVersion(PricingExceptionRequestEntity entity, long expectedVersion) {
        if (entity.getVersion() != expectedVersion) {
            throw ApiProblem.versionMismatch(expectedVersion, entity.getVersion());
        }
    }

    private static void requireOpen(MortgageApplicationEntity application) {
        if (application.getStatus() != ApplicationStatus.OPEN) {
            throw ApiProblem.applicationNotOpen(application.getId(), application.getStatus());
        }
    }

    /** A discount is a whole number of basis points from 1 to {@code pricing.max-discount-bps}. */
    private void requireValidDiscount(int discountBps) {
        int max = properties.maxDiscountBps();
        if (discountBps < 1 || discountBps > max) {
            throw new ValidationException(ValidationException.INVALID_DISCOUNT,
                    "discountBps must be between 1 and " + max + ", was " + discountBps);
        }
    }

    /** Not blank (non-breaking spaces and invisible format characters count as blank), at most 500 characters. */
    private static void requireValidReason(String reason) {
        if (reason == null || isBlank(reason)) {
            throw new ValidationException(ValidationException.INVALID_REASON, "reason must not be blank");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new ValidationException(ValidationException.INVALID_REASON,
                    "reason must be at most " + MAX_REASON_LENGTH + " characters, was " + reason.length());
        }
    }

    private static void requireValidComment(String comment) {
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw new ValidationException(ValidationException.INVALID_COMMENT,
                    "comment must be at most " + MAX_COMMENT_LENGTH + " characters, was " + comment.length());
        }
    }

    private static boolean isBlank(String s) {
        return s.codePoints().allMatch(cp -> Character.isWhitespace(cp)
                || Character.isSpaceChar(cp)
                || Character.getType(cp) == Character.FORMAT);
    }

    // ---------- loading ----------

    private PricingExceptionResponse toResponse(PricingExceptionRequestEntity entity) {
        UUID supersededById = entity.getStatus() == RequestStatus.SUPERSEDED
                ? requests.findBySupersedesId(entity.getId()).map(PricingExceptionRequestEntity::getId).orElse(null)
                : null;
        return PricingExceptionResponse.from(entity, supersededById);
    }

    private PricingExceptionRequestEntity load(UUID id) {
        return requests.findById(id).orElseThrow(() -> ApiProblem.requestNotFound(id));
    }

    private MortgageApplicationEntity loadApplication(String applicationId) {
        return applications.findById(applicationId).orElseThrow(() -> ApiProblem.applicationNotFound(applicationId));
    }

    /** The application row locked until this transaction ends, so a concurrent close cannot slip between the INV-06 check and the commit (ADR-008). */
    private MortgageApplicationEntity lockApplication(String applicationId) {
        return applications.findForUpdateById(applicationId).orElseThrow(() -> ApiProblem.applicationNotFound(applicationId));
    }
}
