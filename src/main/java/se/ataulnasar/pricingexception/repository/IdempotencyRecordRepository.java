package se.ataulnasar.pricingexception.repository;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import se.ataulnasar.pricingexception.model.IdempotencyRecordEntity;
import se.ataulnasar.pricingexception.model.IdempotencyRecordId;

/** Stored responses for Idempotency-Key replay. Rows live until the scheduled purge removes them. */
public interface IdempotencyRecordRepository extends Repository<IdempotencyRecordEntity, IdempotencyRecordId> {

    Optional<IdempotencyRecordEntity> findById(IdempotencyRecordId id);

    IdempotencyRecordEntity saveAndFlush(IdempotencyRecordEntity entity);

    long count();

    @Modifying
    @Query("delete from IdempotencyRecordEntity r where r.createdAt < :before")
    int deleteCreatedBefore(@Param("before") Instant before);
}
