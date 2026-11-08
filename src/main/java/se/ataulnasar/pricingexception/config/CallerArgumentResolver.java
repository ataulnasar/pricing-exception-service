package se.ataulnasar.pricingexception.config;

import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import se.ataulnasar.pricingexception.exception.ApiProblem;
import se.ataulnasar.pricingexception.service.UserLookup;
import se.ataulnasar.pricingexception.model.Actor;

/**
 * Resolves the caller from the {@code X-User-Id} header for every controller method that takes an
 * {@link Actor}. Missing or unknown ids are 401; roles are checked by the service, not here.
 * Registers itself with Spring MVC.
 */
@Component
public class CallerArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    public static final String HEADER = "X-User-Id";

    private final UserLookup users;

    public CallerArgumentResolver(UserLookup users) {
        this.users = users;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String userId = webRequest.getHeader(HEADER);
        if (userId == null || userId.isBlank()) {
            throw ApiProblem.unauthenticated("missing " + HEADER + " header");
        }
        // No trimming: the id must match the user table exactly (HTTP already strips field whitespace).
        return users.findActor(userId)
                .orElseThrow(() -> ApiProblem.unauthenticated("unknown user"));
    }
}
