package se.ataulnasar.pricingexception.application;

import org.springframework.http.HttpStatus;

/** Kept only because tests still construct it; production code uses the {@link ApiProblem} factories. */
public class NotFoundException extends ApiProblem {

    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
