package se.ataulnasar.pricingexception.application;

import org.springframework.http.HttpStatus;

/** Kept only because tests still construct it; production code uses the {@link ApiProblem} factories. */
public class ConflictException extends ApiProblem {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
