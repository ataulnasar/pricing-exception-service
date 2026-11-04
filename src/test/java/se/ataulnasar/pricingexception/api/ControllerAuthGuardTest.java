package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import se.ataulnasar.pricingexception.domain.Actor;

/** Identity is enforced by the Actor argument resolver, so a handler without an Actor parameter is unauthenticated. */
class ControllerAuthGuardTest {

    @Test
    void api_every_controller_handler_takes_the_caller() {
        List<Method> handlers = Arrays.stream(PricingExceptionController.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();
        assertThat(handlers).as("handlers found").isNotEmpty();
        for (Method m : handlers) {
            assertThat(Arrays.stream(m.getParameterTypes()))
                    .as("handler %s must take an Actor parameter", m.getName())
                    .contains(Actor.class);
        }
    }
}
