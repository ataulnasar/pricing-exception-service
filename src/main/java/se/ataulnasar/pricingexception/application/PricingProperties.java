package se.ataulnasar.pricingexception.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Business configuration, prefix {@code pricing}. */
@ConfigurationProperties(prefix = "pricing")
public record PricingProperties(int maxDiscountBps) {
}
