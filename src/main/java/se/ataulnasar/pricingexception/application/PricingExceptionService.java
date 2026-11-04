package se.ataulnasar.pricingexception.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.ataulnasar.pricingexception.domain.Actor;
import se.ataulnasar.pricingexception.domain.DiscountBps;
import se.ataulnasar.pricingexception.domain.PricingExceptionRequest;
import se.ataulnasar.pricingexception.domain.Reason;
import se.ataulnasar.pricingexception.domain.RequestStatus;
import se.ataulnasar.pricingexception.persistence.ApplicationStatus;
import se.ataulnasar.pricingexception.persistence.MortgageApplicationEntity;
import se.ataulnasar.pricingexception.persistence.MortgageApplicationRepository;
import se.ataulnasar.pricingexception.persistence.PricingExceptionRequestEntity;
import se.ataulnasar.pricingexception.persistence.PricingExceptionRequestMapper;
import se.ataulnasar.pricingexception.persistence.PricingExceptionRequestRepository;

/**
 * One use case per method, one transaction per command. Each command loads the row, checks the
 * caller's If-Match version against the stored version (INV-05), runs the domain command, copies
 * the result back and flushes, so the request and its new event are written together (INV-02).
 * Entities are mapped to the domain inside the transaction only.
 */
@Service
public class PricingExceptionService {

    private final PricingExceptionRequestRepository requests;
    private final MortgageApplicationRepository applications;
    private final PricingExceptionRequestMapper mapper;
    private final PricingProperties properties;
    private final Clock clock;

    public PricingExceptionService(PricingExceptionRequestRepository requests,
                                   MortgageApplicationRepository applications,
                                   PricingExceptionRequestMapper mapper,
                                   PricingProperties properties,
                                   Clock clock) {
        this.requests = requests;
        this.applications = applications;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** RM submits a request. Role and input are checked by the domain first, then INV-06 and INV-04. */
    @Transactional
    public RequestView create(Actor caller, String applicationId, int discountBps, String reason) {
        PricingExceptionRequest request = PricingExceptionRequest.submit(
                UUID.randomUUID(), applicationId, caller,
                DiscountBps.of(discountBps, properties.maxDiscountBps()), new Reason(reason), null, clock);

        requireOpen(loadApplication(applicationId));
        requests.findByActiveApplicationId(applicationId).ifPresent(active -> {
            throw new ConflictException(ConflictException.ACTIVE_REQUEST_EXISTS,
                    "application " + applicationId + " already has an active request " + active.getId());
        });

        PricingExceptionRequestEntity saved = requests.saveAndFlush(mapper.toNewEntity(request));
        return new RequestView(request, saved.getVersion());
    }

    @Transactional(readOnly = true)
    public RequestView get(UUID id) {
        return view(load(id));
    }

    /** Newest first. Both filters are optional. */
    @Transactional(readOnly = true)
    public List<RequestView> list(RequestStatus status, String applicationId) {
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
                .map(this::view)
                .toList();
    }

    /** Reviewer approves or declines. decidedBy is the caller, decidedAt is the clock, never the body. */
    @Transactional
    public RequestView decide(Actor caller, UUID id, long expectedVersion, Decision decision, String comment) {
        PricingExceptionRequestEntity entity = load(id);
        requireVersion(entity, expectedVersion);
        PricingExceptionRequest request = mapper.toDomain(entity);

        if (decision == Decision.APPROVED) {
            request.approve(caller, comment, clock);
            requireOpen(loadApplication(entity.getApplicationId())); // INV-06, after the domain checks
        } else {
            request.decline(caller, comment, clock);
        }
        return persist(request, entity);
    }

    /** The owner RM withdraws a pending request. */
    @Transactional
    public RequestView withdraw(Actor caller, UUID id, long expectedVersion) {
        PricingExceptionRequestEntity entity = load(id);
        requireVersion(entity, expectedVersion);
        PricingExceptionRequest request = mapper.toDomain(entity);
        request.withdraw(caller, clock);
        return persist(request, entity);
    }

    // ---------- helpers ----------

    private RequestView persist(PricingExceptionRequest request, PricingExceptionRequestEntity entity) {
        mapper.applyTo(request, entity);
        PricingExceptionRequestEntity saved = requests.saveAndFlush(entity);
        return new RequestView(request, saved.getVersion());
    }

    private RequestView view(PricingExceptionRequestEntity entity) {
        return new RequestView(mapper.toDomain(entity), entity.getVersion());
    }

    private PricingExceptionRequestEntity load(UUID id) {
        return requests.findById(id).orElseThrow(() -> new NotFoundException(
                NotFoundException.REQUEST_NOT_FOUND, "pricing exception request " + id + " not found"));
    }

    private MortgageApplicationEntity loadApplication(String applicationId) {
        return applications.findById(applicationId).orElseThrow(() -> new NotFoundException(
                NotFoundException.APPLICATION_NOT_FOUND, "mortgage application " + applicationId + " not found"));
    }

    private static void requireOpen(MortgageApplicationEntity application) {
        if (application.getStatus() != ApplicationStatus.OPEN) {
            throw new ConflictException(ConflictException.APPLICATION_NOT_OPEN,
                    "mortgage application " + application.getId() + " is " + application.getStatus());
        }
    }

    private static void requireVersion(PricingExceptionRequestEntity entity, long expectedVersion) {
        if (entity.getVersion() != expectedVersion) {
            throw new VersionMismatchException(expectedVersion, entity.getVersion());
        }
    }
}
