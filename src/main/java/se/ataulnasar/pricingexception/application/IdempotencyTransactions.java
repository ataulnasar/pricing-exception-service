package se.ataulnasar.pricingexception.application;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import se.ataulnasar.pricingexception.persistence.IdempotencyRecordEntity;
import se.ataulnasar.pricingexception.persistence.IdempotencyRecordId;
import se.ataulnasar.pricingexception.persistence.IdempotencyRecordRepository;

/**
 * The transactional steps of a keyed create, on their own bean so the proxies apply.
 *
 * <p>{@link #claimAndRun} first inserts the record with status 0 and flushes: that claims the
 * (caller, key) primary key. A concurrent caller with the same key blocks on that key until this
 * transaction ends and then gets a duplicate-key error, which {@link IdempotencyService} turns into
 * a re-read and replay. Then the create runs in the same transaction, and the record is completed
 * with the rendered response. If the create fails, everything rolls back, including the claim.
 */
@Component
public class IdempotencyTransactions {

    private final IdempotencyRecordRepository records;
    private final Clock clock;

    public IdempotencyTransactions(IdempotencyRecordRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecordEntity> find(String callerId, String key) {
        return records.findById(new IdempotencyRecordId(callerId, key));
    }

    @Transactional
    public IdempotentResult claimAndRun(String callerId, String key, String requestHash, int successStatus,
                                        Supplier<RequestView> action,
                                        Function<RequestView, String> renderBody) {
        IdempotencyRecordEntity record = records.saveAndFlush(new IdempotencyRecordEntity(
                new IdempotencyRecordId(callerId, key), requestHash,
                IdempotencyRecordEntity.IN_PROGRESS, "", clock.instant()));

        RequestView view = action.get();
        String body = renderBody.apply(view);
        record.complete(successStatus, body);
        records.saveAndFlush(record);
        return new IdempotentResult(successStatus, body, view.request().id(), view.version(), false);
    }
}
