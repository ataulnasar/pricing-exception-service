package se.ataulnasar.pricingexception.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import se.ataulnasar.pricingexception.domain.RequestStatus;

/**
 * Requests and their history. Deliberately not a {@code JpaRepository}: requests are never deleted
 * and {@code request_event} is insert only (INV-02), so no delete method is exposed. Every finder
 * loads the events with the request (one query, no lazy loading outside a transaction).
 */
public interface PricingExceptionRequestRepository extends Repository<PricingExceptionRequestEntity, UUID> {

    PricingExceptionRequestEntity saveAndFlush(PricingExceptionRequestEntity entity);

    void flush();

    @EntityGraph(attributePaths = "events")
    Optional<PricingExceptionRequestEntity> findById(UUID id);

    @EntityGraph(attributePaths = "events")
    List<PricingExceptionRequestEntity> findAll();

    long count();

    @EntityGraph(attributePaths = "events")
    List<PricingExceptionRequestEntity> findByApplicationId(String applicationId);

    @EntityGraph(attributePaths = "events")
    List<PricingExceptionRequestEntity> findByStatus(RequestStatus status);

    @EntityGraph(attributePaths = "events")
    List<PricingExceptionRequestEntity> findByApplicationIdAndStatus(String applicationId, RequestStatus status);

    /** The request that replaced the given one, if any (the reverse of supersedes_id). */
    @EntityGraph(attributePaths = "events")
    Optional<PricingExceptionRequestEntity> findBySupersedesId(UUID supersedesId);

    /**
     * The active (PENDING or APPROVED) request of an application, if any. At most one row per
     * application has a non-null active_application_id (INV-04). Explicit query so that a null
     * argument matches nothing instead of becoming {@code IS NULL}.
     */
    @EntityGraph(attributePaths = "events")
    @Query("select r from PricingExceptionRequestEntity r where r.activeApplicationId = :applicationId")
    Optional<PricingExceptionRequestEntity> findByActiveApplicationId(String applicationId);
}
