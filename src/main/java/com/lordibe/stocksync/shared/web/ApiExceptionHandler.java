package com.lordibe.stocksync.shared.web;

import com.lordibe.stocksync.iam.service.TooManyLoginAttemptsException;
import com.lordibe.stocksync.inventory.domain.InsufficientStockException;
import com.lordibe.stocksync.inventory.domain.InvalidMovementException;
import com.lordibe.stocksync.shared.tenancy.MissingTenantContextException;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns domain failures into RFC 9457 problem responses.
 *
 * <p>Each mapping is a deliberate choice of status: a refused sale is a conflict
 * with the current state (409), not a malformed request; a contradictory movement
 * is the client's fault (400); a missing tenant binding is our bug (500) and is the
 * only one logged at error level with a stack trace.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail onBadCredentials(BadCredentialsException exception) {
        // Deliberately vague: the service does not reveal which part was wrong.
        return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    ProblemDetail onTooManyAttempts(TooManyLoginAttemptsException exception) {
        ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS,
                "Too many failed login attempts. Try again later.");
        problem.setTitle("Too many attempts");
        problem.setProperty("retryAfterSeconds", exception.getRetryAfter().toSeconds());
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail onAccessDenied(AccessDeniedException exception) {
        // 403, not 404: the caller is authenticated, they simply lack the role.
        return problem(HttpStatus.FORBIDDEN, "You do not have permission to do that");
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail onInsufficientStock(InsufficientStockException exception) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Insufficient stock");
        problem.setProperty("itemId", exception.getItemId());
        problem.setProperty("locationId", exception.getLocationId());
        // The ids and numbers live here, as structured properties, rather than in
        // the sentence: a client that wants to highlight a field or re-fetch a
        // level can read them, and the person reading `detail` is not shown a
        // UUID. Named "available" rather than "onHand" because for a refused
        // reservation it is what was free, which is not the same number.
        problem.setProperty("available", exception.getAvailable());
        problem.setProperty("requested", exception.getRequested());
        return problem;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail onNotFound(ResourceNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail onConflict(ConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail onDataIntegrityViolation(DataIntegrityViolationException exception) {
        // A unique constraint we did not check first — a duplicate SKU, code or
        // slug racing another request. The message is deliberately not echoed:
        // it names tables and constraints.
        log.warn("Database constraint rejected a request", exception);
        return problem(HttpStatus.CONFLICT, "That conflicts with something that already exists");
    }

    @ExceptionHandler(InvalidMovementException.class)
    ProblemDetail onInvalidMovement(InvalidMovementException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new HashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(MissingTenantContextException.class)
    ProblemDetail onMissingTenant(MissingTenantContextException exception) {
        log.error("Tenant-scoped work attempted with no tenant bound", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Request could not be processed");
    }

    private ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
