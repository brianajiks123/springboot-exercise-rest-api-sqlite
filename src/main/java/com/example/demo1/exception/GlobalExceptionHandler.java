package com.example.demo1.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions thrown by the services to proper HTTP responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Business-rule violations raised by the service layer (bad request payload). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    /**
     * The request is well-formed but conflicts with the current state of the data,
     * e.g. deleting a product that existing orders still reference.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<String> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    /**
     * Two requests tried to update the same product row, e.g. two orders racing for
     * the last item in stock. The losing request must be retried by the client.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<String> handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("The product was modified by another request. Please retry.");
    }

    /**
     * Safety net for constraint violations that are not caught by bean validation,
     * e.g. a unique-constraint clash on order items.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("The request conflicts with the current state of the data.");
    }
}
