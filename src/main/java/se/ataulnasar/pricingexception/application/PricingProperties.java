package se.ataulnasar.pricingexception.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Business configuration, prefix {@code pricing}. */
@ConfigurationProperties(prefix = "pricing")
public record PricingProperties(int maxDiscountBps, @DefaultValue Idempotency idempotency) {

    /** Idempotency-Key store settings. */
    public record Idempotency(@DefaultValue("PT24H") Duration ttl, @DefaultValue("PT1H") Duration purgeInterval) {
    }
}
