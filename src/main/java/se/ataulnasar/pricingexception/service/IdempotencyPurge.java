package se.ataulnasar.pricingexception.service;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import se.ataulnasar.pricingexception.repository.IdempotencyRecordRepository;
import se.ataulnasar.pricingexception.config.PricingProperties;

/** Removes idempotency records older than {@code pricing.idempotency.ttl} (default 24h). */
@Component
public class IdempotencyPurge {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurge.class);

    private final IdempotencyRecordRepository records;
    private final PricingProperties properties;
    private final Clock clock;

    public IdempotencyPurge(IdempotencyRecordRepository records, PricingProperties properties, Clock clock) {
        this.records = records;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pricing.idempotency.purge-interval:PT1H}",
               initialDelayString = "${pricing.idempotency.purge-interval:PT1H}")
    @Transactional
    public int purgeExpired() {
        Instant before = clock.instant().minus(properties.idempotency().ttl());
        int removed = records.deleteCreatedBefore(before);
        if (removed > 0) {
            log.info("purged {} idempotency record(s) created before {}", removed, before);
        }
        return removed;
    }
}
