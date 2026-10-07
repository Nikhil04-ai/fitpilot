package com.project.fitness.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Handles @Valid failures (e.g. blank email on register)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }

    /**
     * Malformed JSON, or a value that can't be coerced into the target type
     * (e.g. "type":"JOGGING" when ActivityType has no such constant).
     *
     * Jackson's own message names Java classes and parser offsets, so it is logged
     * rather than returned.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> handleUnreadableRequest(Exception ex) {
        log.warn("Rejected malformed request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "The request couldn't be read. Check the field names and value types and try again."));
    }

    /**
     * Handles database-level constraint failures (foreign key violations, unique
     * constraint clashes, etc). Without this handler, Spring lets Hibernate's raw
     * SQL exception message — full of table/column names and bind parameters —
     * bubble straight to the client. That's both ugly and a security leak.
     *
     * The dead-session case (a localStorage userId whose row is gone) is caught
     * upstream now — see handleAccountNotFound below, which fires on the lookup
     * rather than on the insert. The fk_activity_user branch here stays as a
     * backstop for the narrow race where the row disappears between that lookup
     * and the flush, and because it is the one constraint whose violation can
     * only ever mean that.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String rootMsg = ex.getMostSpecificCause().getMessage();
        log.warn("Data integrity violation: {}", rootMsg);
        String friendly;

        if (rootMsg != null && rootMsg.contains("fk_activity_user")) {
            // Deliberately NOT the "account no longer exists" phrasing any more, and
            // deliberately not the phrase that makes the frontend force a logout.
            //
            // A real dead session never reaches here — it fails at the lookup and is
            // reported as AccountNotFoundException. What DID reach here, on a real
            // machine, was a schema left over from an older mapping: the constraint
            // still pointed at a legacy `user` table while accounts were being written
            // to `fitness_user`, so every single activity insert failed. The old
            // message blamed the person's session and told them to log out and back
            // in, which could never help, and the real cause (a mispointed FK) went
            // undiagnosed while the activity table stayed empty.
            log.error("fk_activity_user violated though the user was loaded successfully — "
                    + "check that the constraint references fitness_user(id) and not a legacy table");
            friendly = "Couldn't save this workout because of a database configuration problem "
                     + "on our side. This isn't something you can fix by logging in again — "
                     + "please report it.";
        } else if (rootMsg != null && rootMsg.toLowerCase().contains("foreign key")) {
            friendly = "This action references data that no longer exists. Please refresh the page and try again.";
        } else if (rootMsg != null && rootMsg.toLowerCase().contains("duplicate") ) {
            friendly = "That value is already in use. Please choose a different one.";
        } else {
            friendly = "This request couldn't be saved due to a data conflict. Please refresh and try again.";
        }

        Map<String, String> error = new HashMap<>();
        error.put("error", friendly);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * 409 — the JWT is valid but its user id no longer exists in the database.
     *
     * This used to be detected only by sniffing a MySQL foreign-key constraint name
     * out of a DataIntegrityViolationException (see above), which in practice never
     * fired: every service loads the user with findById() BEFORE it writes anything,
     * so a dead session failed on the lookup — reported as a generic 400 "User not
     * found: <id>" — and the insert that would have violated the constraint never
     * ran. The frontend keys its session self-healing off this 409, so it never got
     * the chance to recover: the person was left on a working-looking page where
     * every action failed with an error they couldn't clear.
     *
     * Throwing this from the lookup itself makes the signal come from the
     * authenticated identity rather than from a database constraint name, so it is
     * consistent across every endpoint and every database engine.
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleAccountNotFound(AccountNotFoundException ex) {
        log.info("Rejected request from a session whose account is gone: userId={}", ex.getUserId());
        Map<String, String> error = new HashMap<>();
        error.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * 403 — the caller is authenticated but asked for someone else's data.
     *
     * This handler MUST exist and MUST be declared for the specific type: an
     * AccessDeniedException thrown inside a controller or service never reaches
     * Spring Security's ExceptionTranslationFilter (that only sees exceptions that
     * escape the filter chain), so without this it falls into the RuntimeException
     * catch-all below and is reported to the client as a 400 "bad request" — which
     * reads like the client sent something malformed rather than "not yours".
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("error", ex.getMessage() != null ? ex.getMessage()
                : "You don't have access to this resource.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * 400 — the services throw plain RuntimeExceptions with human-readable messages
     * for expected failures ("Activity not found: …", "Rating must be between 1 and 5"),
     * so the message is safe to return.
     *
     * Deliberately NOT a catch-all for Throwable: a NullPointerException or a
     * Hibernate failure would otherwise be reported as a 400 carrying an internal
     * message, which both lies to the client (it wasn't their request's fault) and
     * leaks implementation detail. Those fall through to the handler below.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        if (isUnexpected(ex)) {
            return handleUnexpected(ex);
        }
        log.debug("Request rejected: {}", ex.getMessage());
        Map<String, String> error = new HashMap<>();
        error.put("error", ex.getMessage() != null ? ex.getMessage() : "Request failed.");
        return ResponseEntity.badRequest().body(error);
    }

    /** 500 — anything genuinely unexpected. Logged in full, reported without detail. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        // Spring raises a family of exceptions that already carry the right status —
        // NoResourceFoundException (404 for an unknown URL), HttpRequestMethodNot-
        // SupportedException (405), HttpMediaTypeNotSupportedException (415) and
        // anything thrown as a ResponseStatusException. They all implement
        // ErrorResponse. Catching Exception without this check would report every
        // one of them as a 500, so a simple typo in a URL would look like the server
        // had crashed.
        if (ex instanceof ErrorResponse errorResponse) {
            log.debug("Request failed with {}: {}", errorResponse.getStatusCode(), ex.getMessage());
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .body(Map.of("error", messageFor(errorResponse)));
        }

        log.error("Unhandled exception", ex);
        Map<String, String> error = new HashMap<>();
        error.put("error", "Something went wrong on our end. Please try again.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private String messageFor(ErrorResponse errorResponse) {
        HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
        if (status == HttpStatus.NOT_FOUND)                 return "That endpoint doesn't exist.";
        if (status == HttpStatus.METHOD_NOT_ALLOWED)        return "That HTTP method isn't supported on this endpoint.";
        if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE)    return "Unsupported content type — send JSON.";
        return "Request failed.";
    }

    /**
     * Distinguishes "a service rejected this request on purpose" from "something
     * broke". The services signal the former with `new RuntimeException("message")`
     * exactly — any subclass is a real fault.
     */
    private boolean isUnexpected(RuntimeException ex) {
        return ex.getClass() != RuntimeException.class;
    }
}
