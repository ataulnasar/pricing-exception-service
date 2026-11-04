package se.ataulnasar.pricingexception;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import se.ataulnasar.pricingexception.application.PricingProperties;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration
@EnableConfigurationProperties(PricingProperties.class)
public class AppConfig {

    /**
     * The one source of time. Ticks in microseconds so an instant in memory always equals the
     * same instant after a round trip through TIMESTAMP WITH TIME ZONE (microsecond precision).
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.of(1, ChronoUnit.MICROS));
    }

    /**
     * Strict JSON numbers: a JSON string such as {@code "40"} is not accepted where an integer is
     * expected (together with {@code accept-float-as-int=false} in application.properties).
     */
    @Bean
    public JsonMapperBuilderCustomizer strictIntegers() {
        return builder -> builder.withCoercionConfig(LogicalType.Integer,
                config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail));
    }
}
