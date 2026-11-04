package se.ataulnasar.pricingexception.api;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import se.ataulnasar.pricingexception.application.ConflictException;
import se.ataulnasar.pricingexception.application.IdempotencyInProgressException;
import se.ataulnasar.pricingexception.application.IdempotencyKeyReusedException;
import se.ataulnasar.pricingexception.application.NotFoundException;
import se.ataulnasar.pricingexception.application.VersionMismatchException;
import se.ataulnasar.pricingexception.domain.DomainException;
import se.ataulnasar.pricingexception.domain.ForbiddenActionException;
import se.ataulnasar.pricingexception.domain.InvalidTransitionException;
import se.ataulnasar.pricingexception.domain.ValidationException;

/**
 * Maps every error to an RFC 9457 ProblemDetail with a stable {@code code} property
 * (table in docs/design.md "API error codes"). {@code detail} is plain text, never HTML.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";
    public static final String DATA_CONFLICT = "DATA_CONFLICT";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ProblemDetail unauthenticated(UnauthenticatedException e) {
        return problem(HttpStatus.UNAUTHORIZED, e);
    }

    @ExceptionHandler(ForbiddenActionException.class)
    ProblemDetail forbidden(ForbiddenActionException e) {
        return problem(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler({ConflictException.class, InvalidTransitionException.class, IdempotencyInProgressException.class})
    ProblemDetail conflict(DomainException e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(VersionMismatchException.class)
    ProblemDetail versionMismatch(VersionMismatchException e) {
        return problem(HttpStatus.PRECONDITION_FAILED, e);
    }

    @ExceptionHandler(IfMatchRequiredException.class)
    ProblemDetail ifMatchRequired(IfMatchRequiredException e) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, e);
    }

    @ExceptionHandler({ValidationException.class, IdempotencyKeyReusedException.class})
    ProblemDetail domainValidation(DomainException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e);
    }

    @ExceptionHandler(MalformedRequestException.class)
    ProblemDetail malformed(MalformedRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail beanValidation(MethodArgumentNotValidException e) {
        List<FieldViolation> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldViolation(f.getField(), message(f)))
                .toList();
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, VALIDATION_FAILED,
                "request body is not valid");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, MalformedRequestException.CODE, "request could not be read");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail optimisticLock(OptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, CONCURRENT_MODIFICATION,
                "the request was changed by someone else; reload it and try again");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataIntegrity(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("ux_per_active_application")) {
            return problem(HttpStatus.CONFLICT, ConflictException.ACTIVE_REQUEST_EXISTS,
                    "the application already has an active request");
        }
        log.warn("data integrity violation", e);
        return problem(HttpStatus.CONFLICT, DATA_CONFLICT, "the change conflicts with stored data");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        if (e instanceof ErrorResponse spring) {
            // Spring's own errors (unknown route, wrong method, missing parameter...) keep their
            // status and headers (Allow on 405, Accept on 415) and get a code HTTP_<status>.
            ProblemDetail problem = spring.getBody();
            problem.setProperty("code", "HTTP_" + problem.getStatus());
            return ResponseEntity.status(problem.getStatus()).headers(spring.getHeaders()).body(problem);
        }
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, "an unexpected error occurred"));
    }

    private static ProblemDetail problem(HttpStatus status, DomainException e) {
        return problem(status, e.code(), e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }

    private static String message(FieldError f) {
        return f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage();
    }
}
