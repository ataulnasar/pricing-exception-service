package se.ataulnasar.pricingexception.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import se.ataulnasar.pricingexception.persistence.IdempotencyRecordEntity;
import se.ataulnasar.pricingexception.persistence.IdempotencyRecordId;
import se.ataulnasar.pricingexception.persistence.IdempotencyRecordRepository;

/**
 * Design "Idempotency-Key ... Records are purged after pricing.idempotency.ttl (default 24h)".
 * Uses its OWN in-memory database (own datasource URL) and a fixed Clock, so the record counts are
 * exact and no other test can add or remove idempotency records.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:purge;DB_CLOSE_DELAY=-1")
class IdempotencyPurgeTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired IdempotencyPurge purge;
    @Autowired IdempotencyRecordRepository records;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void emptyTable() {
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void insert(String key, Duration age) {
        records.saveAndFlush(new IdempotencyRecordEntity(new IdempotencyRecordId("rm-anna", key),
                "hash-" + key, 201, "{}", NOW.minus(age)));
    }

    @Test
    void idempotency_records_older_than_ttl_are_purged() {
        insert("old-25h", Duration.ofHours(25));
        insert("young-23h", Duration.ofHours(23));
        insert("fresh-1min", Duration.ofMinutes(1));

        int deleted = purge.purgeExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(records.count()).isEqualTo(2);
        assertThat(records.findById(new IdempotencyRecordId("rm-anna", "old-25h"))).isEmpty();
        assertThat(records.findById(new IdempotencyRecordId("rm-anna", "young-23h"))).isPresent();
        assertThat(records.findById(new IdempotencyRecordId("rm-anna", "fresh-1min"))).isPresent();
        assertThat(purge.purgeExpired()).isZero();
        assertThat(records.count()).isEqualTo(2);
    }

    @Test
    void idempotency_purge_on_empty_table_returns_zero() {
        assertThat(purge.purgeExpired()).isZero();
    }

    @Test
    void idempotency_purge_ttl_is_24h_by_default_so_a_record_just_inside_it_stays() {
        insert("just-inside", Duration.ofHours(24).minusSeconds(1));

        assertThat(purge.purgeExpired()).isZero();
        assertThat(records.count()).isEqualTo(1);
    }
}
