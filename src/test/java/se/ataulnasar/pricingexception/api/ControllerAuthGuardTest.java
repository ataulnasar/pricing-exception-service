package se.ataulnasar.pricingexception.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.ataulnasar.pricingexception.domain.Actor;

/** Identity is enforced by the Actor argument resolver, so a handler without an Actor parameter is unauthenticated. */
class ControllerAuthGuardTest {

    @Test
    void api_every_controller_handler_in_the_api_package_takes_the_caller() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("se.ataulnasar.pricingexception.api")) {
            controllers.add(Class.forName(bd.getBeanClassName()));
        }
        assertThat(controllers).as("scanned controllers")
                .isNotEmpty()
                .contains(PricingExceptionController.class, ApplicationController.class);

        for (Class<?> controller : controllers) {
            List<Method> handlers = Arrays.stream(controller.getDeclaredMethods())
                    .filter(m -> Modifier.isPublic(m.getModifiers()))
                    .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                    .toList();
            assertThat(handlers).as("handlers found in %s", controller.getSimpleName()).isNotEmpty();
            for (Method m : handlers) {
                assertThat(Arrays.stream(m.getParameterTypes()))
                        .as("handler %s.%s must take an Actor parameter", controller.getSimpleName(), m.getName())
                        .contains(Actor.class);
            }
        }
    }
}
